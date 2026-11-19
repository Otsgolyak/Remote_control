package com.remote.control.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.google.protobuf.ByteString
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import remote.*
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

    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0
    @Volatile private var activeVoiceSessionId: Int? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _isVoiceActive = MutableStateFlow(false)
    override val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

    override suspend fun connect(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            disconnect()
            try {
                val sslContext = pairingRepository.createSslContext()
                val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
                rawSocket.connect(InetSocketAddress(ip, port), 10000)
                rawSocket.startHandshake()
                socket = rawSocket
                inputStream = rawSocket.inputStream
                outputStream = rawSocket.outputStream
                _isConnected.value = true
                startReaderLoop()
            } catch (e: Exception) { _isConnected.value = false; throw e }
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
                        msg.hasRemoteConfigure() -> sendMessage(RemoteMessage.newBuilder().setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(622).setDeviceInfo(RemoteDeviceInfo.newBuilder().setModel(Build.MODEL).setVendor(Build.MANUFACTURER).setPackageName("com.remote.control").setAppVersion("1.0.0").build()).build()).build())
                        msg.hasRemoteSetActive() -> sendMessage(RemoteMessage.newBuilder().setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622).build()).build())
                        msg.hasRemotePingRequest() -> sendMessage(RemoteMessage.newBuilder().setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1).build()).build())
                        msg.hasRemoteImeShowRequest() -> fieldCounter = msg.remoteImeShowRequest.remoteTextFieldStatus.counterField
                        msg.hasRemoteImeKeyInject() -> {
                            fieldCounter = msg.remoteImeKeyInject.textFieldStatus.counterField
                            imeCounter = msg.remoteImeKeyInject.appInfo.counter
                        }
                        msg.hasRemoteVoiceBegin() -> activeVoiceSessionId = msg.remoteVoiceBegin.sessionId
                    }
                }
            } catch (e: Exception) { disconnect() }
        }
    }

    override suspend fun sendMessage(message: RemoteMessage) {
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val out = outputStream ?: return@withContext
                    message.writeDelimitedTo(out)
                    out.flush()
                } catch (e: Exception) { disconnect() }
            }
        }
    }

    override suspend fun sendKey(keyCode: RemoteKeyCode) {
        sendMessage(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(RemoteDirection.SHORT).build()).build())
    }

    override suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        // 1. Активация поля
        sendMessage(RemoteMessage.newBuilder().setRemoteImeShowRequest(RemoteImeShowRequest.newBuilder().setRemoteTextFieldStatus(RemoteTextFieldStatus.newBuilder().setCounterField(fieldCounter).setValue(text).build()).build()).build())
        delay(50)
        // 2. Вставка
        val imeObject = RemoteImeObject.newBuilder().setStart(text.length).setEnd(text.length).setValue(text).build()
        val editInfo = RemoteEditInfo.newBuilder().setInsert(1).setTextFieldStatus(imeObject).build()
        val batchEdit = RemoteImeBatchEdit.newBuilder().setImeCounter(imeCounter).setFieldCounter(fieldCounter).addEditInfo(editInfo).build()
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
        Logger.log("Запуск голосового ввода (KEYCODE_SEARCH)...")
        sendKey(RemoteKeyCode.KEYCODE_SEARCH)
        delay(800) // Ждем ответа ТВ
        
        val sid = activeVoiceSessionId ?: 1
        _isVoiceActive.value = true
        
        voiceJob?.cancel()
        voiceJob = ioScope.launch(Dispatchers.IO) {
            try {
                // PCM 16kHz
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 16384)
                recorder.startRecording()
                val buffer = ByteArray(8192)
                
                while (_isVoiceActive.value && isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        sendMessage(
                            RemoteMessage.newBuilder()
                                .setRemoteVoicePayload(
                                    RemoteVoicePayload.newBuilder()
                                        .setSessionId(sid)
                                        .setSamples(ByteString.copyFrom(buffer, 0, read))
                                        .build()
                                )
                                .build()
                        )
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Logger.log("Ошибка аудио: ${e.message}")
            } finally {
                _isVoiceActive.value = false
            }
        }
    }

    override suspend fun stopVoiceStream() {
        _isVoiceActive.value = false
        voiceJob?.cancel()
        val sid = activeVoiceSessionId ?: 1
        sendMessage(RemoteMessage.newBuilder().setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(sid).build()).build())
    }

    override suspend fun disconnect() {
        _isVoiceActive.value = false
        voiceJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        _isConnected.value = false
    }
    override fun getSocket(): Socket? = socket
}
