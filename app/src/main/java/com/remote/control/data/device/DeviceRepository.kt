package com.remote.control.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "devices")

@Serializable
data class Device(val ip: String, val name: String)

class DeviceRepository(private val context: Context) {
    private val DEVICES_KEY = stringPreferencesKey("paired_devices")
    private val SELECTED_IP_KEY = stringPreferencesKey("selected_device_ip")

    val pairedDevices: Flow<List<Device>> = context.dataStore.data.map { preferences ->
        val json = preferences[DEVICES_KEY] ?: "[]"
        try {
            Json.decodeFromString<List<Device>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val selectedDeviceIp: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_IP_KEY]
    }

    suspend fun addDevice(device: Device) {
        context.dataStore.edit { preferences ->
            val json = preferences[DEVICES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<Device>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val existingIdx = currentList.indexOfFirst { it.ip == device.ip }
            if (existingIdx >= 0) {
                currentList[existingIdx] = device
            } else {
                currentList.add(device)
            }
            preferences[DEVICES_KEY] = Json.encodeToString(currentList)
            preferences[SELECTED_IP_KEY] = device.ip
        }
    }

    suspend fun removeDevice(ip: String) {
        context.dataStore.edit { preferences ->
            val json = preferences[DEVICES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<Device>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.ip == ip }
            preferences[DEVICES_KEY] = Json.encodeToString(currentList)
            if (preferences[SELECTED_IP_KEY] == ip) {
                val nextIp = currentList.firstOrNull()?.ip
                if (nextIp != null) {
                    preferences[SELECTED_IP_KEY] = nextIp
                } else {
                    preferences.remove(SELECTED_IP_KEY)
                }
            }
        }
    }

    suspend fun setSelectedDevice(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_IP_KEY] = ip
        }
    }
}
