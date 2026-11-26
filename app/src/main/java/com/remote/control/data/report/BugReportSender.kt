package com.remote.control.data.report

import android.os.Build
import com.remote.control.BuildConfig
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class BugReportResult {
    data class Success(val issueNumber: Int, val htmlUrl: String) : BugReportResult()
    data class Error(val message: String) : BugReportResult()
}

object BugReportSender {
    private const val GITHUB_REPO_OWNER = "Otsgolyak"
    private const val GITHUB_REPO_NAME = "Remote_control"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun sendReport(userComment: String = ""): BugReportResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.BUG_REPORT_TOKEN.trim()
        if (token.isBlank()) {
            val msg = "Токен отправки не встроен в сборку"
            Logger.log("BugReportSender: $msg")
            return@withContext BugReportResult.Error(msg)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logContent = Logger.getLogFile()?.takeIf { it.exists() }?.readText() ?: Logger.logsFlow.value

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val title = "🐛 [Bug Report] $deviceModel - v${BuildConfig.VERSION_NAME} ($timestamp)"

        val bodyBuilder = StringBuilder()
        bodyBuilder.append("### 📱 Информация об устройстве\n")
        bodyBuilder.append("- **Модель**: `$deviceModel`\n")
        bodyBuilder.append("- **ОС**: `$androidVersion`\n")
        bodyBuilder.append("- **Версия приложения**: `$appVersion`\n")
        bodyBuilder.append("- **Время отправки**: `$timestamp`\n\n")

        if (userComment.isNotBlank()) {
            bodyBuilder.append("### 💬 Комментарий пользователя\n")
            bodyBuilder.append("> $userComment\n\n")
        }

        bodyBuilder.append("### 📋 Лог приложения\n")
        bodyBuilder.append("```text\n")
        // Ограничиваем размер лога до последних 40 Кб во избежание превышения лимита GitHub API
        val trimmedLog = if (logContent.length > 40_000) logContent.takeLast(40_000) else logContent
        bodyBuilder.append(trimmedLog.ifBlank { "Логи пусты" })
        bodyBuilder.append("\n```\n")

        val json = JSONObject().apply {
            put("title", title)
            put("body", bodyBuilder.toString())
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/issues"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "RemoteControlApp/$appVersion")
            .post(requestBody)
            .build()

        try {
            Logger.log("BugReportSender: отправка логов на GitHub Issues...")
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val respJson = JSONObject(responseBody)
                    val issueNum = respJson.optInt("number", 0)
                    val htmlUrl = respJson.optString("html_url", "")
                    Logger.log("BugReportSender: репорт успешно создан -> Issue #$issueNum ($htmlUrl)")
                    BugReportResult.Success(issueNum, htmlUrl)
                } else {
                    val errMsg = "HTTP ${response.code}: $responseBody"
                    Logger.log("BugReportSender ошибка: $errMsg")
                    BugReportResult.Error("Ошибка сервера GitHub: ${response.code}")
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("BugReportSender исключение: $err")
            BugReportResult.Error("Ошибка сети: $err")
        }
    }

    suspend fun sendFeedback(
        senderName: String,
        message: String,
        attachLogs: Boolean = true
    ): BugReportResult = withContext(Dispatchers.IO) {
        val token = BuildConfig.BUG_REPORT_TOKEN.trim()
        if (token.isBlank()) {
            val msg = "Токен отправки не встроен в сборку"
            Logger.log("BugReportSender: $msg")
            return@withContext BugReportResult.Error(msg)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logContent = if (attachLogs) {
            Logger.getLogFile()?.takeIf { it.exists() }?.readText() ?: Logger.logsFlow.value
        } else ""

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val nameTag = if (senderName.isNotBlank()) " от $senderName" else ""
        val title = "💬 [Обратная связь$nameTag] $deviceModel - v${BuildConfig.VERSION_NAME} ($timestamp)"

        val bodyBuilder = StringBuilder()
        bodyBuilder.append("### 👤 Отправитель\n")
        bodyBuilder.append("- **Имя**: `${senderName.ifBlank { "Пользователь приложения" }}`\n")
        bodyBuilder.append("- **Модель**: `$deviceModel`\n")
        bodyBuilder.append("- **ОС**: `$androidVersion`\n")
        bodyBuilder.append("- **Версия приложения**: `$appVersion`\n")
        bodyBuilder.append("- **Время отправки**: `$timestamp`\n\n")

        bodyBuilder.append("### ✉️ Сообщение\n")
        bodyBuilder.append(message.trim()).append("\n\n")

        if (attachLogs && logContent.isNotBlank()) {
            bodyBuilder.append("### 📋 Лог приложения\n")
            bodyBuilder.append("```text\n")
            val trimmedLog = if (logContent.length > 40_000) logContent.takeLast(40_000) else logContent
            bodyBuilder.append(trimmedLog.ifBlank { "Логи пусты" })
            bodyBuilder.append("\n```\n")
        }

        val json = JSONObject().apply {
            put("title", title)
            put("body", bodyBuilder.toString())
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/issues"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "RemoteControlApp/$appVersion")
            .post(requestBody)
            .build()

        try {
            Logger.log("BugReportSender: отправка обращения обратной связи на GitHub Issues...")
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val respJson = JSONObject(responseBody)
                    val issueNum = respJson.optInt("number", 0)
                    val htmlUrl = respJson.optString("html_url", "")
                    Logger.log("BugReportSender: обращение успешно создано -> Issue #$issueNum ($htmlUrl)")
                    BugReportResult.Success(issueNum, htmlUrl)
                } else {
                    val errMsg = "HTTP ${response.code}: $responseBody"
                    Logger.log("BugReportSender ошибка: $errMsg")
                    BugReportResult.Error("Ошибка сервера GitHub: ${response.code}")
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Logger.log("BugReportSender исключение: $err")
            BugReportResult.Error("Ошибка сети: $err")
        }
    }
}
