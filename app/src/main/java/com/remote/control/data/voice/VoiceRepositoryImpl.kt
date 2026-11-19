package com.remote.control.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.protobuf.ByteString
import com.remote.control.data.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remote.RemoteMessage
import remote.RemoteVoiceBegin
import remote.RemoteVoiceEnd
import remote.RemoteVoicePayload

class VoiceRepositoryImpl(
    private val connectionManager: ConnectionManager
) : VoiceRepository {

    private var audioRecord: AudioRecord? = null
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(8192)

    @SuppressLint("MissingPermission")
    override suspend fun startVoice() = withContext(Dispatchers.IO) {
        connectionManager.startVoiceStream()
    }

    override suspend fun stopVoice() = withContext(Dispatchers.IO) {
        connectionManager.stopVoiceStream()
    }

    override suspend fun streamVoice() = withContext(Dispatchers.IO) {
        // Handled by connectionManager.startVoiceStream()
    }
}
