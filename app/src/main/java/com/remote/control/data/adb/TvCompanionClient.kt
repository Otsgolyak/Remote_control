package com.remote.control.data.adb

import android.content.Context
import com.remote.control.utils.Logger
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class TvCompanionClient(private val context: Context) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null

    private val _isCompanionConnected = MutableStateFlow(false)
    val isCompanionConnected: StateFlow<Boolean> = _isCompanionConnected.asStateFlow()

    suspend fun tryConnect(ip: String): Boolean = withContext(Dispatchers.IO) {
        if (_isCompanionConnected.value && socket?.isConnected == true) return@withContext true
        disconnect()
        try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, 6666), 2000)
            s.tcpNoDelay = true
            socket = s
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            _isCompanionConnected.value = true
            Logger.log("TV Курсор-компаньон подключен по Wi-Fi ($ip:6666)!")
            true
        } catch (e: Exception) {
            _isCompanionConnected.value = false
            false
        }
    }

    suspend fun installAndActivateOnTv(ip: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Logger.log("Установка модуля курсора на ТВ ($ip:5555) через ADB...")
            val apkFile = File(context.cacheDir, "tv-cursor.apk")
            context.assets.open("tv-cursor.apk").use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }

            Dadb.create(ip, 5555).use { dadb ->
                Logger.log("ADB соединение с ТВ установлено, установка tv-cursor.apk...")
                dadb.install(apkFile)
                val svc = "com.remote.control.companion/.CursorAccessibilityService"
                dadb.shell("appops set com.remote.control.companion SYSTEM_ALERT_WINDOW allow")
                dadb.shell("settings put secure enabled_accessibility_services $svc")
                dadb.shell("settings put secure accessibility_enabled 1")
                Logger.log("Курсор-компаньон установлен и активирован на ТВ!")
            }

            delay(1000)
            val connected = tryConnect(ip)
            if (connected) {
                Result.success("Курсор установлен и запущен на экране ТВ!")
            } else {
                Result.success("Модуль установлен на ТВ! Включите его в Спецвозможностях ТВ, если курсор не появился.")
            }
        } catch (e: Exception) {
            Logger.log("Ошибка ADB-установки курсора на ТВ: ${e.message}")
            Result.failure(e)
        }
    }

    fun sendMove(dx: Float, dy: Float) {
        if (!_isCompanionConnected.value) return
        ioScope.launch {
            try {
                val w = writer ?: return@launch
                synchronized(this@TvCompanionClient) {
                    w.write("MOVE:${"%.1f".format(dx)}:${"%.1f".format(dy)}\n")
                    w.flush()
                }
            } catch (e: Exception) {
                disconnect()
            }
        }
    }

    fun sendClick() {
        if (!_isCompanionConnected.value) return
        ioScope.launch {
            try {
                val w = writer ?: return@launch
                synchronized(this@TvCompanionClient) {
                    w.write("CLICK:0:0\n")
                    w.flush()
                }
            } catch (e: Exception) {
                disconnect()
            }
        }
    }

    fun disconnect() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
        _isCompanionConnected.value = false
    }
}
