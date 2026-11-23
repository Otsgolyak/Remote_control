package com.remote.control.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.remote.control.BuildConfig
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val percent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isDownloading: Boolean = false,
    val error: String? = null
) {
    val downloadedMb: String get() = "%.1f".format(downloadedBytes / (1024f * 1024f))
    val totalMb: String get() = if (totalBytes > 0) "%.1f".format(totalBytes / (1024f * 1024f)) else "—"
}

interface UpdateRepository {
    val downloadProgress: StateFlow<DownloadProgress>
    fun canInstall(): Boolean
    fun requestInstallPermission(ctx: Context)
    suspend fun getLatestReleaseVersion(): String?
    suspend fun checkForNewVersion(): String?
    suspend fun downloadUpdate(url: String, outputFile: File): Boolean
    fun installUpdate(context: Context, apkFile: File)
    suspend fun checkForUpdates(): Boolean
    fun cleanupOldApks(context: Context)
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean
}

class UpdateRepositoryImpl(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : UpdateRepository {

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    override val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    override fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Logger.log("Открытие настроек разрешения REQUEST_INSTALL_PACKAGES для ${ctx.packageName}")
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startActivity(permIntent)
            } catch (e: Exception) {
                Logger.log("Ошибка открытия настроек MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
            }
        }
    }

    override fun cleanupOldApks(context: Context) {
        runCatching {
            val dir = context.externalCacheDir ?: context.cacheDir
            val oldApk = File(dir, "app-update.apk")
            if (oldApk.exists()) {
                val deleted = oldApk.delete()
                Logger.log("cleanupOldApks: удален старый APK (${oldApk.name}) -> $deleted")
            }
            val debugApk = File(context.cacheDir, "app-debug.apk")
            if (debugApk.exists()) {
                debugApk.delete()
            }
        }
    }

    override fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val cleanRemote = remoteTag.trimStart('v', 'V').trim()
        val cleanLocal = localVersion.trimStart('v', 'V').trim()
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    override suspend fun getLatestReleaseVersion(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/Otsgolyak/Remote_control/releases/latest")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("getLatestReleaseVersion: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tag = json.optString("tag_name", null)
                Logger.log("getLatestReleaseVersion: получен тег последнего релиза: $tag")
                tag
            }
        } catch (e: Exception) {
            Logger.log("getLatestReleaseVersion ошибка: ${e.message}")
            null
        }
    }

    override suspend fun checkForNewVersion(): String? {
        val remoteTag = getLatestReleaseVersion() ?: return null
        val currentVersion = BuildConfig.VERSION_NAME
        val isNewer = isNewerVersion(remoteTag, currentVersion)
        Logger.log("Проверка обновления: текущая v$currentVersion, на сервере $remoteTag -> доступно=$isNewer")
        return if (isNewer) remoteTag else null
    }

    override suspend fun downloadUpdate(url: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        Logger.log("Скачивание обновления APK: $url в ${outputFile.absolutePath}")
        _downloadProgress.value = DownloadProgress(isDownloading = true)
        val request = Request.Builder().url(url).build()

        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("Ошибка скачивания APK: HTTP ${response.code}")
                    _downloadProgress.value = DownloadProgress(isDownloading = false, error = "HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body ?: run {
                    _downloadProgress.value = DownloadProgress(isDownloading = false, error = "Пустое тело ответа")
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        var lastReportTime = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 200 || downloadedBytes == totalBytes) {
                                lastReportTime = now
                                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                _downloadProgress.value = DownloadProgress(
                                    percent = percent,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    isDownloading = true
                                )
                            }
                        }
                    }
                }

                _downloadProgress.value = DownloadProgress(
                    percent = 100,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    isDownloading = false
                )
                Logger.log("APK успешно скачан (${outputFile.length()} байт)")
                true
            }
        } catch (e: IOException) {
            Logger.log("Ошибка скачивания/записи APK: ${e.message}")
            _downloadProgress.value = DownloadProgress(isDownloading = false, error = e.message)
            false
        }
    }

    override fun installUpdate(context: Context, apkFile: File) {
        try {
            if (!canInstall()) {
                Logger.log("installUpdate: нет разрешения на установку, запрос у пользователя")
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
            Logger.log("Запуск системного установщика для ${apkFile.name} (URI: $uri)")
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.log("Ошибка запуска установщика APK: ${e.message}")
        }
    }

    override suspend fun checkForUpdates(): Boolean {
        cleanupOldApks(context)
        val targetDir = context.externalCacheDir ?: context.cacheDir
        val apkFile = File(targetDir, "app-update.apk")
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
