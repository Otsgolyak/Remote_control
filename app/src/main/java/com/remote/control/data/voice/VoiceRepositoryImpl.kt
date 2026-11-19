package com.remote.control.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.protobuf.ByteString
import com.remote.control.data.ConnectionManager
import remote.RemoteMessage
import remote.RemoteVoiceBegin
import remote.RemoteVoiceEnd
import remote.RemoteVoicePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceRepositoryImpl(
    private val connectionManager: ConnectionManager
) : VoiceRepository {

    private var audioRecord: AudioRecord? = null
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    override suspend fun startVoice() = withContext(Dispatchers.IO) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        audioRecord?.startRecording()
        
        val message = RemoteMessage.newBuilder()
            .setRemoteVoiceBegin(RemoteVoiceBegin.getDefaultInstance())
            .build()
        connectionManager.send(message.toByteArray())
    }

    override suspend fun stopVoice() = withContext(Dispatchers.IO) {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        val message = RemoteMessage.newBuilder()
            .setRemoteVoiceEnd(RemoteVoiceEnd.getDefaultInstance())
            .build()
        connectionManager.send(message.toByteArray())
    }

    override suspend fun streamVoice() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(bufferSize)
        while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                val payload = RemoteVoicePayload.newBuilder()
                    .setAudioData(ByteString.copyFrom(buffer, 0, read))
                    .build()
                val message = RemoteMessage.newBuilder()
                    .setRemoteVoicePayload(payload)
                    .build()
                connectionManager.send(message.toByteArray())
            }
        }
    }
}
