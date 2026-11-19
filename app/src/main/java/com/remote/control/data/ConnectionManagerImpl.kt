package com.remote.control.data

import android.os.Build
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import remote.RemoteConfigure
import remote.RemoteDeviceInfo
import remote.RemoteDirection
import remote.RemoteKeyCode
import remote.RemoteKeyInject
import remote.RemoteMessage
import remote.RemotePingResponse
import remote.RemoteSetActive
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

class ConnectionManagerImpl(
    private val pairingRepository: PairingRepository
) : ConnectionManager {

    private var socket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            disconnect()
            Logger.log("Подключение к ТВ $ip:$port по протоколу Android TV Remote v2...")
            try {
                val sslContext = pairingRepository.createSslContext()
                val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
                rawSocket.connect(InetSocketAddress(ip, port), 10000)
                rawSocket.keepAlive = true
                rawSocket.tcpNoDelay = true

                Logger.log("Запуск mTLS рукопожатия с $ip:$port...")
                rawSocket.startHandshake()
                Logger.log("mTLS рукопожатие с $ip:$port прошло успешно!")

                socket = rawSocket
                inputStream = rawSocket.inputStream
                outputStream = rawSocket.outputStream
                _lastError.value = null
                _isConnected.value = true

                startReaderLoop()
            } catch (e: Exception) {
                _isConnected.value = false
                val errMsg = "${e.javaClass.simpleName}: ${e.message}"
                _lastError.value = errMsg
                Logger.log("Ошибка подключения к $ip:$port -> $errMsg")
                throw e
            }
        }
    }

    private fun startReaderLoop() {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            val input = inputStream ?: return@launch
            try {
                while (isActive && _isConnected.value) {
                    val msg = RemoteMessage.parseDelimitedFrom(input)
                    if (msg == null) {
                        Logger.log("ТВ закрыл поток соединения (возможно требуется сопряжение по PIN)")
                        _lastError.value = "ТВ закрыл соединение (выполните сопряжение по PIN)"
                        disconnect()
                        break
                    }

                    when {
                        msg.hasRemoteConfigure() -> {
                            val tvInfo = msg.remoteConfigure.deviceInfo
                            Logger.log("Получен RemoteConfigure от ТВ: ${tvInfo.vendor} ${tvInfo.model}")
                            val reply = RemoteMessage.newBuilder()
                                .setRemoteConfigure(
                                    RemoteConfigure.newBuilder()
                                        .setCode1(622)
                                        .setDeviceInfo(
                                            RemoteDeviceInfo.newBuilder()
                                                .setModel(Build.MODEL)
                                                .setVendor(Build.MANUFACTURER)
                                                .setUnknown1(1)
                                                .setUnknown2("1")
                                                .setPackageName("com.remote.control")
                                                .setAppVersion("1.0.0")
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                            sendMessage(reply)
                        }
                        msg.hasRemoteSetActive() -> {
                            Logger.log("Получен RemoteSetActive от ТВ")
                            val reply = RemoteMessage.newBuilder()
                                .setRemoteSetActive(
                                    RemoteSetActive.newBuilder()
                                        .setActive(622)
                                        .build()
                                )
                                .build()
                            sendMessage(reply)
                        }
                        msg.hasRemotePingRequest() -> {
                            val pingVal = msg.remotePingRequest.val1
                            val pong = RemoteMessage.newBuilder()
                                .setRemotePingResponse(
                                    RemotePingResponse.newBuilder()
                                        .setVal1(pingVal)
                                        .build()
                                )
                                .build()
                            sendMessage(pong)
                        }
                        msg.hasRemoteError() -> {
                            Logger.log("Получен RemoteError от ТВ: ${msg.remoteError}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (_isConnected.value) {
                    Logger.log("Поток чтения ТВ прерван: ${e.javaClass.simpleName}: ${e.message}")
                    _lastError.value = e.message
                    disconnect()
                }
            }
        }
    }

    override suspend fun sendMessage(message: RemoteMessage) {
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val out = outputStream ?: throw IllegalStateException("Нет активного соединения с ТВ")
                    message.writeDelimitedTo(out)
                    out.flush()
                } catch (e: Exception) {
                    Logger.log("Ошибка отправки команды на ТВ: ${e.message}")
                    _lastError.value = e.message
                    disconnect()
                }
            }
        }
    }

    override suspend fun sendKey(keyCode: RemoteKeyCode) {
        Logger.log("Отправка клавиши: ${keyCode.name}")
        val msg = RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(keyCode)
                    .setDirection(RemoteDirection.SHORT)
                    .build()
            )
            .build()
        sendMessage(msg)
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val msg = RemoteMessage.parseFrom(data)
                sendMessage(msg)
            } catch (e: Exception) {
                Logger.log("Ошибка отправки raw-сообщения: ${e.message}")
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            readerJob?.cancel()
            readerJob = null
            runCatching { socket?.close() }
            socket = null
            inputStream = null
            outputStream = null
            _isConnected.value = false
        }
    }

    override fun getSocket(): Socket? = socket
}
