package com.remote.control.utils

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
            }
            val current = _logsFlow.value
            _logsFlow.value = if (current.length + line.length > 15000) {
                (current + line).takeLast(15000)
            } else {
                current + line
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

    fun sendFeedbackByEmail(
        context: Context,
        senderName: String,
        message: String,
        attachLogs: Boolean = true
    ) {
        log("sendFeedbackByEmail: подготовка отправки сообщения разработчику")
        try {
            val file = if (attachLogs) getLogFile() else null
            val logContent = if (attachLogs) {
                file?.takeIf { it.exists() }?.readText()?.ifBlank { _logsFlow.value.ifBlank { "Логи пусты" } }
                    ?: _logsFlow.value.ifBlank { "Логи пусты" }
            } else ""

            val subject = "Xiaomi TV Remote: Обратная связь${if (senderName.isNotBlank()) " от $senderName" else ""} (v${BuildConfig.VERSION_NAME})"

            val body = buildString {
                if (senderName.isNotBlank()) {
                    append("От кого: ").append(senderName).append("\n")
                }
                append("Версия приложения: v").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n\n")
                append("Сообщение:\n")
                append(message.trim())
                if (attachLogs && logContent.isNotBlank()) {
                    append("\n\n--- Диагностический журнал ---\n")
                    append(logContent.takeLast(4000))
                }
            }

            val mailtoUri = Uri.parse("mailto:otsgolyak@gmail.com?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
            val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(emailIntent, "Отправить сообщение разработчику").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Toast.makeText(context, "Открытие почтового приложения...", Toast.LENGTH_SHORT).show()
            context.startActivity(chooser)
            log("sendFeedbackByEmail: диалог отправки почты запущен")
        } catch (e: Exception) {
            log("sendFeedbackByEmail ошибка: ${e.message}")
            Toast.makeText(context, "Не удалось открыть почтовый клиент", Toast.LENGTH_LONG).show()
        }
    }

    fun sendLogsByEmail(context: Context, subjectPrefix: String = "Xiaomi TV Remote Logs") {
        sendFeedbackByEmail(context, senderName = "", message = "Диагностические логи приложения.", attachLogs = true)
    }
}
