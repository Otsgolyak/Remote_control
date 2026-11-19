package com.remote.control.data.keyboard

import com.remote.control.data.ConnectionManager
import remote.RemoteMessage
import remote.RemoteImeBatchEdit
import remote.RemoteEditInfo
import remote.RemoteImeKeyInject
import remote.RemoteAppInfo
import remote.RemoteTextFieldStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KeyboardRepositoryImpl(private val connectionManager: ConnectionManager) : KeyboardRepository {

    override suspend fun sendBatchEdit(imeCounter: Int, fieldCounter: Int, insert: Int) = withContext(Dispatchers.IO) {
        val editInfo = RemoteEditInfo.newBuilder()
            .setInsert(insert)
            .build()

        val batchEdit = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(fieldCounter)
            .setEditInfo(editInfo)
            .build()

        val message = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(batchEdit)
            .build()
        
        connectionManager.send(message.toByteArray())
    }

    override suspend fun sendKeyInject(appPackage: String, appLabel: String, counter: Int, value: String, start: Int, end: Int) = withContext(Dispatchers.IO) {
        val appInfo = RemoteAppInfo.newBuilder()
            .setAppPackage(appPackage)
            .setLabel(appLabel)
            .setCounter(counter)
            .build()

        val textFieldStatus = RemoteTextFieldStatus.newBuilder()
            .setValue(value)
            .setStart(start)
            .setEnd(end)
            .setCounterField(counter)
            .build()

        val keyInject = RemoteImeKeyInject.newBuilder()
            .setAppInfo(appInfo)
            .setTextFieldStatus(textFieldStatus)
            .build()

        val message = RemoteMessage.newBuilder()
            .setRemoteImeKeyInject(keyInject)
            .build()
        
        connectionManager.send(message.toByteArray())
    }
}
