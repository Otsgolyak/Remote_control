package com.remote.control.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

interface UpdateRepository {
    suspend fun getLatestReleaseVersion(): String?
    suspend fun downloadUpdate(url: String, outputFile: File): Boolean
    fun installUpdate(context: Context, apkFile: File)
}

class UpdateRepositoryImpl(private val client: OkHttpClient) : UpdateRepository {
    override suspend fun getLatestReleaseVersion(): String? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Otsgolyak/Remote_control/releases/latest")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                json.getString("tag_name")
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun downloadUpdate(url: String, outputFile: File): Boolean {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    override fun installUpdate(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
