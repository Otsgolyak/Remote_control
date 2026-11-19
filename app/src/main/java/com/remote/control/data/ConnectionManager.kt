package com.remote.control.data

import kotlinx.coroutines.flow.StateFlow
import java.net.Socket

interface ConnectionManager {
    val isConnected: StateFlow<Boolean>
    suspend fun connect(ip: String, port: Int)
    suspend fun send(data: ByteArray)
    suspend fun disconnect()
    fun getSocket(): Socket?
}
