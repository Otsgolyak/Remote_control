package com.remote.control.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.google.protobuf.ByteString
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import remote.RemoteAppInfo
import remote.RemoteConfigure
import remote.RemoteDeviceInfo
import remote.RemoteDirection
import remote.RemoteImeKeyInject
import remote.RemoteKeyCode
import remote.RemoteKeyInject
import remote.RemoteMessage
import remote.RemotePingResponse
import remote.RemoteSetActive
import remote.RemoteTextFieldStatus
import remote.RemoteVoiceBegin
import remote.RemoteVoiceEnd
import remote.RemoteVoicePayload
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
    private var voiceJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    @Volatile
    private var imeCounter: Int = 0
    @Volatile
    private var fieldCounter: Int = 0

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isVoiceActive = MutableStateFlow(false)
    override val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

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
                        Logger.log("ТВ закрыл поток соединения")
                        _lastError.value = "ТВ закрыл соединение"
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
                        msg.hasRemoteImeShowRequest() -> {
                            val status = msg.remoteImeShowRequest.remoteTextFieldStatus
                            fieldCounter = status.counterField
                            Logger.log("ТВ активировал поле ввода текста (counter=$fieldCounter)")
                        }
                        msg.hasRemoteImeKeyInject() -> {
                            fieldCounter = msg.remoteImeKeyInject.textFieldStatus.counterField
                            imeCounter = msg.remoteImeKeyInject.appInfo.counter
                        }
                        msg.hasRemoteImeBatchEdit() -> {
                            imeCounter = msg.remoteImeBatchEdit.imeCounter
                            fieldCounter = msg.remoteImeBatchEdit.fieldCounter
                        }
                        msg.hasRemoteVoiceBegin() -> {
                            Logger.log("ТВ подтвердил прием голосового потока (RemoteVoiceBegin)")
                        }
                        msg.hasRemoteVoiceEnd() -> {
                            Logger.log("ТВ завершил прием голосового потока (RemoteVoiceEnd)")
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

    override suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        Logger.log("Отправка текста на ТВ: \"$text\"")
        // 1. Send via RemoteImeKeyInject (for native Android TV input fields)
        val imeMsg = RemoteMessage.newBuilder()
            .setRemoteImeKeyInject(
                RemoteImeKeyInject.newBuilder()
                    .setAppInfo(
                        RemoteAppInfo.newBuilder()
                            .setCounter(imeCounter)
                            .build()
                    )
                    .setTextFieldStatus(
                        RemoteTextFieldStatus.newBuilder()
                            .setCounterField(fieldCounter)
                            .setValue(text)
                            .setStart(text.length)
                            .setEnd(text.length)
                            .build()
                    )
                    .build()
            )
            .build()
        sendMessage(imeMsg)

        // 2. Also send individual keycodes for ASCII characters (works in YouTube / custom TV keyboards)
        for (ch in text) {
            val kc = charToKeyCode(ch)
            if (kc != null) {
                sendKey(kc)
                delay(45)
            }
        }
    }

    override suspend fun sendCharKey(ch: Char) {
        val kc = charToKeyCode(ch)
        if (kc != null) {
            sendKey(kc)
        } else {
            sendText(ch.toString())
        }
    }

    private fun charToKeyCode(ch: Char): RemoteKeyCode? {
        return when (ch.uppercaseChar()) {
            'A' -> RemoteKeyCode.KEYCODE_A
            'B' -> RemoteKeyCode.KEYCODE_B
            'C' -> RemoteKeyCode.KEYCODE_C
            'D' -> RemoteKeyCode.KEYCODE_D
            'E' -> RemoteKeyCode.KEYCODE_E
            'F' -> RemoteKeyCode.KEYCODE_F
            'G' -> RemoteKeyCode.KEYCODE_G
            'H' -> RemoteKeyCode.KEYCODE_H
            'I' -> RemoteKeyCode.KEYCODE_I
            'J' -> RemoteKeyCode.KEYCODE_J
            'K' -> RemoteKeyCode.KEYCODE_K
            'L' -> RemoteKeyCode.KEYCODE_L
            'M' -> RemoteKeyCode.KEYCODE_M
            'N' -> RemoteKeyCode.KEYCODE_N
            'O' -> RemoteKeyCode.KEYCODE_O
            'P' -> RemoteKeyCode.KEYCODE_P
            'Q' -> RemoteKeyCode.KEYCODE_Q
            'R' -> RemoteKeyCode.KEYCODE_R
            'S' -> RemoteKeyCode.KEYCODE_S
            'T' -> RemoteKeyCode.KEYCODE_T
            'U' -> RemoteKeyCode.KEYCODE_U
            'V' -> RemoteKeyCode.KEYCODE_V
            'W' -> RemoteKeyCode.KEYCODE_W
            'X' -> RemoteKeyCode.KEYCODE_X
            'Y' -> RemoteKeyCode.KEYCODE_Y
            'Z' -> RemoteKeyCode.KEYCODE_Z
            '0' -> RemoteKeyCode.KEYCODE_0
            '1' -> RemoteKeyCode.KEYCODE_1
            '2' -> RemoteKeyCode.KEYCODE_2
            '3' -> RemoteKeyCode.KEYCODE_3
            '4' -> RemoteKeyCode.KEYCODE_4
            '5' -> RemoteKeyCode.KEYCODE_5
            '6' -> RemoteKeyCode.KEYCODE_6
            '7' -> RemoteKeyCode.KEYCODE_7
            '8' -> RemoteKeyCode.KEYCODE_8
            '9' -> RemoteKeyCode.KEYCODE_9
            ' ' -> RemoteKeyCode.KEYCODE_SPACE
            '.' -> RemoteKeyCode.KEYCODE_PERIOD
            ',' -> RemoteKeyCode.KEYCODE_COMMA
            '-' -> RemoteKeyCode.KEYCODE_MINUS
            '=' -> RemoteKeyCode.KEYCODE_EQUALS
            '/' -> RemoteKeyCode.KEYCODE_SLASH
            '@' -> RemoteKeyCode.KEYCODE_AT
            '+' -> RemoteKeyCode.KEYCODE_PLUS
            '*' -> RemoteKeyCode.KEYCODE_STAR
            '#' -> RemoteKeyCode.KEYCODE_POUND
            '\n' -> RemoteKeyCode.KEYCODE_ENTER
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startVoiceStream() {
        if (_isVoiceActive.value || !_isConnected.value) return
        
        Logger.log("Запуск голосового ввода...")
        sendKey(RemoteKeyCode.KEYCODE_VOICE_ASSIST)
        
        // Отправляем запрос и ждем 200мс, чтобы не спамить в случае ошибки
        val beginMsg = RemoteMessage.newBuilder()
            .setRemoteVoiceBegin(RemoteVoiceBegin.getDefaultInstance())
            .build()
        sendMessage(beginMsg)
        
        delay(300) 
        
        _isVoiceActive.value = true
        // ... (audio stream logic)

        voiceJob?.cancel()
        voiceJob = ioScope.launch {
            val sampleRate = 8000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuf * 2
                )
                audioRecord = recorder
                recorder.startRecording()
                val buffer = ByteArray(4096)
                while (isActive && _isVoiceActive.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val payload = RemoteMessage.newBuilder()
                            .setRemoteVoicePayload(
                                RemoteVoicePayload.newBuilder()
                                    .setAudioData(ByteString.copyFrom(buffer, 0, read))
                                    .build()
                            )
                            .build()
                        sendMessage(payload)
                    }
                }
            } catch (e: Exception) {
                Logger.log("Ошибка записи микрофона: ${e.message}")
            } finally {
                runCatching {
                    audioRecord?.stop()
                    audioRecord?.release()
                }
                audioRecord = null
            }
        }
    }

    override suspend fun stopVoiceStream() {
        if (!_isVoiceActive.value) return
        _isVoiceActive.value = false
        voiceJob?.cancel()
        voiceJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        Logger.log("Остановка голосового ввода (RemoteVoiceEnd)")
        val endMsg = RemoteMessage.newBuilder()
            .setRemoteVoiceEnd(RemoteVoiceEnd.getDefaultInstance())
            .build()
        sendMessage(endMsg)
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
            _isVoiceActive.value = false
            voiceJob?.cancel()
            voiceJob = null
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
