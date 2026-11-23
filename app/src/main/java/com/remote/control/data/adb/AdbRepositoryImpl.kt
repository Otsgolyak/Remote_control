package com.remote.control.data.adb

import android.content.Context
import com.remote.control.utils.Logger
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AdbRepositoryImpl(
    private val context: Context,
    private val host: String,
    private val port: Int = 5555
) : AdbRepository {

    /**
     * Загружает существующую пару ADB-ключей из приватного каталога приложения
     * либо генерирует её один раз и сохраняет. Благодаря этому телевизор
     * не запрашивает подтверждение отладки при каждом запуске приложения.
     */
    /**
     * Возвращает пару ADB-ключей из приватного каталога приложения.
     * Метод `AdbKeyPair.read` загружает существующие ключи, а если их нет —
     * автоматически генерирует и сохраняет новую пару.
     */
    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")
        val existed = privateKeyFile.exists() && publicKeyFile.exists()
        if (!existed) {
            Logger.log("ADB: генерация новой пары ключей (${privateKeyFile.name})...")
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        val keyPair = AdbKeyPair.read(privateKeyFile, publicKeyFile)
        Logger.log(
            if (existed) "ADB: загружена сохраненная пара ключей (${privateKeyFile.name})"
            else "ADB: сгенерирована и сохранена новая пара ключей (${privateKeyFile.name})"
        )
        return keyPair
    }

    override suspend fun listInstalledApps(): List<String> = withContext(Dispatchers.IO) {
        val keyPair = loadOrCreateKeyPair()
        Dadb.create(host, port, keyPair).use { dadb ->
            val response = dadb.shell("pm list packages -3")
            Logger.log("ADB: pm list packages -3 -> exitCode=${response.exitCode}")
            response.allOutput
                .lines()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
        }
    }

    override suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val keyPair = loadOrCreateKeyPair()
        Dadb.create(host, port, keyPair).use { dadb ->
            val cmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
            Logger.log("ADB: запуск приложения -> $cmd")
            val response = dadb.shell(cmd)
            Logger.log("ADB: am start exitCode=${response.exitCode}, output=${response.output}")
            response.exitCode == 0
        }
    }
}
