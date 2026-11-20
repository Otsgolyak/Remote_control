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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import kotlin.concurrent.thread

class ConnectionManagerImpl(
    private val pairingRepository: PairingRepository
) : ConnectionManager {

    companion object {
        // Feature bitmask from androidtvremote2:
        // PING(1) | KEY(2) | IME(4) | VOICE(8) | POWER(32) | VOLUME(64) | APP_LINK(512) = 623
        private const val REQUESTED_FEATURES = 1 or 2 or 4 or 8 or 32 or 64 or 512
        // Android TV Remote Service requires 8 KB min / 20 KB max voice chunks at 8000 Hz 16-bit mono PCM
        private const val VOICE_CHUNK_MIN_SIZE = 8 * 1024
        private const val VOICE_CHUNK_TARGET_SIZE = 16 * 1024
    }

    private var socket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var voiceThread: Thread? = null
    private var voiceSenderJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private var voiceChannel = Channel<ByteArray>(Channel.BUFFERED)

    @Volatile private var activeFeatures: Int = REQUESTED_FEATURES
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0
    @Volatile private var hasImeCounter: Boolean = false
    @Volatile private var hasFieldCounter: Boolean = false
    @Volatile private var pendingVoiceBegin: CompletableDeferred<Int>? = null
    @Volatile private var activeVoiceSessionId: Int? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _isVoiceActive = MutableStateFlow(false)
    override val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            disconnect()
            Logger.log("Подключение к ТВ $ip:$port (Android TV Remote v2)...")
            try {
                val sslContext = pairingRepository.createSslContext()
                val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
                rawSocket.connect(InetSocketAddress(ip, port), 10000)
                rawSocket.keepAlive = true
                rawSocket.tcpNoDelay = true
                rawSocket.startHandshake()

                socket = rawSocket
                inputStream = rawSocket.inputStream
                outputStream = rawSocket.outputStream
                hasImeCounter = false
                hasFieldCounter = false
                activeFeatures = REQUESTED_FEATURES
                _isConnected.value = true
                _lastError.value = null
                Logger.log("mTLS соединение с $ip:$port установлено!")
                startReaderLoop()
            } catch (e: Exception) {
                _isConnected.value = false
                _lastError.value = e.message
                Logger.log("Ошибка подключения к $ip:$port -> ${e.message}")
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
                    val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
                    when {
                        msg.hasRemoteConfigure() -> {
                            val cfg = msg.remoteConfigure
                            val supported = cfg.code1
                            activeFeatures = if (supported != 0) (REQUESTED_FEATURES and supported) else REQUESTED_FEATURES
                            Logger.log("RemoteConfigure от ТВ: ${cfg.deviceInfo.vendor} ${cfg.deviceInfo.model} (supported=$supported -> active=$activeFeatures)")
                            val reply = RemoteMessage.newBuilder()
                                .setRemoteConfigure(
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
                                )
                                .build()
                            sendMessage(reply)
                        }
                        msg.hasRemoteSetActive() -> {
                            Logger.log("RemoteSetActive от ТВ -> ответ active=$activeFeatures")
                            val reply = RemoteMessage.newBuilder()
                                .setRemoteSetActive(
                                    RemoteSetActive.newBuilder()
                                        .setActive(activeFeatures)
                                        .build()
                                )
                                .build()
                            sendMessage(reply)
                        }
                        msg.hasRemotePingRequest() -> {
                            // CRITICAL: Must reply to every ping within 15s or TV drops the connection!
                            val pong = RemoteMessage.newBuilder()
                                .setRemotePingResponse(
                                    RemotePingResponse.newBuilder()
                                        .setVal1(msg.remotePingRequest.val1)
                                        .build()
                                )
                                .build()
                            sendMessage(pong)
                        }
                        msg.hasRemoteImeKeyInject() -> {
                            val inject = msg.remoteImeKeyInject
                            imeCounter = inject.appInfo.counter
                            fieldCounter = inject.textFieldStatus.counterField
                            hasImeCounter = imeCounter != 0
                            hasFieldCounter = fieldCounter != 0
                            Logger.log("IME поле активно (${inject.appInfo.appPackage}): ime=$imeCounter, field=$fieldCounter")
                        }
                        msg.hasRemoteImeShowRequest() -> {
                            if (msg.remoteImeShowRequest.hasRemoteTextFieldStatus()) {
                                fieldCounter = msg.remoteImeShowRequest.remoteTextFieldStatus.counterField
                                hasFieldCounter = true
                            }
                        }
                        msg.hasRemoteImeBatchEdit() -> {
                            if (!hasImeCounter) imeCounter = msg.remoteImeBatchEdit.imeCounter
                            if (!hasFieldCounter) fieldCounter = msg.remoteImeBatchEdit.fieldCounter
                        }
                        msg.hasRemoteVoiceBegin() -> {
                            val sid = msg.remoteVoiceBegin.sessionId
                            activeVoiceSessionId = sid
                            Logger.log("ПРИНЯТО RemoteVoiceBegin: sid=$sid, pkg=${msg.remoteVoiceBegin.packageName}")
                            pendingVoiceBegin?.let { deferred ->
                                if (!deferred.isCompleted) deferred.complete(sid)
                            }
                        }
                        msg.hasRemoteVoicePayload() -> {
                            Logger.log("ПРИНЯТО RemoteVoicePayload: sid=${msg.remoteVoicePayload.sessionId}, bytes=${msg.remoteVoicePayload.samples.size()}")
                        }
                        msg.hasRemoteVoiceEnd() -> {
                            Logger.log("ПРИНЯТО RemoteVoiceEnd: sid=${msg.remoteVoiceEnd.sessionId}")
                            _isVoiceActive.value = false
                        }
                        msg.hasRemoteError() -> {
                            Logger.log("RemoteError от ТВ: ${msg.remoteError}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (_isConnected.value) {
                    Logger.log("Разрыв соединения с ТВ: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    override suspend fun sendMessage(message: RemoteMessage) {
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val out = outputStream ?: return@withContext
                    message.writeDelimitedTo(out)
                    out.flush()
                } catch (e: Exception) {
                    _lastError.value = e.message
                    disconnect()
                }
            }
        }
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                sendMessage(RemoteMessage.parseFrom(data))
            } catch (e: Exception) {
                _lastError.value = e.message
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
        Logger.log("Вставка текста (RemoteImeBatchEdit ime=$imeCounter, field=$fieldCounter): \"$text\"")
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
        sendMessage(RemoteMessage.newBuilder().setRemoteImeBatchEdit(batchEdit).build())
    }

    override suspend fun sendCharKey(ch: Char) {
        val kc = when (ch.uppercaseChar()) {
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
            '\n' -> RemoteKeyCode.KEYCODE_ENTER; else -> null
        }
        if (kc != null) sendKey(kc)
    }

    @SuppressLint("MissingPermission")
    override suspend fun startVoiceStream() {
        if (_isVoiceActive.value || !_isConnected.value) return

        val deferred = CompletableDeferred<Int>()
        pendingVoiceBegin = deferred

        Logger.log("Запуск голосового поиска: отправка KEYCODE_SEARCH...")
        sendKey(RemoteKeyCode.KEYCODE_SEARCH)

        val sid = withTimeoutOrNull(2500L) {
            deferred.await()
        } ?: activeVoiceSessionId

        pendingVoiceBegin = null

        if (sid == null) {
            Logger.log("ТВ не прислал session_id для голоса (открыт экран поиска ТВ)")
            return
        }

        // Echo back RemoteVoiceBegin with the TV's session_id
        Logger.log("Эхо RemoteVoiceBegin(sessionId=$sid) и старт записи PCM 8000 Hz 16-bit Mono...")
        sendMessage(
            RemoteMessage.newBuilder()
                .setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(sid).build())
                .build()
        )

        voiceChannel = Channel(Channel.BUFFERED)
        _isVoiceActive.value = true

        voiceSenderJob?.cancel()
        voiceSenderJob = ioScope.launch(Dispatchers.IO) {
            for (chunk in voiceChannel) {
                val padded = if (chunk.size < VOICE_CHUNK_MIN_SIZE) {
                    ByteArray(VOICE_CHUNK_MIN_SIZE).also { System.arraycopy(chunk, 0, it, 0, chunk.size) }
                } else {
                    chunk
                }
                sendMessage(
                    RemoteMessage.newBuilder()
                        .setRemoteVoicePayload(
                            RemoteVoicePayload.newBuilder()
                                .setSessionId(sid)
                                .setSamples(ByteString.copyFrom(padded))
                                .build()
                        )
                        .build()
                )
            }
        }

        voiceThread = thread(start = true, name = "VoiceStreamThread") {
            var recorder: AudioRecord? = null
            try {
                // Spec requirement: 8000 Hz, PCM 16-bit, Mono, sent in 8KB..20KB chunks
                val sampleRate = 8000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(VOICE_CHUNK_TARGET_SIZE)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                recorder.startRecording()

                val chunkBuffer = ByteArray(VOICE_CHUNK_TARGET_SIZE)
                var totalSent = 0L
                while (_isVoiceActive.value) {
                    var offset = 0
                    while (offset < VOICE_CHUNK_TARGET_SIZE && _isVoiceActive.value) {
                        val read = recorder.read(chunkBuffer, offset, VOICE_CHUNK_TARGET_SIZE - offset)
                        if (read > 0) {
                            offset += read
                        } else {
                            break
                        }
                    }
                    if (offset > 0) {
                        voiceChannel.trySend(chunkBuffer.copyOf(offset))
                        totalSent += offset
                        Logger.log("Голос: отправлен чанк $offset байт (всего $totalSent байт)")
                    }
                }
            } catch (e: Exception) {
                Logger.log("Ошибка записи микрофона: ${e.message}")
            } finally {
                runCatching {
                    recorder?.stop()
                    recorder?.release()
                }
            }
        }
    }

    override suspend fun stopVoiceStream() {
        if (!_isVoiceActive.value) return
        _isVoiceActive.value = false
        runCatching { voiceThread?.join(400) }
        voiceThread = null
        voiceChannel.close()
        runCatching { voiceSenderJob?.join() }
        voiceSenderJob = null

        val sid = activeVoiceSessionId
        if (sid != null && _isConnected.value) {
            Logger.log("Завершение голосовой сессии: отправка RemoteVoiceEnd(sessionId=$sid)")
            sendMessage(
                RemoteMessage.newBuilder()
                    .setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(sid).build())
                    .build()
            )
        }
    }

    override suspend fun disconnect() {
        _isVoiceActive.value = false
        runCatching { voiceChannel.close() }
        voiceSenderJob?.cancel()
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        inputStream = null
        outputStream = null
        _isConnected.value = false
    }

    override fun getSocket(): Socket? = socket
}
