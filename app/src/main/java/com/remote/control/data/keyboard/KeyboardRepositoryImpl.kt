package com.remote.control.data.keyboard

import com.remote.control.data.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remote.RemoteAppInfo
import remote.RemoteEditInfo
import remote.RemoteImeBatchEdit
import remote.RemoteImeKeyInject
import remote.RemoteImeObject
import remote.RemoteMessage
import remote.RemoteTextFieldStatus

class KeyboardRepositoryImpl(private val connectionManager: ConnectionManager) : KeyboardRepository {

    override suspend fun sendBatchEdit(imeCounter: Int, fieldCounter: Int, insert: Int) = withContext(Dispatchers.IO) {
        val editInfo = RemoteEditInfo.newBuilder()
            .setInsert(insert)
            .build()

        val batchEdit = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(imeCounter)
            .setFieldCounter(fieldCounter)
            .addEditInfo(editInfo)
            .build()

        val message = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(batchEdit)
            .build()

        connectionManager.sendMessage(message)
    }

    override suspend fun sendKeyInject(appPackage: String, appLabel: String, counter: Int, value: String, start: Int, end: Int) = withContext(Dispatchers.IO) {
        val imeObject = RemoteImeObject.newBuilder()
            .setStart(start)
            .setEnd(end)
            .setValue(value)
            .build()

        val editInfo = RemoteEditInfo.newBuilder()
            .setInsert(1)
            .setTextFieldStatus(imeObject)
            .build()

        val batchEdit = RemoteImeBatchEdit.newBuilder()
            .setImeCounter(counter)
            .setFieldCounter(counter)
            .addEditInfo(editInfo)
            .build()

        val message = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(batchEdit)
            .build()

        connectionManager.sendMessage(message)
    }
}
