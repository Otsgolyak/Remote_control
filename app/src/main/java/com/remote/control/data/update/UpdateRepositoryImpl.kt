package com.remote.control.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.remote.control.BuildConfig
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class UpdateRepositoryImpl(private val context: Context) {

    fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://github.com/Otsgolyak/Remote_control/releases/latest/download/app-debug.apk"
            val file = File(context.cacheDir, "app-debug.apk")
            if (file.exists()) file.delete()
            
            Logger.log("Скачивание обновления APK...")
            val connection = URL(url).openConnection()
            connection.getInputStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            
            Logger.log("APK успешно скачан (${file.length()} байт)")
            installApk(file)
            true
        } catch (e: Exception) {
            Logger.log("Ошибка обновления: ${e.message}")
            false
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun cleanupOldApks(context: Context) {
        val file = File(context.cacheDir, "app-debug.apk")
        if (file.exists()) {
            val deleted = file.delete()
            Logger.log("Автоочистка старого APK: deleted=$deleted")
        }
    }
}
