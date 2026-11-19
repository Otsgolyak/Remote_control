package com.remote.control.data.adb

interface AdbRepository {
    suspend fun listInstalledApps(): List<String>
    suspend fun launchApp(packageName: String): Boolean
}
