package com.remote.control.data.lg

import android.content.Context
import com.remote.control.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import remote.RemoteKeyCode
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

class LgWebOsConnectionManager(context: Context) {
    private val prefs = context.getSharedPreferences("lg_tv_prefs", Context.MODE_PRIVATE)
    private var mainWebSocket: WebSocket? = null
    private var pointerWebSocket: WebSocket? = null
    private var currentIp: String? = null
    private val requestId = AtomicInteger(1)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isPromptPending = MutableStateFlow(false)
    val isPromptPending: StateFlow<Boolean> = _isPromptPending.asStateFlow()

    private val _isPointerConnected = MutableStateFlow(false)
    val isPointerConnected: StateFlow<Boolean> = _isPointerConnected.asStateFlow()

    private val _installedApps = MutableStateFlow<List<LgAppInfo>>(emptyList())
    val installedApps: StateFlow<List<LgAppInfo>> = _installedApps.asStateFlow()

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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    fun connect(ip: String) {
        connectInternal(ip, useSsl = true)
    }

    private fun connectInternal(ip: String, useSsl: Boolean) {
        disconnect()
        currentIp = ip
        val port = if (useSsl) 3001 else 3000
        val scheme = if (useSsl) "wss" else "ws"
        val url = "$scheme://$ip:$port"

        Logger.log("LG webOS: Подключение к $url")
        val request = Request.Builder().url(url).build()

        mainWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("LG webOS: Главный WebSocket открыт ($url). Отправка пакета регистрации...")
                sendRegistrationPacket(webSocket, ip)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val id = json.optString("id")
                    val payload = json.optJSONObject("payload")

                    Logger.log("LG webOS Msg: type=$type, id=$id")

                    when (type) {
                        "response" -> {
                            val pairingType = payload?.optString("pairingType")
                            if (pairingType == "PROMPT") {
                                Logger.log("LG webOS: Телевизор ожидает подтверждения пользователя («Разрешить» на экране ТВ)")
                                _isPromptPending.value = true
                            }
                        }
                        "registered" -> {
                            _isPromptPending.value = false
                            _isConnected.value = true
                            val clientKey = payload?.optString("client-key")
                            if (!clientKey.isNullOrBlank()) {
                                prefs.edit().putString("client_key_$ip", clientKey).apply()
                                Logger.log("LG webOS: Сохранен ключ авторизации client-key: $clientKey")
                            }
                            Logger.log("LG webOS: Успешно зарегистрирован и подключен к ТВ!")

                            // 1. Запрашиваем установленные приложения
                            requestInstalledApps()

                            // 2. Запрашиваем Pointer Input Socket для управления курсором Magic Remote и кнопками
                            requestPointerInputSocket()
                        }
                        "error" -> {
                            val error = json.optString("error")
                            Logger.log("LG webOS Ошибка от ТВ: $error")
                        }
                    }

                    // Ответ на запрос сокета курсора
                    if (id.startsWith("req_pointer")) {
                        val socketPath = payload?.optString("socketPath")
                        if (!socketPath.isNullOrBlank()) {
                            Logger.log("LG webOS: Получен путь сокета курсора: $socketPath")
                            connectPointerSocket(socketPath)
                        }
                    }

                    // Ответ на запрос списка приложений
                    if (id.startsWith("req_apps")) {
                        val launchPoints = payload?.optJSONArray("launchPoints")
                        if (launchPoints != null) {
                            val apps = parseLaunchPoints(launchPoints)
                            _installedApps.value = apps
                            Logger.log("LG webOS: Получено ${apps.size} установленных приложений")
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("LG webOS JSON parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("LG webOS ошибка соединения (ssl=$useSsl): ${t.message}")
                _isConnected.value = false
                _isPromptPending.value = false
                if (useSsl) {
                    Logger.log("LG webOS: Фоллбэк на порт 3000 (ws://)")
                    connectInternal(ip, useSsl = false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.log("LG webOS соединение закрыто: $reason")
                _isConnected.value = false
                _isPromptPending.value = false
            }
        })
    }

    private fun sendRegistrationPacket(ws: WebSocket, ip: String) {
        val savedKey = prefs.getString("client_key_$ip", null)
        val permissions = listOf(
            "LAUNCH",
            "LAUNCH_WEBAPP",
            "APP_TO_APP",
            "CLOSE",
            "TEST_OPEN",
            "TEST_PROTECTED",
            "CONTROL_AUDIO",
            "CONTROL_DISPLAY",
            "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_MEDIA_PLAYBACK",
            "CONTROL_INPUT_TV",
            "READ_APP_STATUS",
            "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST",
            "READ_NETWORK_STATE",
            "READ_RUNNING_APPS",
            "READ_TV_CHANNEL_LIST",
            "WRITE_NOTIFICATION_TOAST",
            "READ_POWER_STATE",
            "READ_COUNTRY_INFO",
            "CONTROL_POWER",
            "READ_INSTALLED_APPS",
            "CONTROL_INPUT_TEXT",
            "CONTROL_MOUSE_AND_KEYBOARD",
            "READ_SETTINGS"
        )

        val reg = JSONObject().apply {
            put("type", "register")
            put("id", "reg_${requestId.getAndIncrement()}")
            put("payload", JSONObject().apply {
                put("forcePairing", false)
                put("pairingType", "PROMPT")
                if (!savedKey.isNullOrBlank()) {
                    put("client-key", savedKey)
                }
                put("manifest", JSONObject().apply {
                    put("manifestVersion", 1)
                    put("appVersion", "1.0")
                    put("signed", JSONObject().apply {
                        put("created", "20140509")
                        put("appId", "com.remote.control")
                        put("vendorId", "com.remote")
                        put("localizedAppNames", JSONObject().apply {
                            put("", "Xiaomi Universal Remote")
                            put("ru-RU", "Xiaomi Universal Remote")
                        })
                        put("permissions", JSONArray(permissions))
                        put("serial", "2f930e2d2cdc08497f6b")
                    })
                    put("permissions", JSONArray(permissions))
                })
            })
        }

        ws.send(reg.toString())
    }

    private fun requestPointerInputSocket() {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "req_pointer_${requestId.getAndIncrement()}")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        mainWebSocket?.send(req.toString())
    }

    private fun connectPointerSocket(socketPath: String) {
        runCatching { pointerWebSocket?.close(1000, "Reconnecting") }
        pointerWebSocket = null
        _isPointerConnected.value = false

        val request = Request.Builder().url(socketPath).build()
        pointerWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("LG webOS: Pointer Input Socket открыт и готов к управлению Magic Remote курсором!")
                _isPointerConnected.value = true
                // Немедленно пробуждаем Magic Remote курсор на экране ТВ
                webSocket.send("type:move\ndx:2\ndy:2\ndown:0\n\n")
                webSocket.send("type:move\ndx:-2\ndy:-2\ndown:0\n\n")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("LG webOS: Ошибка Pointer Socket: ${t.message}")
                _isPointerConnected.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.log("LG webOS: Pointer Socket закрыт: $reason")
                _isPointerConnected.value = false
            }
        })
    }

    fun requestInstalledApps() {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "req_apps_${requestId.getAndIncrement()}")
            put("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
        }
        mainWebSocket?.send(req.toString())
    }

    private fun parseLaunchPoints(array: JSONArray): List<LgAppInfo> {
        val list = mutableListOf<LgAppInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = item.optString("title")
            val icon = item.optString("icon")
            if (id.isNotBlank() && title.isNotBlank()) {
                list.add(LgAppInfo(id = id, title = title, icon = icon))
            }
        }
        return list
    }

    // --- Курсор (Magic Remote Air Mouse / Touchpad) ---

    fun wakeCursor() {
        val pws = pointerWebSocket
        if (pws != null && _isPointerConnected.value) {
            pws.send("type:move\ndx:2\ndy:2\ndown:0\n\n")
            pws.send("type:move\ndx:-2\ndy:-2\ndown:0\n\n")
            Logger.log("LG webOS: wakeCursor() — импульс отправлен в Pointer Socket")
        } else {
            Logger.log("LG webOS: wakeCursor() — повторный запрос Pointer Socket")
            requestPointerInputSocket()
        }
    }

    fun sendPointerMove(dx: Float, dy: Float) {
        val pws = pointerWebSocket
        if (pws == null || !_isPointerConnected.value) {
            requestPointerInputSocket()
            return
        }
        val ix = dx.roundToInt()
        val iy = dy.roundToInt()
        if (ix == 0 && iy == 0) return
        val cmd = "type:move\ndx:$ix\ndy:$iy\ndown:0\n\n"
        pws.send(cmd)
    }

    fun sendPointerClick() {
        val pws = pointerWebSocket
        if (pws != null && _isPointerConnected.value) {
            pws.send("type:click\n\n")
        } else {
            sendPointerButton("ENTER")
        }
    }

    fun sendPointerScroll(dy: Int) {
        val pws = pointerWebSocket ?: return
        pws.send("type:scroll\ndx:0\ndy:$dy\n\n")
    }

    fun sendPointerButton(buttonName: String) {
        val pws = pointerWebSocket
        if (pws != null && _isPointerConnected.value) {
            pws.send("type:button\nname:$buttonName\n\n")
        } else {
            // Фолбэк на SSAP команду если сокет мыши не готов
            sendSsapKey(buttonName)
        }
    }

    // --- Клавиши пульта ДУ ---

    fun sendKey(keyCode: RemoteKeyCode) {
        when (keyCode) {
            RemoteKeyCode.KEYCODE_POWER, RemoteKeyCode.KEYCODE_TV_POWER, RemoteKeyCode.KEYCODE_SLEEP, RemoteKeyCode.KEYCODE_SOFT_SLEEP -> {
                sendSsapCommand("ssap://system/turnOff")
            }
            RemoteKeyCode.KEYCODE_DPAD_UP, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_UP -> sendPointerButton("UP")
            RemoteKeyCode.KEYCODE_DPAD_DOWN, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_DOWN -> sendPointerButton("DOWN")
            RemoteKeyCode.KEYCODE_DPAD_LEFT, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_LEFT -> sendPointerButton("LEFT")
            RemoteKeyCode.KEYCODE_DPAD_RIGHT, RemoteKeyCode.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> sendPointerButton("RIGHT")
            RemoteKeyCode.KEYCODE_DPAD_CENTER, RemoteKeyCode.KEYCODE_ENTER, RemoteKeyCode.KEYCODE_NUMPAD_ENTER -> sendPointerButton("ENTER")
            RemoteKeyCode.KEYCODE_BACK -> sendPointerButton("BACK")
            RemoteKeyCode.KEYCODE_HOME, RemoteKeyCode.KEYCODE_ALL_APPS -> sendPointerButton("HOME")
            RemoteKeyCode.KEYCODE_VOLUME_UP -> sendPointerButton("VOLUMEUP")
            RemoteKeyCode.KEYCODE_VOLUME_DOWN -> sendPointerButton("VOLUMEDOWN")
            RemoteKeyCode.KEYCODE_VOLUME_MUTE, RemoteKeyCode.KEYCODE_MUTE -> sendPointerButton("MUTE")
            RemoteKeyCode.KEYCODE_CHANNEL_UP, RemoteKeyCode.KEYCODE_PAGE_UP -> sendPointerButton("CHANNELUP")
            RemoteKeyCode.KEYCODE_CHANNEL_DOWN, RemoteKeyCode.KEYCODE_PAGE_DOWN -> sendPointerButton("CHANNELDOWN")
            RemoteKeyCode.KEYCODE_SETTINGS, RemoteKeyCode.KEYCODE_MENU -> sendPointerButton("MENU")
            RemoteKeyCode.KEYCODE_INFO -> sendPointerButton("INFO")
            RemoteKeyCode.KEYCODE_GUIDE -> sendPointerButton("GUIDE")
            RemoteKeyCode.KEYCODE_CAPTIONS -> sendPointerButton("CC")
            RemoteKeyCode.KEYCODE_ESCAPE -> sendPointerButton("EXIT")
            RemoteKeyCode.KEYCODE_MEDIA_PLAY -> sendPointerButton("PLAY")
            RemoteKeyCode.KEYCODE_MEDIA_PAUSE -> sendPointerButton("PAUSE")
            RemoteKeyCode.KEYCODE_MEDIA_STOP -> sendPointerButton("STOP")
            RemoteKeyCode.KEYCODE_MEDIA_REWIND -> sendPointerButton("REWIND")
            RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD -> sendPointerButton("FASTFORWARD")
            RemoteKeyCode.KEYCODE_PROG_RED -> sendPointerButton("RED")
            RemoteKeyCode.KEYCODE_PROG_GREEN -> sendPointerButton("GREEN")
            RemoteKeyCode.KEYCODE_PROG_YELLOW -> sendPointerButton("YELLOW")
            RemoteKeyCode.KEYCODE_PROG_BLUE -> sendPointerButton("BLUE")
            RemoteKeyCode.KEYCODE_0, RemoteKeyCode.KEYCODE_NUMPAD_0 -> sendPointerButton("0")
            RemoteKeyCode.KEYCODE_1, RemoteKeyCode.KEYCODE_NUMPAD_1 -> sendPointerButton("1")
            RemoteKeyCode.KEYCODE_2, RemoteKeyCode.KEYCODE_NUMPAD_2 -> sendPointerButton("2")
            RemoteKeyCode.KEYCODE_3, RemoteKeyCode.KEYCODE_NUMPAD_3 -> sendPointerButton("3")
            RemoteKeyCode.KEYCODE_4, RemoteKeyCode.KEYCODE_NUMPAD_4 -> sendPointerButton("4")
            RemoteKeyCode.KEYCODE_5, RemoteKeyCode.KEYCODE_NUMPAD_5 -> sendPointerButton("5")
            RemoteKeyCode.KEYCODE_6, RemoteKeyCode.KEYCODE_NUMPAD_6 -> sendPointerButton("6")
            RemoteKeyCode.KEYCODE_7, RemoteKeyCode.KEYCODE_NUMPAD_7 -> sendPointerButton("7")
            RemoteKeyCode.KEYCODE_8, RemoteKeyCode.KEYCODE_NUMPAD_8 -> sendPointerButton("8")
            RemoteKeyCode.KEYCODE_9, RemoteKeyCode.KEYCODE_NUMPAD_9 -> sendPointerButton("9")
            RemoteKeyCode.KEYCODE_DEL -> {
                sendSsapCommand("ssap://com.webos.service.ime/deleteCharacters", JSONObject().apply {
                    put("count", 1)
                })
            }
            else -> {
                Logger.log("LG webOS: Неизвестная клавиша ${keyCode.name}")
            }
        }
    }

    private fun sendSsapKey(buttonName: String) {
        when (buttonName) {
            "VOLUMEUP" -> sendSsapCommand("ssap://audio/volumeUp")
            "VOLUMEDOWN" -> sendSsapCommand("ssap://audio/volumeDown")
            "MUTE" -> sendSsapCommand("ssap://audio/setMute", JSONObject().apply { put("mute", true) })
            "PLAY" -> sendSsapCommand("ssap://media.controls/play")
            "PAUSE" -> sendSsapCommand("ssap://media.controls/pause")
            "STOP" -> sendSsapCommand("ssap://media.controls/stop")
            "REWIND" -> sendSsapCommand("ssap://media.controls/rewind")
            "FASTFORWARD" -> sendSsapCommand("ssap://media.controls/fastForward")
            else -> {
                Logger.log("LG webOS SSAP фолбэк для $buttonName")
            }
        }
    }

    // --- Ввод текста ---

    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendSsapCommand("ssap://com.webos.service.ime/insertText", JSONObject().apply {
            put("text", text)
            put("replace", 0)
        })
        Logger.log("LG webOS отправка текста: \"$text\"")
    }

    // --- Запуск приложений ---

    fun launchApp(appTitleOrId: String) {
        val targetId = when {
            appTitleOrId.equals("YouTube", ignoreCase = true) -> "youtube.leanback.v4"
            appTitleOrId.equals("Netflix", ignoreCase = true) -> "netflix"
            appTitleOrId.equals("Prime Video", ignoreCase = true) -> "amazon"
            appTitleOrId.equals("Браузер", ignoreCase = true) || appTitleOrId.equals("Browser", ignoreCase = true) -> "com.webos.app.browser"
            appTitleOrId.equals("Кинопоиск", ignoreCase = true) || appTitleOrId.equals("Kinopoisk", ignoreCase = true) -> "kinopoisk"
            appTitleOrId.equals("Megogo", ignoreCase = true) -> "megogo"
            appTitleOrId.equals("Ivi", ignoreCase = true) -> "ivi"
            appTitleOrId.equals("Live TV", ignoreCase = true) || appTitleOrId.equals("ТВ", ignoreCase = true) -> "com.webos.app.livetv"
            else -> {
                // Ищем по заголовку среди установленных
                val matched = _installedApps.value.find { it.title.equals(appTitleOrId, ignoreCase = true) }
                matched?.id ?: appTitleOrId
            }
        }

        Logger.log("LG webOS запуск приложения: $appTitleOrId -> $targetId")
        sendSsapCommand("ssap://system.launcher/launch", JSONObject().apply {
            put("id", targetId)
        })
    }

    private fun sendSsapCommand(uri: String, payload: JSONObject? = null) {
        val ws = mainWebSocket ?: return
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "req_${requestId.getAndIncrement()}")
            put("uri", uri)
            if (payload != null) {
                put("payload", payload)
            }
        }
        ws.send(req.toString())
    }

    fun disconnect() {
        runCatching { pointerWebSocket?.close(1000, "Disconnect") }
        runCatching { mainWebSocket?.close(1000, "Disconnect") }
        pointerWebSocket = null
        mainWebSocket = null
        _isConnected.value = false
        _isPointerConnected.value = false
        _isPromptPending.value = false
    }
}
