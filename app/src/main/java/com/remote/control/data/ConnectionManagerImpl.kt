package com.remote.control.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.google.protobuf.ByteString
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import remote.RemoteConfigure
import remote.RemoteDeviceInfo
import remote.RemoteDirection
import remote.RemoteEditInfo
import remote.RemoteImeBatchEdit
import remote.RemoteImeObject
import remote.RemoteKeyCode
import remote.RemoteKeyInject
import remote.RemoteMessage
import remote.RemotePingResponse
import remote.RemoteSetActive
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

    companion object {
        // Feature bitmask from androidtvremote2:
        // PING(1) | KEY(2) | IME(4) | VOICE(8) | POWER(32) | VOLUME(64) | APP_LINK(512) = 623
        private const val REQUESTED_FEATURES = 1 or 2 or 4 or 8 or 32 or 64 or 512
        // Minimum voice chunk size accepted by Android TV Remote Service (8 KB)
        private const val VOICE_CHUNK_MIN_SIZE = 8 * 1024
    }

    private var socket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var voiceJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    @Volatile
    private var activeFeatures: Int = REQUESTED_FEATURES
    @Volatile
    private var imeCounter: Int = 0
    @Volatile
    private var fieldCounter: Int = 0
    @Volatile
    private var hasImeCounter: Boolean = false
    @Volatile
    private var hasFieldCounter: Boolean = false

    @Volatile
    private var pendingVoiceBegin: CompletableDeferred<Int>? = null
    @Volatile
    private var activeVoiceSessionId: Int? = null

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
                hasImeCounter = false
                hasFieldCounter = false
                activeFeatures = REQUESTED_FEATURES
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
                            val cfg = msg.remoteConfigure
                            val tvInfo = cfg.deviceInfo
                            val supported = cfg.code1
                            activeFeatures = if (supported != 0) (REQUESTED_FEATURES and supported) else REQUESTED_FEATURES
                            Logger.log("Получен RemoteConfigure от ТВ: ${tvInfo.vendor} ${tvInfo.model} (features=$supported -> active=$activeFeatures)")
                            val reply = RemoteMessage.newBuilder().setRemoteConfigure(
                                RemoteConfigure.newBuilder()
                                    .setCode1(activeFeatures)
                                    .setDeviceInfo(
                                        RemoteDeviceInfo.newBuilder()
                                            .setModel(Build.MODEL)
                                            .setVendor(Build.MANUFACTURER)
                                            .setUnknown1(1)
                                            .setUnknown2("1")
                                            .setPackageName("atvremote")
                                            .setAppVersion("1.0.0")
                                            .build()
                                    )
                                    .build()
                            ).build()
                            sendMessage(reply)
                        }
                        msg.hasRemoteSetActive() -> {
                            Logger.log("Получен RemoteSetActive от ТВ -> отправка active=$activeFeatures")
                            sendMessage(
                                RemoteMessage.newBuilder()
                                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(activeFeatures).build())
                                    .build()
                            )
                        }
                        msg.hasRemotePingRequest() -> {
                            sendMessage(
                                RemoteMessage.newBuilder()
                                    .setRemotePingResponse(
                                        RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1).build()
                                    )
                                    .build()
                            )
                        }
                        msg.hasRemoteImeKeyInject() -> {
                            val inject = msg.remoteImeKeyInject
                            imeCounter = inject.appInfo.counter
                            fieldCounter = inject.textFieldStatus.counterField
                            hasImeCounter = imeCounter != 0
                            hasFieldCounter = fieldCounter != 0
                            Logger.log("IME поле активно (${inject.appInfo.appPackage}): imeCounter=$imeCounter, fieldCounter=$fieldCounter")
                        }
                        msg.hasRemoteImeShowRequest() -> {
                            if (msg.remoteImeShowRequest.hasRemoteTextFieldStatus()) {
                                fieldCounter = msg.remoteImeShowRequest.remoteTextFieldStatus.counterField
                                hasFieldCounter = true
                                Logger.log("ТВ запросил клавиатуру (fieldCounter=$fieldCounter)")
                            }
                        }
                        msg.hasRemoteImeBatchEdit() -> {
                            if (!hasImeCounter) {
                                imeCounter = msg.remoteImeBatchEdit.imeCounter
                            }
                            if (!hasFieldCounter) {
                                fieldCounter = msg.remoteImeBatchEdit.fieldCounter
                            }
                            Logger.log("IME обновление счетчиков: imeCounter=$imeCounter, fieldCounter=$fieldCounter")
                        }
                        msg.hasRemoteVoiceBegin() -> {
                            val sid = msg.remoteVoiceBegin.sessionId
                            val pkg = msg.remoteVoiceBegin.packageName
                            Logger.log("ТВ открыл голосовую сессию (RemoteVoiceBegin): sessionId=$sid, pkg=$pkg")
                            val deferred = pendingVoiceBegin
                            if (deferred != null && !deferred.isCompleted) {
                                deferred.complete(sid)
                            }
                        }
                        msg.hasRemoteVoiceEnd() -> {
                            Logger.log("ТВ завершил голосовую сессию (RemoteVoiceEnd): sessionId=${msg.remoteVoiceEnd.sessionId}")
                            if (_isVoiceActive.value) {
                                stopVoiceInternal(sendEndPacket = false)
                            }
                        }
                        msg.hasRemoteError() -> {
                            Logger.log("RemoteError от ТВ: ${msg.remoteError}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (_isConnected.value) {
                    Logger.log("Поток чтения ТВ прерван: ${e.javaClass.simpleName}: ${e.message}")
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
        sendMessage(
            RemoteMessage.newBuilder()
                .setRemoteKeyInject(
                    RemoteKeyInject.newBuilder()
                        .setKeyCode(keyCode)
                        .setDirection(RemoteDirection.SHORT)
                        .build()
                )
                .build()
        )
    }

    override suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        Logger.log("Отправка текста через RemoteImeBatchEdit (ime=$imeCounter, field=$fieldCounter): \"$text\"")
        val imeObject = RemoteImeObject.newBuilder()
            .setStart(text.length)
            .setEnd(text.length)
            .setValue(text)
            .build()
        val editInfo = RemoteEditInfo.newBuilder()
            .setInsert(1)
            .setTextFieldStatus(imeObject)
            .build()
        val batchEdit = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(fieldCounter)
            .addEditInfo(editInfo)
            .build()
        val msg = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(batchEdit)
            .build()
        sendMessage(msg)
    }

    override suspend fun sendCharKey(ch: Char) {
        val kc = charToKeyCode(ch)
        if (kc != null) {
            sendKey(kc)
        }
    }

    private fun charToKeyCode(ch: Char): RemoteKeyCode? {
        return when (ch.uppercaseChar()) {
            'A' -> RemoteKeyCode.KEYCODE_A; 'B' -> RemoteKeyCode.KEYCODE_B; 'C' -> RemoteKeyCode.KEYCODE_C
            'D' -> RemoteKeyCode.KEYCODE_D; 'E' -> RemoteKeyCode.KEYCODE_E; 'F' -> RemoteKeyCode.KEYCODE_F
            'G' -> RemoteKeyCode.KEYCODE_G; 'H' -> RemoteKeyCode.KEYCODE_H; 'I' -> RemoteKeyCode.KEYCODE_I
            'J' -> RemoteKeyCode.KEYCODE_J; 'K' -> RemoteKeyCode.KEYCODE_K; 'L' -> RemoteKeyCode.KEYCODE_L
            'M' -> RemoteKeyCode.KEYCODE_M; 'N' -> RemoteKeyCode.KEYCODE_N; 'O' -> RemoteKeyCode.KEYCODE_O
            'P' -> RemoteKeyCode.KEYCODE_P; 'Q' -> RemoteKeyCode.KEYCODE_Q; 'R' -> RemoteKeyCode.KEYCODE_R
            'S' -> RemoteKeyCode.KEYCODE_S; 'T' -> RemoteKeyCode.KEYCODE_T; 'U' -> RemoteKeyCode.KEYCODE_U
            'V' -> RemoteKeyCode.KEYCODE_V; 'W' -> RemoteKeyCode.KEYCODE_W; 'X' -> RemoteKeyCode.KEYCODE_X
            'Y' -> RemoteKeyCode.KEYCODE_Y; 'Z' -> RemoteKeyCode.KEYCODE_Z
            '0' -> RemoteKeyCode.KEYCODE_0; '1' -> RemoteKeyCode.KEYCODE_1; '2' -> RemoteKeyCode.KEYCODE_2
            '3' -> RemoteKeyCode.KEYCODE_3; '4' -> RemoteKeyCode.KEYCODE_4; '5' -> RemoteKeyCode.KEYCODE_5
            '6' -> RemoteKeyCode.KEYCODE_6; '7' -> RemoteKeyCode.KEYCODE_7; '8' -> RemoteKeyCode.KEYCODE_8
            '9' -> RemoteKeyCode.KEYCODE_9; ' ' -> RemoteKeyCode.KEYCODE_SPACE
            '.' -> RemoteKeyCode.KEYCODE_PERIOD; ',' -> RemoteKeyCode.KEYCODE_COMMA
            '-' -> RemoteKeyCode.KEYCODE_MINUS; '=' -> RemoteKeyCode.KEYCODE_EQUALS
            '/' -> RemoteKeyCode.KEYCODE_SLASH; '@' -> RemoteKeyCode.KEYCODE_AT
            '+' -> RemoteKeyCode.KEYCODE_PLUS; '*' -> RemoteKeyCode.KEYCODE_STAR
            '#' -> RemoteKeyCode.KEYCODE_POUND; '\n' -> RemoteKeyCode.KEYCODE_ENTER
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startVoiceStream() {
        if (_isVoiceActive.value || !_isConnected.value) return
        Logger.log("Инициализация голосового поиска: отправка KEYCODE_SEARCH и ожидание session_id от ТВ...")

        val deferred = CompletableDeferred<Int>()
        pendingVoiceBegin = deferred

        try {
            // 1. According to Android TV Remote Protocol v2, client sends KEYCODE_SEARCH
            // and waits for the TV to send RemoteVoiceBegin with session_id
            sendKey(RemoteKeyCode.KEYCODE_SEARCH)

            val sessionId = withTimeoutOrNull(3000L) {
                deferred.await()
            }

            if (sessionId == null) {
                Logger.log("ТВ не прислал RemoteVoiceBegin за 3 сек (голосовой ассистент открыт без аудио-стрима)")
                return
            }

            // 2. Echo back RemoteVoiceBegin with the exact session_id received from the TV
            Logger.log("Подтверждение голосовой сессии sessionId=$sessionId -> старт записи PCM 8kHz...")
            val beginEcho = RemoteMessage.newBuilder()
                .setRemoteVoiceBegin(
                    RemoteVoiceBegin.newBuilder()
                        .setSessionId(sessionId)
                        .build()
                )
                .build()
            sendMessage(beginEcho)

            activeVoiceSessionId = sessionId
            _isVoiceActive.value = true

            voiceJob?.cancel()
            voiceJob = ioScope.launch {
                val sampleRate = 8000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    .coerceAtLeast(VOICE_CHUNK_MIN_SIZE)
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

                    // Android TV requires voice chunks to be at least 8 KB (8192 bytes)
                    val chunkBuffer = ByteArray(VOICE_CHUNK_MIN_SIZE)
                    while (isActive && _isVoiceActive.value) {
                        var offset = 0
                        while (offset < VOICE_CHUNK_MIN_SIZE && isActive && _isVoiceActive.value) {
                            val read = recorder.read(chunkBuffer, offset, VOICE_CHUNK_MIN_SIZE - offset)
                            if (read > 0) {
                                offset += read
                            } else {
                                break
                            }
                        }
                        if (offset > 0) {
                            // Pad remaining bytes with 0 if stopped mid-chunk so chunk is >= 8 KB
                            if (offset < VOICE_CHUNK_MIN_SIZE) {
                                chunkBuffer.fill(0, offset, VOICE_CHUNK_MIN_SIZE)
                            }
                            val payload = RemoteMessage.newBuilder()
                                .setRemoteVoicePayload(
                                    RemoteVoicePayload.newBuilder()
                                        .setSessionId(sessionId)
                                        .setSamples(ByteString.copyFrom(chunkBuffer))
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
        } finally {
            pendingVoiceBegin = null
        }
    }

    private suspend fun stopVoiceInternal(sendEndPacket: Boolean) {
        val sid = activeVoiceSessionId
        activeVoiceSessionId = null
        _isVoiceActive.value = false
        voiceJob?.cancel()
        voiceJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        if (sendEndPacket && sid != null && _isConnected.value) {
            Logger.log("Отправка RemoteVoiceEnd (sessionId=$sid)")
            val endMsg = RemoteMessage.newBuilder()
                .setRemoteVoiceEnd(
                    RemoteVoiceEnd.newBuilder()
                        .setSessionId(sid)
                        .build()
                )
                .build()
            sendMessage(endMsg)
        }
    }

    override suspend fun stopVoiceStream() {
        if (!_isVoiceActive.value) return
        stopVoiceInternal(sendEndPacket = true)
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                sendMessage(RemoteMessage.parseFrom(data))
            } catch (e: Exception) {
                Logger.log("Ошибка отправки raw-сообщения: ${e.message}")
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _isVoiceActive.value = false
            activeVoiceSessionId = null
            pendingVoiceBegin?.cancel()
            pendingVoiceBegin = null
            voiceJob?.cancel()
            voiceJob = null
            readerJob?.cancel()
            readerJob = null
            runCatching {
                audioRecord?.stop()
                audioRecord?.release()
            }
            audioRecord = null
            runCatching { socket?.close() }
            socket = null
            inputStream = null
            outputStream = null
            _isConnected.value = false
        }
    }

    override fun getSocket(): Socket? = socket
}
