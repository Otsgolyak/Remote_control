package com.remote.control.data

import kotlinx.coroutines.flow.StateFlow
import remote.RemoteKeyCode
import remote.RemoteMessage
import java.net.Socket

interface ConnectionManager {
    val isConnected: StateFlow<Boolean>
    val lastError: StateFlow<String?>
    suspend fun connect(ip: String, port: Int = 6466)
    suspend fun send(data: ByteArray)
    suspend fun sendMessage(message: RemoteMessage)
    suspend fun sendKey(keyCode: RemoteKeyCode)
    suspend fun disconnect()
    fun getSocket(): Socket?
}
