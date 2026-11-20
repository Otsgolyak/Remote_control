package com.remote.control.data.samsung

import android.content.Context
import android.util.Base64
import com.remote.control.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import remote.RemoteKeyCode
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungConnectionManager(context: Context) {
    private val prefs = context.getSharedPreferences("samsung_tv_prefs", Context.MODE_PRIVATE)
    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun connect(ip: String) {
        disconnect()
        val appNameBase64 = Base64.encodeToString("XiaomiUniversalRemote".toByteArray(), Base64.NO_WRAP)
        val savedToken = prefs.getString("token_$ip", null)
        val url = if (!savedToken.isNullOrBlank()) {
            "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64&token=$savedToken"
        } else {
            "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
        }

        Logger.log("Samsung Tizen: Подключение к $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("Samsung Tizen: WebSocket открыт ($ip)")
                _isConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    Logger.log("Samsung Tizen Event: $event")
                    if (event == "ms.channel.connect") {
                        _isConnected.value = true
                        val data = json.optJSONObject("data")
                        val token = data?.optString("token")
                        if (!token.isNullOrBlank()) {
                            prefs.edit().putString("token_$ip", token).apply()
                            Logger.log("Samsung Tizen: Токен успешно сохранен ($token)")
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("Samsung Tizen JSON error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("Samsung Tizen ошибка соединения: ${t.message}")
                _isConnected.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.log("Samsung Tizen соединение закрыто: $reason")
                _isConnected.value = false
            }
        })
    }

    fun sendKey(keyCode: RemoteKeyCode) {
        val samsungKey = mapToSamsungKey(keyCode) ?: return
        sendSamsungKey(samsungKey)
    }

    fun sendSamsungKey(samsungKey: String) {
        val ws = webSocket ?: return
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", samsungKey)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        Logger.log("Samsung Tizen отправка: $samsungKey")
        ws.send(payload.toString())
    }

    fun sendText(text: String) {
        val ws = webSocket ?: return
        val base64Text = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", base64Text)
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            })
        }
        Logger.log("Samsung Tizen отправка текста: \"$text\"")
        ws.send(payload.toString())
    }

    private fun mapToSamsungKey(keyCode: RemoteKeyCode): String? {
        return when (keyCode) {
            RemoteKeyCode.KEYCODE_POWER -> "KEY_POWER"
            RemoteKeyCode.KEYCODE_DPAD_UP -> "KEY_UP"
            RemoteKeyCode.KEYCODE_DPAD_DOWN -> "KEY_DOWN"
            RemoteKeyCode.KEYCODE_DPAD_LEFT -> "KEY_LEFT"
            RemoteKeyCode.KEYCODE_DPAD_RIGHT -> "KEY_RIGHT"
            RemoteKeyCode.KEYCODE_DPAD_CENTER, RemoteKeyCode.KEYCODE_ENTER -> "KEY_ENTER"
            RemoteKeyCode.KEYCODE_BACK -> "KEY_RETURN"
            RemoteKeyCode.KEYCODE_HOME -> "KEY_HOME"
            RemoteKeyCode.KEYCODE_VOLUME_UP -> "KEY_VOLUP"
            RemoteKeyCode.KEYCODE_VOLUME_DOWN -> "KEY_VOLDOWN"
            RemoteKeyCode.KEYCODE_VOLUME_MUTE -> "KEY_MUTE"
            RemoteKeyCode.KEYCODE_TV_INPUT -> "KEY_SOURCE"
            RemoteKeyCode.KEYCODE_SETTINGS, RemoteKeyCode.KEYCODE_MENU -> "KEY_MENU"
            else -> null
        }
    }

    fun disconnect() {
        runCatching { webSocket?.close(1000, "Disconnect") }
        webSocket = null
        _isConnected.value = false
    }
}
