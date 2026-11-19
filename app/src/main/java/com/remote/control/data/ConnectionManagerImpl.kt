package com.remote.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import com.remote.control.data.wifi.PairingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.remote.control.utils.Logger

class ConnectionManagerImpl(private val pairingRepository: PairingRepository) : ConnectionManager {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            Logger.log("Попытка подключения к IP: $ip, порт: $port")
            try {
                val keyPair = pairingRepository.getKeyPair()
                
                val sslContext = SSLContext.getInstance("TLSv1.3")
                
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, null)
                
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore)
                
                sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
                
                val socketFactory = sslContext.socketFactory
                Logger.log("Создание SSL сокета...")
                val sslSocket = socketFactory.createSocket(ip, port) as javax.net.ssl.SSLSocket
                
                Logger.log("Начало SSL рукопожатия...")
                sslSocket.startHandshake()
                Logger.log("Успех SSL рукопожатия")
                
                socket = sslSocket
                outputStream = socket?.getOutputStream()
                _isConnected.value = true
                Logger.log("Соединение установлено успешно")
            } catch (e: Exception) {
                _isConnected.value = false
                Logger.log("Ошибка SSL рукопожатия или подключения: ${e.message}")
                throw e
            }
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
            try {
                socket?.close()
                Logger.log("Соединение разорвано")
            } catch (e: Exception) {
                Logger.log("Ошибка при разрыве соединения: ${e.message}")
            } finally {
                socket = null
                outputStream = null
                _isConnected.value = false
            }
        }
    }

    override fun getSocket(): Socket? = socket
}
