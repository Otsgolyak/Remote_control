package com.remote.control.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow

private val Context.dataStore by preferencesDataStore(name = "devices")

@Serializable
data class Device(val ip: String, val name: String)

class DeviceRepository(private val context: Context) {
    private val DEVICES_KEY = stringPreferencesKey("paired_devices")

    val pairedDevices: Flow<List<Device>> = context.dataStore.data.map { preferences ->
        val json = preferences[DEVICES_KEY] ?: "[]"
        try {
            Json.decodeFromString<List<Device>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addDevice(device: Device) {
        context.dataStore.edit { preferences ->
            val json = preferences[DEVICES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<Device>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            if (currentList.none { it.ip == device.ip }) {
                currentList.add(device)
                preferences[DEVICES_KEY] = Json.encodeToString(currentList)
            }
        }
    }
}
