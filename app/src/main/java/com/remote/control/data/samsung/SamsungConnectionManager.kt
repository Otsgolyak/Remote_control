package com.remote.control.data.samsung

import android.content.Context
import android.util.Base64
import com.remote.control.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import remote.RemoteKeyCode
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungConnectionManager(context: Context) {
    private val prefs = context.getSharedPreferences("samsung_tv_prefs", Context.MODE_PRIVATE)
    private var webSocket: WebSocket? = null
    private var currentIp: String? = null

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
        connectInternal(ip, useSsl = true)
    }

    private fun connectInternal(ip: String, useSsl: Boolean) {
        disconnect()
        currentIp = ip
        val appNameBase64 = Base64.encodeToString("XiaomiUniversalRemote".toByteArray(), Base64.NO_WRAP)
        val savedToken = prefs.getString("token_$ip", null)
        val url = if (useSsl) {
            if (!savedToken.isNullOrBlank()) {
                "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64&token=$savedToken"
            } else {
                "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
            }
        } else {
            "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$appNameBase64"
        }

        Logger.log("Samsung Tizen: Подключение к $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("Samsung Tizen: WebSocket открыт ($ip, ssl=$useSsl)")
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
                Logger.log("Samsung Tizen ошибка соединения (ssl=$useSsl): ${t.message}")
                _isConnected.value = false
                if (useSsl) {
                    Logger.log("Samsung Tizen: Фоллбэк на порт 8001 (ws://)")
                    connectInternal(ip, useSsl = false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.log("Samsung Tizen соединение закрыто: $reason")
                _isConnected.value = false
            }
        })
    }

    fun launchApp(appTitle: String) {
        val appId = when {
            appTitle.equals("YouTube", ignoreCase = true) -> "111299001912"
            appTitle.equals("Netflix", ignoreCase = true) -> "3201907018807"
            appTitle.equals("Prime Video", ignoreCase = true) -> "3201910019365"
            appTitle.equals("Браузер", ignoreCase = true) || appTitle.equals("Browser", ignoreCase = true) -> "org.tizen.browser"
            else -> appTitle
        }

        val ip = currentIp
        if (!ip.isNullOrBlank()) {
            val httpUrl = "http://$ip:8001/api/v2/applications/$appId"
            val emptyBody = "".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(httpUrl)
                .post(emptyBody)
                .build()
            Logger.log("Samsung Tizen HTTP запуск приложения: $appTitle ($appId) -> $httpUrl")
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Logger.log("Samsung Tizen HTTP launchApp ошибка: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        Logger.log("Samsung Tizen HTTP launchApp ответ: ${it.code}")
                    }
                }
            })
        }

        val ws = webSocket
        if (ws != null) {
            val payload = JSONObject().apply {
                put("method", "ms.channel.emit")
                put("params", JSONObject().apply {
                    put("event", "ed.apps.launch")
                    put("to", "host")
                    put("data", JSONObject().apply {
                        put("appId", appId)
                        put("action_type", "DEEP_LINK")
                    })
                })
            }
            Logger.log("Samsung Tizen WS запуск приложения: $appTitle ($appId)")
            ws.send(payload.toString())
        }
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
            RemoteKeyCode.KEYCODE_POWER, RemoteKeyCode.KEYCODE_TV_POWER, RemoteKeyCode.KEYCODE_SLEEP, RemoteKeyCode.KEYCODE_SOFT_SLEEP -> "KEY_POWER"
            RemoteKeyCode.KEYCODE_DPAD_UP, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_UP -> "KEY_UP"
            RemoteKeyCode.KEYCODE_DPAD_DOWN, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_DOWN -> "KEY_DOWN"
            RemoteKeyCode.KEYCODE_DPAD_LEFT, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_LEFT -> "KEY_LEFT"
            RemoteKeyCode.KEYCODE_DPAD_RIGHT, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> "KEY_RIGHT"
            RemoteKeyCode.KEYCODE_DPAD_CENTER, RemoteKeyCode.KEYCODE_ENTER, RemoteKeyCode.KEYCODE_NUMPAD_ENTER -> "KEY_ENTER"
            RemoteKeyCode.KEYCODE_BACK -> "KEY_RETURN"
            RemoteKeyCode.KEYCODE_ESCAPE -> "KEY_EXIT"
            RemoteKeyCode.KEYCODE_HOME, RemoteKeyCode.KEYCODE_ALL_APPS -> "KEY_HOME"
            RemoteKeyCode.KEYCODE_VOLUME_UP -> "KEY_VOLUP"
            RemoteKeyCode.KEYCODE_VOLUME_DOWN -> "KEY_VOLDOWN"
            RemoteKeyCode.KEYCODE_VOLUME_MUTE, RemoteKeyCode.KEYCODE_MUTE -> "KEY_MUTE"
            RemoteKeyCode.KEYCODE_CHANNEL_UP, RemoteKeyCode.KEYCODE_PAGE_UP -> "KEY_CHUP"
            RemoteKeyCode.KEYCODE_CHANNEL_DOWN, RemoteKeyCode.KEYCODE_PAGE_DOWN -> "KEY_CHDOWN"
            RemoteKeyCode.KEYCODE_LAST_CHANNEL -> "KEY_PRECH"
            RemoteKeyCode.KEYCODE_TV_INPUT -> "KEY_SOURCE"
            RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_1 -> "KEY_HDMI1"
            RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_2 -> "KEY_HDMI2"
            RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_3 -> "KEY_HDMI3"
            RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_4 -> "KEY_HDMI4"
            RemoteKeyCode.KEYCODE_SETTINGS, RemoteKeyCode.KEYCODE_MENU -> "KEY_MENU"
            RemoteKeyCode.KEYCODE_INFO -> "KEY_INFO"
            RemoteKeyCode.KEYCODE_GUIDE -> "KEY_GUIDE"
            RemoteKeyCode.KEYCODE_TV -> "KEY_TV"
            RemoteKeyCode.KEYCODE_CAPTIONS -> "KEY_CAPTION"
            RemoteKeyCode.KEYCODE_TV_TELETEXT -> "KEY_TTX_MIX"
            RemoteKeyCode.KEYCODE_MEDIA_PLAY -> "KEY_PLAY"
            RemoteKeyCode.KEYCODE_MEDIA_PAUSE -> "KEY_PAUSE"
            RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE -> "KEY_PLAY_BACK"
            RemoteKeyCode.KEYCODE_MEDIA_STOP -> "KEY_STOP"
            RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD, RemoteKeyCode.KEYCODE_MEDIA_NEXT, RemoteKeyCode.KEYCODE_MEDIA_SKIP_FORWARD -> "KEY_FF"
            RemoteKeyCode.KEYCODE_MEDIA_REWIND, RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS, RemoteKeyCode.KEYCODE_MEDIA_SKIP_BACKWARD -> "KEY_REWIND"
            RemoteKeyCode.KEYCODE_MEDIA_RECORD -> "KEY_REC"
            RemoteKeyCode.KEYCODE_PROG_RED -> "KEY_RED"
            RemoteKeyCode.KEYCODE_PROG_GREEN -> "KEY_GREEN"
            RemoteKeyCode.KEYCODE_PROG_YELLOW -> "KEY_YELLOW"
            RemoteKeyCode.KEYCODE_PROG_BLUE -> "KEY_CYAN"
            RemoteKeyCode.KEYCODE_0, RemoteKeyCode.KEYCODE_NUMPAD_0 -> "KEY_0"
            RemoteKeyCode.KEYCODE_1, RemoteKeyCode.KEYCODE_NUMPAD_1 -> "KEY_1"
            RemoteKeyCode.KEYCODE_2, RemoteKeyCode.KEYCODE_NUMPAD_2 -> "KEY_2"
            RemoteKeyCode.KEYCODE_3, RemoteKeyCode.KEYCODE_NUMPAD_3 -> "KEY_3"
            RemoteKeyCode.KEYCODE_4, RemoteKeyCode.KEYCODE_NUMPAD_4 -> "KEY_4"
            RemoteKeyCode.KEYCODE_5, RemoteKeyCode.KEYCODE_NUMPAD_5 -> "KEY_5"
            RemoteKeyCode.KEYCODE_6, RemoteKeyCode.KEYCODE_NUMPAD_6 -> "KEY_6"
            RemoteKeyCode.KEYCODE_7, RemoteKeyCode.KEYCODE_NUMPAD_7 -> "KEY_7"
            RemoteKeyCode.KEYCODE_8, RemoteKeyCode.KEYCODE_NUMPAD_8 -> "KEY_8"
            RemoteKeyCode.KEYCODE_9, RemoteKeyCode.KEYCODE_NUMPAD_9 -> "KEY_9"
            RemoteKeyCode.KEYCODE_DEL -> "KEY_BACKSPACE"
            RemoteKeyCode.KEYCODE_SEARCH, RemoteKeyCode.KEYCODE_VOICE_ASSIST -> "KEY_SEARCH"
            else -> null
        }
    }

    fun disconnect() {
        runCatching { webSocket?.close(1000, "Disconnect") }
        webSocket = null
        _isConnected.value = false
    }
}
