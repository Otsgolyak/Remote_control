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

class ConnectionManagerImpl(private val pairingRepository: PairingRepository) : ConnectionManager {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val keyPair = pairingRepository.getKeyPair()
                
                // For simplicity in this implementation, we are using a self-signed approach
                // In a real Android TV Remote Protocol v2 implementation, you would need
                // to manage the certificates properly.
                
                val sslContext = SSLContext.getInstance("TLSv1.3")
                
                // Create KeyManager (assuming we just need a dummy structure for now as per instructions)
                // mTLS requires a certificate chain. Since I am just creating a keypair,
                // this is a simplified stub implementation.
                
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                // ... (Add certificate to keystore)
                
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, null)
                
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore) // Trusting self-signed for now
                
                sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
                
                val socketFactory = sslContext.socketFactory
                val sslSocket = socketFactory.createSocket(ip, port) as javax.net.ssl.SSLSocket
                sslSocket.startHandshake()
                
                socket = sslSocket
                outputStream = socket?.getOutputStream()
            } catch (e: Exception) {
                com.remote.control.utils.Logger.log("Connection/SSL error: ${e.message}")
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
            socket?.close()
            socket = null
            outputStream = null
        }
    }

    override fun getSocket(): Socket? = socket
}
