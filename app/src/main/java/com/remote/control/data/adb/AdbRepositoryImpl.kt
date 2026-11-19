package com.remote.control.data.adb

import com.shazam.dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdbRepositoryImpl(private val dadb: Dadb) : AdbRepository {

    override suspend fun listInstalledApps(): List<String> = withContext(Dispatchers.IO) {
        val response = dadb.execute("pm list packages -3")
        response.allOutput
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
    }

    override suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Using monkey to launch the app
        val response = dadb.execute("monkey -p $packageName 1")
        return@withContext response.exitCode == 0
    }
}
