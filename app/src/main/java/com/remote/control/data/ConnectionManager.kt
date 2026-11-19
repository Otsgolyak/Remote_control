package com.remote.control.data

import java.net.Socket

interface ConnectionManager {
    suspend fun connect(ip: String, port: Int)
    suspend fun send(data: ByteArray)
    suspend fun disconnect()
    fun getSocket(): Socket?
}
