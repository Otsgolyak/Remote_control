package com.remote.control.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun checkForUpdates(): Boolean
}

class UpdateRepositoryImpl(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
) : UpdateRepository {

    override suspend fun getLatestReleaseVersion(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Otsgolyak/Remote_control/releases/latest")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                json.optString("tag_name", null)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun downloadUpdate(url: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

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

    override suspend fun checkForUpdates(): Boolean {
        val apkFile = File(context.cacheDir, "app-debug.apk")
        val downloadUrl = "https://github.com/Otsgolyak/Remote_control/releases/latest/download/app-debug.apk"
        val downloaded = downloadUpdate(downloadUrl, apkFile)
        if (downloaded && apkFile.exists()) {
            withContext(Dispatchers.Main) {
                installUpdate(context, apkFile)
            }
            return true
        }
        return false
    }
}
