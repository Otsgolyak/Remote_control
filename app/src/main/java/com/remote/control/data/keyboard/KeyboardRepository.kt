package com.remote.control.data.keyboard

interface KeyboardRepository {
    suspend fun sendBatchEdit(imeCounter: Int, fieldCounter: Int, insert: Int)
    suspend fun sendKeyInject(appPackage: String, appLabel: String, counter: Int, value: String, start: Int, end: Int)
}
