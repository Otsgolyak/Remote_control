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
import kotlin.concurrent.thread

class ConnectionManagerImpl(
    private val pairingRepository: PairingRepository
) : ConnectionManager {

    private var socket: SSLSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var voiceThread: Thread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0
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
            try {
                val sslContext = pairingRepository.createSslContext()
                val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
                rawSocket.connect(InetSocketAddress(ip, port), 10000)
                rawSocket.startHandshake()
                socket = rawSocket
                inputStream = rawSocket.inputStream
                outputStream = rawSocket.outputStream
                _isConnected.value = true
                _lastError.value = null
                startReaderLoop()
            } catch (e: Exception) { _isConnected.value = false; _lastError.value = e.message; throw e }
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
                } catch (e: Exception) { _lastError.value = e.message; disconnect() }
            }
        }
    }

    override suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                sendMessage(RemoteMessage.parseFrom(data))
            } catch (e: Exception) { _lastError.value = e.message }
        }
    }

    override suspend fun sendKey(keyCode: RemoteKeyCode) {
        sendMessage(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(RemoteDirection.SHORT).build()).build())
    }

    override suspend fun sendText(text: String) {
        // 1. Activate
        sendMessage(RemoteMessage.newBuilder().setRemoteImeShowRequest(RemoteImeShowRequest.newBuilder().setRemoteTextFieldStatus(RemoteTextFieldStatus.newBuilder().setCounterField(fieldCounter).setValue(text).build()).build()).build())
        delay(100)
        // 2. Insert
        val batchEdit = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(fieldCounter)
            .addEditInfo(RemoteEditInfo.newBuilder().setInsert(1).setTextFieldStatus(RemoteImeObject.newBuilder().setStart(text.length).setEnd(text.length).setValue(text).build()).build())
            .build()
        sendMessage(RemoteMessage.newBuilder().setRemoteImeBatchEdit(batchEdit).build())
    }

    override suspend fun sendCharKey(ch: Char) {}

    @SuppressLint("MissingPermission")
    override suspend fun startVoiceStream() {
        if (_isVoiceActive.value) return
        sendKey(RemoteKeyCode.KEYCODE_SEARCH)
        delay(800)
        
        _isVoiceActive.value = true
        val sid = activeVoiceSessionId ?: 1
        sendMessage(RemoteMessage.newBuilder().setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(sid).build()).build())

        voiceThread = thread(start = true, name = "VoiceStreamThread") {
            try {
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 16384)
                recorder.startRecording()
                val buffer = ByteArray(8192)
                while (_isVoiceActive.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        runBlocking {
                             sendMessage(RemoteMessage.newBuilder().setRemoteVoicePayload(RemoteVoicePayload.newBuilder().setSessionId(sid).setSamples(ByteString.copyFrom(buffer, 0, read)).build()).build())
                        }
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) { Logger.log("Голос поток упал: ${e.message}") }
        }
    }

    override suspend fun stopVoiceStream() {
        _isVoiceActive.value = false
        voiceThread?.interrupt()
        voiceThread = null
        val sid = activeVoiceSessionId ?: 1
        sendMessage(RemoteMessage.newBuilder().setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(sid).build()).build())
    }

    override suspend fun disconnect() {
        _isVoiceActive.value = false
        voiceThread?.interrupt()
        runCatching { socket?.close() }
        socket = null
        _isConnected.value = false
    }
    
    override fun getSocket(): Socket? = socket
}
