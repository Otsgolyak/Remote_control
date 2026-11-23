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

    fun sendLogsByEmail(context: Context, subjectPrefix: String = "Xiaomi TV Remote Logs") {
        log("sendLogsByEmail: подготовка отправки отчета на otsgolyak@gmail.com")
        try {
            val file = getLogFile()
            val logContent = file?.takeIf { it.exists() }?.readText()?.ifBlank { _logsFlow.value.ifBlank { "Логи пусты" } }
                ?: _logsFlow.value.ifBlank { "Логи пусты" }

            val subject = "$subjectPrefix (v${BuildConfig.VERSION_NAME})"

            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:otsgolyak@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, "Отчет логов приложения Xiaomi TV Remote (v${BuildConfig.VERSION_NAME}):\n\n$logContent")
                if (file != null && file.exists()) {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (fe: Exception) {
                        log("FileProvider getUriForFile предупреждение: ${fe.message}")
                    }
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(emailIntent, "Отправить логи на otsgolyak@gmail.com").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Toast.makeText(context, "Открытие почтового приложения...", Toast.LENGTH_SHORT).show()
            context.startActivity(chooser)
            log("sendLogsByEmail: диалог отправки почты запущен")
        } catch (e: Exception) {
            log("sendLogsByEmail ошибка primary: ${e.message}, пробуем fallback mailto...")
            try {
                val body = Uri.encode(_logsFlow.value.takeLast(4000))
                val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:otsgolyak@gmail.com?subject=Remote%20Logs&body=$body")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mailto)
                log("sendLogsByEmail: Fallback mailto успешно запущен")
            } catch (e2: Exception) {
                log("sendLogsByEmail: не удалось открыть почтовый клиент: ${e2.message}")
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("RemoteLogs", _logsFlow.value))
                    Toast.makeText(context, "Почтовый клиент не найден. Логи скопированы в буфер обмена.", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }
}
