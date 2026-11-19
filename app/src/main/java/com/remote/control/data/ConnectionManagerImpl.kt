package com.remote.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket

class ConnectionManagerImpl : ConnectionManager {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            socket = Socket(ip, port)
            outputStream = socket?.getOutputStream()
        }
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            outputStream?.write(data.size)
            outputStream?.write(data)
            outputStream?.flush()
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            socket?.close()
            socket = null
            outputStream = null
        }
    }

    override fun getSocket(): Socket? = socket
}
