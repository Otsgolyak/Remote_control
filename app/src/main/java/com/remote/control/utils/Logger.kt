package com.remote.control.utils

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object Logger {
    private const val FILE_NAME = "logs.txt"
    var appContext: Context? = null

    fun log(message: String) {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            FileWriter(file, true).use { writer ->
                writer.append("$timestamp: $message\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
