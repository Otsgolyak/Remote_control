package com.remote.control.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

interface UpdateRepository {
    fun canInstall(): Boolean
    fun requestInstallPermission(ctx: Context)
    suspend fun getLatestReleaseVersion(): String?
    suspend fun downloadUpdate(url: String, outputFile: File): Boolean
    fun installUpdate(context: Context, apkFile: File)
    suspend fun checkForUpdates(): Boolean
    fun cleanupOldApks(context: Context)
}

class UpdateRepositoryImpl(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : UpdateRepository {

    override fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Logger.log("Открытие настроек разрешения REQUEST_INSTALL_PACKAGES")
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(permIntent)
        }
    }

    override fun cleanupOldApks(context: Context) {
        runCatching {
            val cacheApk = File(context.cacheDir, "app-debug.apk")
            if (cacheApk.exists()) {
                val deleted = cacheApk.delete()
                Logger.log("Автоочистка старого APK после установки (${cacheApk.name}): deleted=$deleted")
            }
            context.externalCacheDir?.let { extDir ->
                val extApk = File(extDir, "app-debug.apk")
                if (extApk.exists()) {
                    extApk.delete()
                }
            }
        }
    }

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
            Logger.log("Ошибка проверки версии на GitHub: ${e.message}")
            null
        }
    }

    override suspend fun downloadUpdate(url: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        Logger.log("Скачивание обновления APK: $url")
        val request = Request.Builder().url(url).build()
        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("Ошибка скачивания APK: HTTP ${response.code}")
                    return@withContext false
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Logger.log("APK успешно скачан (${outputFile.length()} байт)")
                true
            }
        } catch (e: IOException) {
            Logger.log("Ошибка записи APK при скачивании: ${e.message}")
            false
        }
    }

    override fun installUpdate(context: Context, apkFile: File) {
        try {
            if (!canInstall()) {
                requestInstallPermission(context)
                return
            }

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
            Logger.log("Запуск системного установщика для ${apkFile.name}")
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.log("Ошибка запуска установщика APK: ${e.message}")
        }
    }

    override suspend fun checkForUpdates(): Boolean {
        val apkFile = File(context.cacheDir, "app-debug.apk")
        val downloadUrl = "https://github.com/Otsgolyak/Remote_control/releases/latest/download/app-debug.apk"
        val downloaded = downloadUpdate(downloadUrl, apkFile)
        if (downloaded && apkFile.exists() && apkFile.length() > 100_000) {
            withContext(Dispatchers.Main) {
                installUpdate(context, apkFile)
            }
            return true
        }
        return false
    }
}
