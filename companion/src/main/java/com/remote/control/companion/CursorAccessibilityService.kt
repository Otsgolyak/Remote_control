package com.remote.control.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CursorAccessibilityService : AccessibilityService() {

    private var cursorView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cursorX = 960f
    @Volatile private var cursorY = 540f
    private var screenWidth = 1920f
    private var screenHeight = 1080f

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = resources.displayMetrics
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            screenWidth = metrics.widthPixels.toFloat()
            screenHeight = metrics.heightPixels.toFloat()
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
        }

        mainHandler.post {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF6D00"))
                    setStroke(4, Color.WHITE)
                }
            }
            val params = WindowManager.LayoutParams(
                36,
                36,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = cursorX.toInt()
                y = cursorY.toInt()
            }
            runCatching {
                wm.addView(dot, params)
                cursorView = dot
            }
        }

        thread(start = true, name = "TvCompanionSocketServer") {
            try {
                val server = ServerSocket(6666)
                while (true) {
                    val client = server.accept()
                    thread { handleConnection(client) }
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
                val cmd = parts[0]
                when (cmd) {
                    "MOVE" -> {
                        if (parts.size >= 3) {
                            val dx = parts[1].toFloatOrNull() ?: 0f
                            val dy = parts[2].toFloatOrNull() ?: 0f
                            moveCursorDelta(dx, dy)
                        }
                    }
                    "CLICK" -> {
                        dispatchClick(cursorX, cursorY)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun moveCursorDelta(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth - 36f)
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight - 36f)
        mainHandler.post {
            val view = cursorView ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.x = cursorX.toInt()
            params.y = cursorY.toInt()
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun dispatchClick(x: Float, y: Float) {
        mainHandler.post {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            runCatching { dispatchGesture(gesture, null, null) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
