package com.remote.control.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.remote.control.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object Logger {
    private const val FILE_NAME = "logs.txt"
    var appContext: Context? = null

    private val _logsFlow = MutableStateFlow("")
    val logsFlow: StateFlow<String> = _logsFlow.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val file = getLogFile()
        if (file != null && !file.exists()) {
            file.createNewFile()
        }
        if (file != null && file.exists()) {
            _logsFlow.value = runCatching { file.readText() }.getOrDefault("")
        }
        log("=== Запуск Xiaomi TV Remote v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
        log("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
    }

    fun getLogFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "$timestamp: $message\n"
        try {
            val file = getLogFile()
            if (file != null) {
                FileWriter(file, true).use { writer ->
                    writer.append(line)
                }
                val text = file.readText()
                _logsFlow.value = if (text.length > 15000) text.takeLast(15000) else text
            } else {
                _logsFlow.value = (_logsFlow.value + line).takeLast(15000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearLogs() {
        try {
            getLogFile()?.writeText("")
            _logsFlow.value = ""
            log("Логи очищены")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendLogsByEmail(context: Context, subjectPrefix: String = "Xiaomi TV Remote Logs") {
        try {
            val file = getLogFile()
            if (file != null && !file.exists()) {
                log("Создание файла логов перед отправкой")
            }
            val logContent = file?.takeIf { it.exists() }?.readText()?.ifBlank { "Логи пусты" }
                ?: _logsFlow.value.ifBlank { "Логи пусты" }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("otsgolyak@gmail.com"))
                putExtra(Intent.EXTRA_SUBJECT, "$subjectPrefix (v${BuildConfig.VERSION_NAME})")
                putExtra(Intent.EXTRA_TEXT, "Автоматический отчет логов:\n\n$logContent")
                if (file != null && file.exists()) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Отправить логи на otsgolyak@gmail.com").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Fallback to mailto: if rfc822 chooser fails
            try {
                val body = Uri.encode(_logsFlow.value.takeLast(4000))
                val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:otsgolyak@gmail.com?subject=Remote%20Logs&body=$body")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mailto)
            } catch (_: Exception) {}
        }
    }
}
