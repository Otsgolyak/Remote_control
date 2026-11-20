package com.remote.control.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CursorAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        thread {
            try {
                val server = ServerSocket(6666)
                while (true) {
                    val client = server.accept()
                    handleConnection(client)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                    "MOVE" -> { /* Update internal state */ }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
        }
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
