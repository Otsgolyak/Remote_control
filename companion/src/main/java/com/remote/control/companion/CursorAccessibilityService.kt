package com.remote.control.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CursorAccessibilityService : AccessibilityService() {

    private var cursorView: ImageView? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Создаем курсор
        cursorView = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_online) // Простая точка
            layoutParams = WindowManager.LayoutParams(
                50, 50,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        }
        windowManager?.addView(cursorView, cursorView?.layoutParams)

        // Запуск сервера команд
        thread {
            try {
                val server = ServerSocket(6666)
                while (true) {
                    val client = server.accept()
                    handleConnection(client)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleConnection(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split(":")
                if (parts.size < 3) continue
                
                val cmd = parts[0]
                val x = parts[1].toFloatOrNull() ?: 0f
                val y = parts[2].toFloatOrNull() ?: 0f
                
                when(cmd) {
                    "CLICK" -> dispatchClick(x, y)
                    "MOVE" -> moveCursor(x, y)
                }
            }
        } catch (e: Exception) { e.printStackTrace() } finally { socket.close() }
    }

    private fun moveCursor(x: Float, y: Float) {
        val params = cursorView?.layoutParams as? WindowManager.LayoutParams
        params?.x = x.toInt()
        params?.y = y.toInt()
        windowManager?.updateViewLayout(cursorView, params)
    }

    private fun dispatchClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
