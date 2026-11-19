package com.remote.control.data.voice

interface VoiceRepository {
    suspend fun startVoice()
    suspend fun stopVoice()
    suspend fun streamVoice()
}
