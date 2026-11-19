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

@Serializable
data class CustomShortcutData(
    val title: String,
    val deepLink: String,
    val iconName: String = "tv"
)

class DeviceRepository(private val context: Context) {
    private val DEVICES_KEY = stringPreferencesKey("paired_devices")
    private val SELECTED_IP_KEY = stringPreferencesKey("selected_device_ip")
    private val SHORTCUTS_KEY = stringPreferencesKey("custom_shortcuts")

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

    val customShortcuts: Flow<List<CustomShortcutData>> = context.dataStore.data.map { preferences ->
        val json = preferences[SHORTCUTS_KEY] ?: "[]"
        try {
            Json.decodeFromString<List<CustomShortcutData>>(json)
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

    suspend fun renameDevice(ip: String, newName: String) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return
        context.dataStore.edit { preferences ->
            val json = preferences[DEVICES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<Device>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val idx = currentList.indexOfFirst { it.ip == ip }
            if (idx >= 0) {
                currentList[idx] = currentList[idx].copy(name = cleanName)
                preferences[DEVICES_KEY] = Json.encodeToString(currentList)
            }
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

    suspend fun addCustomShortcut(shortcut: CustomShortcutData) {
        context.dataStore.edit { preferences ->
            val json = preferences[SHORTCUTS_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<CustomShortcutData>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.title.equals(shortcut.title, ignoreCase = true) }
            currentList.add(shortcut)
            preferences[SHORTCUTS_KEY] = Json.encodeToString(currentList)
        }
    }

    suspend fun removeCustomShortcut(title: String) {
        context.dataStore.edit { preferences ->
            val json = preferences[SHORTCUTS_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<CustomShortcutData>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.title == title }
            preferences[SHORTCUTS_KEY] = Json.encodeToString(currentList)
        }
    }
}
