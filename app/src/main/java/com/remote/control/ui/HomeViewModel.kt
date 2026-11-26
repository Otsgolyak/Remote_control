package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remote.control.data.ConnectionManager
import com.remote.control.data.bluetooth.AirMouseController
import com.remote.control.data.device.CustomShortcutData
import com.remote.control.data.device.Device
import com.remote.control.data.device.DeviceRepository
import com.remote.control.data.lg.LgAppInfo
import com.remote.control.data.lg.LgScanner
import com.remote.control.data.lg.LgWebOsConnectionManager
import com.remote.control.data.samsung.SamsungConnectionManager
import com.remote.control.data.samsung.SamsungScanner
import com.remote.control.data.wifi.DiscoveredTv
import com.remote.control.data.wifi.NsdScanner
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import remote.RemoteAppLinkLaunchRequest
import remote.RemoteKeyCode
import remote.RemoteMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class AppShortcut(
    val title: String,
    val deepLink: String,
    val iconName: String = "tv",
    val fallbackKey: RemoteKeyCode? = null,
    val isCustom: Boolean = false
)

val defaultShortcuts = listOf(
    AppShortcut("YouTube", "https://www.youtube.com", "youtube"),
    AppShortcut("Netflix", "https://www.netflix.com/title", "movie"),
    AppShortcut("Prime Video", "https://app.primevideo.com", "video"),
    AppShortcut("Браузер", "https://www.google.com", "web"),
    AppShortcut("Настройки", "", "settings", RemoteKeyCode.KEYCODE_SETTINGS),
    AppShortcut("Приложения", "", "apps", RemoteKeyCode.KEYCODE_ALL_APPS)
)

data class RemoteState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isSamsungMode: Boolean = false,
    val isLgMode: Boolean = false,
    val status: String = "Не подключено",
    val selectedDeviceName: String = "Xiaomi TV",
    val selectedDeviceIp: String? = null,
    val discoveredDevices: List<DiscoveredTv> = emptyList(),
    val isScanning: Boolean = false,
    val isPairingPinRequired: Boolean = false,
    val pendingPairingName: String = "",
    val pendingPairingIp: String = "",
    val pairingError: String? = null,
    val isVoiceActive: Boolean = false,
    val imeVisible: Boolean = false,
    val isGyroEnabled: Boolean = false,
    val isBtMouseConnected: Boolean = false,
    val isBtScanning: Boolean = false,
    val discoveredBtDevices: List<com.remote.control.data.bluetooth.DiscoveredBluetoothDevice> = emptyList(),
    val btMouseStatus: String = "Тачпад (Wi-Fi) / Курсор на ТВ",
    val btPairingPromptDevice: String? = null,
    val apps: List<AppShortcut> = defaultShortcuts,
    val availableTvApps: List<com.remote.control.data.apps.TvAppCandidate> = emptyList(),
    val isLoadingApps: Boolean = false,
    val mouseSensitivity: Float = 1.5f,
    val cursorPosition: Pair<Float, Float> = Pair(0f, 0f)
)

class HomeViewModel(
    private val repository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val pairingRepository: PairingRepository,
    private val nsdScanner: NsdScanner,
    private val airMouseController: AirMouseController,
    private val samsungConnectionManager: SamsungConnectionManager,
    private val samsungScanner: SamsungScanner,
    private val lgWebOsConnectionManager: LgWebOsConnectionManager,
    private val lgScanner: LgScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteState())
    val uiState: StateFlow<RemoteState> = _uiState.asStateFlow()
    private val isConnecting = AtomicBoolean(false)
    private var lastReconnectAttemptTimeMs: Long = 0L

    private var scanJob: Job? = null

    val pairedDevices: StateFlow<List<Device>> = repository.pairedDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val isDarkTheme: StateFlow<Boolean> = repository.isDarkTheme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false // По умолчанию светлая тема
    )

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDarkTheme(enabled)
        }
    }

    val subscriptionTier: StateFlow<String> = repository.subscriptionTier.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "Free"
    )

    fun applyPromoCode(code: String): String? {
        val trimmed = code.trim().lowercase()
        val newTier = when {
            trimmed == "1" || trimmed == "free" || trimmed == "promo1" || trimmed == "промокод 1" || trimmed == "промокод1" -> "Free"
            trimmed == "2" || trimmed == "premium" || trimmed == "promo2" || trimmed == "промокод 2" || trimmed == "промокод2" || trimmed == "pro" -> "Premium"
            else -> null
        }
        if (newTier != null) {
            viewModelScope.launch {
                repository.setSubscriptionTier(newTier)
            }
        }
        return newTier
    }

    init {
        airMouseController.onFallbackDirection = { dx, dy ->
            if (abs(dx) > abs(dy)) {
                sendGyroKey(if (dx > 0) RemoteKeyCode.KEYCODE_DPAD_RIGHT else RemoteKeyCode.KEYCODE_DPAD_LEFT)
            } else {
                sendGyroKey(if (dy > 0) RemoteKeyCode.KEYCODE_DPAD_DOWN else RemoteKeyCode.KEYCODE_DPAD_UP)
            }
        }

        airMouseController.onVisualCursorDelta = { dx, dy ->
            val current = _uiState.value.cursorPosition
            val newX = (current.first + dx * 0.4f).coerceIn(-120f, 120f)
            val newY = (current.second + dy * 0.4f).coerceIn(-65f, 65f)
            _uiState.value = _uiState.value.copy(cursorPosition = Pair(newX, newY))
            if (_uiState.value.isLgMode && lgWebOsConnectionManager.isConnected.value) {
                lgWebOsConnectionManager.sendPointerMove(dx, dy)
            }
        }

        viewModelScope.launch {
            combine(
                connectionManager.isConnected,
                samsungConnectionManager.isConnected,
                lgWebOsConnectionManager.isConnected
            ) { atvConn, samConn, lgConn ->
                Triple(atvConn, samConn, lgConn)
            }.collect { (atvConn, samConn, lgConn) ->
                val isSam = _uiState.value.isSamsungMode
                val isLg = _uiState.value.isLgMode
                val connected = when {
                    isLg -> lgConn
                    isSam -> samConn
                    else -> atvConn
                }
                val ip = _uiState.value.selectedDeviceIp
                val prefix = when {
                    isLg -> "LG webOS"
                    isSam -> "Samsung Tizen"
                    else -> "Android TV"
                }
                _uiState.value = _uiState.value.copy(
                    isConnected = connected,
                    status = when {
                        connected && ip != null -> "$prefix Подключено ($ip)"
                        ip != null -> "$prefix Отключено ($ip)"
                        else -> "Не подключено"
                    }
                )
            }
        }

        viewModelScope.launch {
            connectionManager.isVoiceActive.collect { active ->
                _uiState.value = _uiState.value.copy(isVoiceActive = active)
            }
        }

        viewModelScope.launch {
            connectionManager.imeVisible.collect { visible ->
                _uiState.value = _uiState.value.copy(imeVisible = visible)
            }
        }

        viewModelScope.launch {
            airMouseController.isGyroEnabled.collect { gyro ->
                _uiState.value = _uiState.value.copy(isGyroEnabled = gyro)
            }
        }

        viewModelScope.launch {
            airMouseController.isBtConnected.collect { btConn ->
                _uiState.value = _uiState.value.copy(isBtMouseConnected = btConn)
            }
        }

        viewModelScope.launch {
            airMouseController.btStatus.collect { status ->
                _uiState.value = _uiState.value.copy(btMouseStatus = status)
            }
        }

        // Подписка на статус Bluetooth-сканирования для отображения индикатора загрузки
        viewModelScope.launch {
            airMouseController.isBtScanning.collect { scanning ->
                _uiState.value = _uiState.value.copy(isBtScanning = scanning)
            }
        }

        // Подписка на список найденных Bluetooth-устройств (отсортированных по убыванию RSSI)
        viewModelScope.launch {
            airMouseController.discoveredBtDevices.collect { list ->
                _uiState.value = _uiState.value.copy(discoveredBtDevices = list)
            }
        }

        viewModelScope.launch {
            airMouseController.btPairingPromptDevice.collect { deviceName ->
                _uiState.value = _uiState.value.copy(btPairingPromptDevice = deviceName)
            }
        }

        viewModelScope.launch {
            repository.customShortcuts.collect { customList ->
                val mapped = customList.map {
                    AppShortcut(
                        title = it.title,
                        deepLink = it.deepLink,
                        iconName = it.iconName,
                        isCustom = true
                    )
                }
                _uiState.value = _uiState.value.copy(apps = defaultShortcuts + mapped)
            }
        }

        viewModelScope.launch {
            val savedList = repository.pairedDevices.firstOrNull().orEmpty()
            val lastIp = repository.selectedDeviceIp.firstOrNull() ?: savedList.firstOrNull()?.ip
            val savedDevice = savedList.find { it.ip == lastIp } ?: savedList.firstOrNull()
            if (savedDevice != null) {
                connectToSavedDevice(savedDevice)
            }
        }

        startDiscovery()
    }

    fun setSensitivity(value: Float) {
        airMouseController.sensitivity = value
        _uiState.value = _uiState.value.copy(mouseSensitivity = value)
    }

    fun resetCursorCenter() {
        _uiState.value = _uiState.value.copy(cursorPosition = Pair(0f, 0f))
    }

    fun updateCursor(dx: Float, dy: Float) {
        val current = _uiState.value.cursorPosition
        val sensitivity = _uiState.value.mouseSensitivity
        val newX = (current.first + dx * 0.35f * sensitivity).coerceIn(-120f, 120f)
        val newY = (current.second + dy * 0.35f * sensitivity).coerceIn(-65f, 65f)
        _uiState.value = _uiState.value.copy(cursorPosition = Pair(newX, newY))
        if (_uiState.value.isLgMode) {
            lgWebOsConnectionManager.sendPointerMove(dx * sensitivity, dy * sensitivity)
        } else {
            airMouseController.sendMouseDelta(dx, dy)
        }
    }

    fun startDiscovery() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = true)
        scanJob = viewModelScope.launch {
            try {
                combine(
                    nsdScanner.discover(),
                    samsungScanner.discoverSamsungTvs(),
                    lgScanner.discoverLgTvs()
                ) { nsdList, samsungList, lgList ->
                    val mergedMap = LinkedHashMap<String, DiscoveredTv>()

                    for (tv in nsdList) {
                        mergedMap[tv.ip] = tv
                    }

                    for (samsung in samsungList) {
                        val existing = mergedMap[samsung.ip]
                        val displayName = when {
                            samsung.name.isNotBlank() && !samsung.name.equals("Samsung Smart TV", ignoreCase = true) -> samsung.name
                            existing != null && existing.name.isNotBlank() -> existing.name
                            else -> "Samsung Smart TV"
                        }
                        mergedMap[samsung.ip] = DiscoveredTv(
                            name = displayName,
                            ip = samsung.ip,
                            port = samsung.port
                        )
                    }

                    for (lg in lgList) {
                        val existing = mergedMap[lg.ip]
                        val displayName = when {
                            lg.name.isNotBlank() && !lg.name.equals("LG webOS Smart TV", ignoreCase = true) -> lg.name
                            existing != null && existing.name.isNotBlank() -> existing.name
                            else -> "LG webOS Smart TV"
                        }
                        mergedMap[lg.ip] = DiscoveredTv(
                            name = displayName,
                            ip = lg.ip,
                            port = lg.port
                        )
                    }

                    mergedMap.values.toList()
                }.collect { combinedList ->
                    _uiState.value = _uiState.value.copy(
                        discoveredDevices = combinedList,
                        isScanning = true
                    )
                }
            } catch (e: Exception) {
                Logger.log("Ошибка сканирования сети: ${e.message}")
                _uiState.value = _uiState.value.copy(isScanning = false)
            }
        }
    }

    fun connectToLgTv(name: String, ip: String, onConnected: () -> Unit = {}) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return
        val devName = name.ifBlank { "LG webOS TV ($cleanIp)" }
        viewModelScope.launch {
            repository.addDevice(Device(cleanIp, devName, isSamsung = false, isLg = true))
            _uiState.value = _uiState.value.copy(
                isSamsungMode = false,
                isLgMode = true,
                selectedDeviceIp = cleanIp,
                selectedDeviceName = devName,
                isConnecting = true,
                pairingError = null,
                status = "Подключение к LG webOS $cleanIp..."
            )
            repository.setSelectedDevice(cleanIp)
            connectionManager.disconnect()
            samsungConnectionManager.disconnect()
            Logger.log("Запуск подключения к LG webOS TV ($cleanIp) по WebSocket...")
            lgWebOsConnectionManager.connect(cleanIp)
            delay(500)
            _uiState.value = _uiState.value.copy(isConnecting = false)
            onConnected()
        }
    }

    fun connectToSamsungTv(name: String, ip: String, onConnected: () -> Unit = {}) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return
        val devName = name.ifBlank { "Samsung TV ($cleanIp)" }
        viewModelScope.launch {
            repository.addDevice(Device(cleanIp, devName, isSamsung = true, isLg = false))
            _uiState.value = _uiState.value.copy(
                isSamsungMode = true,
                isLgMode = false,
                selectedDeviceIp = cleanIp,
                selectedDeviceName = devName,
                isConnecting = true,
                pairingError = null,
                status = "Подключение к $cleanIp..."
            )
            repository.setSelectedDevice(cleanIp)
            connectionManager.disconnect()
            lgWebOsConnectionManager.disconnect()
            Logger.log("Запуск подключения к Samsung TV ($cleanIp) по WebSocket...")
            samsungConnectionManager.connect(cleanIp)
            delay(500)
            _uiState.value = _uiState.value.copy(isConnecting = false)
            onConnected()
        }
    }

    fun connectToSavedDevice(device: Device, onConnected: () -> Unit = {}) {
        viewModelScope.launch {
            if (!isConnecting.compareAndSet(false, true)) {
                Logger.log("Подключение к ${device.ip} уже выполняется, пропускаем повторный запуск")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                selectedDeviceName = device.name,
                selectedDeviceIp = device.ip,
                isSamsungMode = device.isSamsung,
                isLgMode = device.isLg,
                isConnecting = true,
                pairingError = null,
                status = "Подключение к ${device.ip}..."
            )
            repository.setSelectedDevice(device.ip)

            try {
                if (device.isLg) {
                    connectionManager.disconnect()
                    samsungConnectionManager.disconnect()
                    lgWebOsConnectionManager.connect(device.ip)
                    delay(500)
                    _uiState.value = _uiState.value.copy(isConnecting = false)
                    onConnected()
                    return@launch
                }

                if (device.isSamsung) {
                    connectionManager.disconnect()
                    lgWebOsConnectionManager.disconnect()
                    samsungConnectionManager.connect(device.ip)
                    delay(500)
                    _uiState.value = _uiState.value.copy(isConnecting = false)
                    onConnected()
                    return@launch
                }

                samsungConnectionManager.disconnect()
                lgWebOsConnectionManager.disconnect()

                try {
                    connectionManager.connect(device.ip, 6466)
                    delay(400)
                    if (connectionManager.isConnected.value) {
                        _uiState.value = _uiState.value.copy(
                            isConnecting = false,
                            status = "Android TV Подключено (${device.ip})"
                        )
                        onConnected()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isConnecting = false,
                            status = "Не удалось подключиться к ${device.ip}"
                        )
                    }
                } catch (e: Exception) {
                    val isNetworkError = e is java.net.SocketTimeoutException ||
                        e is java.net.ConnectException ||
                        e is java.net.NoRouteToHostException ||
                        e.message?.contains("failed to connect", ignoreCase = true) == true ||
                        e.message?.contains("timed out", ignoreCase = true) == true ||
                        e.message?.contains("refused", ignoreCase = true) == true ||
                        e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ||
                        e.message?.contains("EHOSTUNREACH", ignoreCase = true) == true ||
                        e.message?.contains("ENETUNREACH", ignoreCase = true) == true

                    val isSslError = e is javax.net.ssl.SSLException ||
                        e.cause is javax.net.ssl.SSLException ||
                        e.message?.contains("handshake", ignoreCase = true) == true ||
                        e.message?.contains("certificate", ignoreCase = true) == true ||
                        e.message?.contains("ssl", ignoreCase = true) == true

                    if (isSslError && !isNetworkError) {
                        Logger.log("ТВ ${device.ip} отклонил SSL-сертификат, требуется сопряжение: ${e.message}")
                        initiatePairing(device.name, device.ip)
                    } else {
                        Logger.log("Ошибка сетевого подключения к ${device.ip}:6466 (${e.message}). ТВ выключен или недоступен.")
                        _uiState.value = _uiState.value.copy(
                            isConnecting = false,
                            status = "Телевизор не отвечает по сети (возможно, выключен)"
                        )
                    }
                }
            } finally {
                isConnecting.set(false)
            }
        }
    }

    fun initiatePairing(name: String, ip: String) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                isSamsungMode = false,
                isLgMode = false,
                pairingError = null,
                pendingPairingName = name.ifBlank { "Xiaomi TV ($cleanIp)" },
                pendingPairingIp = cleanIp,
                status = "Запрос PIN-кода у $cleanIp..."
            )
            val result = pairingRepository.startPairing(cleanIp)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isPairingPinRequired = true,
                        pairingError = null,
                        status = "Введите PIN с экрана ТВ"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isPairingPinRequired = false,
                        pairingError = "Не удалось начать сопряжение с $cleanIp: ${err.message}",
                        status = "Ошибка сопряжения"
                    )
                }
            )
        }
    }

    fun confirmPin(pin: String, onSuccess: () -> Unit) {
        val ip = _uiState.value.pendingPairingIp
        val name = _uiState.value.pendingPairingName.ifBlank { "Xiaomi TV ($ip)" }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                pairingError = null,
                status = "Проверка PIN-кода..."
            )
            val result = pairingRepository.confirmPin(pin)
            result.fold(
                onSuccess = {
                    val device = Device(ip = ip, name = name, isSamsung = false, isLg = false)
                    repository.addDevice(device)
                    _uiState.value = _uiState.value.copy(
                        isPairingPinRequired = false,
                        selectedDeviceIp = ip,
                        selectedDeviceName = name,
                        isSamsungMode = false,
                        isLgMode = false,
                        status = "Сопряжено! Подключение..."
                    )
                    delay(300)
                    runCatching { connectionManager.connect(ip, 6466) }
                    _uiState.value = _uiState.value.copy(isConnecting = false)
                    onSuccess()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        pairingError = "Ошибка PIN-кода: ${err.message}"
                    )
                }
            )
        }
    }

    fun dismissPinDialog() {
        _uiState.value = _uiState.value.copy(
            isPairingPinRequired = false,
            pairingError = null
        )
    }

    fun renameDevice(ip: String, newName: String) {
        val clean = newName.trim()
        if (ip.isBlank() || clean.isBlank()) return
        viewModelScope.launch {
            repository.renameDevice(ip, clean)
            if (_uiState.value.selectedDeviceIp == ip) {
                _uiState.value = _uiState.value.copy(selectedDeviceName = clean)
            }
            Logger.log("ТВ ($ip) переименован в \"$clean\"")
        }
    }

    fun toggleDeviceType(ip: String) {
        viewModelScope.launch {
            repository.toggleDeviceType(ip)
            val updated = repository.pairedDevices.firstOrNull()?.find { it.ip == ip }
            if (updated != null && _uiState.value.selectedDeviceIp == ip) {
                _uiState.value = _uiState.value.copy(
                    isSamsungMode = updated.isSamsung,
                    isLgMode = updated.isLg
                )
            }
            Logger.log("Переключен тип устройства ($ip), Samsung=${updated?.isSamsung}, LG=${updated?.isLg}")
        }
    }

    fun removeSavedDevice(ip: String) {
        viewModelScope.launch {
            repository.removeDevice(ip)
            if (_uiState.value.selectedDeviceIp == ip) {
                connectionManager.disconnect()
                samsungConnectionManager.disconnect()
                lgWebOsConnectionManager.disconnect()
                _uiState.value = _uiState.value.copy(
                    selectedDeviceIp = null,
                    selectedDeviceName = "Xiaomi TV",
                    status = "Не подключено"
                )
            }
        }
    }

    fun addCustomShortcut(title: String, deepLink: String, iconName: String) {
        val cleanTitle = title.trim()
        val cleanLink = deepLink.trim()
        if (cleanTitle.isEmpty() || cleanLink.isEmpty()) return
        viewModelScope.launch {
            val formattedLink = if (
                !cleanLink.startsWith("http://") &&
                !cleanLink.startsWith("https://") &&
                !cleanLink.contains("://")
            ) {
                "https://$cleanLink"
            } else {
                cleanLink
            }
            repository.addCustomShortcut(
                CustomShortcutData(
                    title = cleanTitle,
                    deepLink = formattedLink,
                    iconName = iconName
                )
            )
            Logger.log("Добавлен новый ярлык приложения: $cleanTitle -> $formattedLink")
        }
    }

    fun removeCustomShortcut(title: String) {
        viewModelScope.launch {
            repository.removeCustomShortcut(title)
            Logger.log("Удален ярлык приложения: $title")
        }
    }

    fun sendGyroKey(keyCode: RemoteKeyCode) {
        viewModelScope.launch {
            if (_uiState.value.isLgMode) {
                if (lgWebOsConnectionManager.isConnected.value) {
                    lgWebOsConnectionManager.sendKey(keyCode)
                }
            } else if (_uiState.value.isSamsungMode) {
                if (samsungConnectionManager.isConnected.value) {
                    samsungConnectionManager.sendKey(keyCode)
                }
            } else {
                if (connectionManager.isConnected.value) {
                    connectionManager.sendKey(keyCode)
                }
            }
        }
    }

    fun sendKey(keyCode: RemoteKeyCode) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val isLg = _uiState.value.isLgMode
            val isSamsung = _uiState.value.isSamsungMode
            val targetIp = _uiState.value.selectedDeviceIp
            Logger.log("HomeViewModel.sendKey: старт отправки ${keyCode.name} (mode=${when { isLg -> "LG" isSamsung -> "Samsung" else -> "AndroidTV" }}, ip=$targetIp)")

            if (isLg) {
                if (!lgWebOsConnectionManager.isConnected.value) {
                    val ip = targetIp ?: run {
                        Logger.log("HomeViewModel.sendKey: отмена, IP LG ТВ не выбран")
                        return@launch
                    }
                    if (now - lastReconnectAttemptTimeMs >= 5000L) {
                        lastReconnectAttemptTimeMs = now
                        Logger.log("Переподключение к LG webOS TV ($ip) перед отправкой ${keyCode.name}...")
                        lgWebOsConnectionManager.connect(ip)
                        delay(300)
                    }
                }
                Logger.log("LG webOS sendKey: ${keyCode.name} -> отправка через WebSocket")
                lgWebOsConnectionManager.sendKey(keyCode)
                return@launch
            }

            if (isSamsung) {
                if (!samsungConnectionManager.isConnected.value) {
                    val ip = targetIp ?: run {
                        Logger.log("HomeViewModel.sendKey: отмена, IP телевизора не выбран")
                        return@launch
                    }
                    if (now - lastReconnectAttemptTimeMs >= 5000L) {
                        lastReconnectAttemptTimeMs = now
                        Logger.log("Переподключение к Samsung TV ($ip) перед отправкой ${keyCode.name}...")
                        samsungConnectionManager.connect(ip)
                        delay(300)
                    }
                }
                Logger.log("Samsung Tizen sendKey: ${keyCode.name} -> отправка через WebSocket")
                samsungConnectionManager.sendKey(keyCode)
                return@launch
            }

            if (!connectionManager.isConnected.value) {
                val ip = targetIp ?: run {
                    Logger.log("HomeViewModel.sendKey: отмена, IP Android TV не выбран")
                    return@launch
                }
                if (now - lastReconnectAttemptTimeMs >= 5000L) {
                    if (isConnecting.compareAndSet(false, true)) {
                        lastReconnectAttemptTimeMs = now
                        try {
                            Logger.log("Переподключение к Android TV $ip перед отправкой ${keyCode.name}...")
                            connectionManager.connect(ip, 6466)
                        } catch (e: Exception) {
                            Logger.log("Ошибка подключения к $ip: ${e.message}")
                        } finally {
                            isConnecting.set(false)
                        }
                    }
                }
            }
            if (connectionManager.isConnected.value) {
                connectionManager.sendKey(keyCode)
                Logger.log("HomeViewModel.sendKey: клавиша ${keyCode.name} успешно передана в connectionManager")
            } else {
                Logger.log("HomeViewModel.sendKey: соединение с Android TV не активно, клавиша ${keyCode.name} не доставлена")
            }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            if (_uiState.value.isLgMode) {
                lgWebOsConnectionManager.sendText(text)
                return@launch
            }
            if (_uiState.value.isSamsungMode) {
                samsungConnectionManager.sendText(text)
                return@launch
            }
            if (!connectionManager.isConnected.value) {
                val ip = _uiState.value.selectedDeviceIp ?: return@launch
                if (!isConnecting.get()) {
                    runCatching { connectionManager.connect(ip, 6466) }
                }
            }
            connectionManager.sendText(text)
        }
    }

    fun dismissIme() {
        connectionManager.dismissIme()
    }

    fun sendTextAsKeys(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            if (_uiState.value.isLgMode) {
                lgWebOsConnectionManager.sendText(text)
                return@launch
            }
            if (_uiState.value.isSamsungMode) {
                samsungConnectionManager.sendText(text)
                return@launch
            }
            for (ch in text) {
                connectionManager.sendCharKey(ch)
                delay(55)
            }
        }
    }

    fun toggleVoice() {
        viewModelScope.launch {
            if (connectionManager.isVoiceActive.value) {
                connectionManager.stopVoiceStream()
                return@launch
            }
            if (!connectionManager.isConnected.value) {
                val ip = _uiState.value.selectedDeviceIp
                if (ip != null && !isConnecting.get()) {
                    runCatching { connectionManager.connect(ip, 6466) }
                }
            }
            if (connectionManager.isConnected.value) {
                connectionManager.startVoiceStream()
            } else {
                Logger.log("Голос не запущен: нет соединения с ТВ")
            }
        }
    }

    fun connectBluetoothMouse() {
        airMouseController.initBluetoothHid()
    }

    /**
     * Автоматическое фоновое рукопожатие с ближайшим ТВ:
     * 1) Проверяет уже сопряженные устройства.
     * 2) Если сопряженного нет - запускает тихое сканирование BT, сортирует устройства по уровню сигнала (RSSI)
     *    и выполняет сопряжение (createBond) и HID-подключение от самого сильного сигнала к слабому.
     */
    fun autoConnectBluetoothMouse() {
        val targetName = _uiState.value.selectedDeviceName
        Logger.log("HomeViewModel.autoConnectBluetoothMouse: запуск тихого подключения к ТВ (targetName='$targetName')")
        airMouseController.autoConnectStrongestTv(targetName)
    }

    /**
     * Запуск сканирования окружающих BT-устройств с фиксацией уровня сигнала RSSI.
     */
    fun startBluetoothDiscovery() {
        Logger.log("HomeViewModel.startBluetoothDiscovery: ручной запуск сканирования устройств")
        airMouseController.startBluetoothDiscovery()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun getBondedBluetoothDevices(): List<Pair<String, String>> {
        return airMouseController.getBondedDevices()
            .map { (it.name ?: "Bluetooth TV") to it.address }
            .also { Logger.log("BT список сопряженных устройств: ${it.size}") }
    }

    fun connectBluetoothMouseToAddress(address: String) {
        Logger.log("Выбрано BT-устройство для HID-мыши: $address")
        airMouseController.connectToAddress(address)
    }

    fun makeBluetoothDiscoverable(context: android.content.Context) {
        airMouseController.initBluetoothHid()
        airMouseController.makeDiscoverable(context)
    }

    fun dismissBtPairingPrompt() {
        airMouseController.dismissPairingPrompt()
    }

    fun setGyroEnabled(enabled: Boolean) {
        airMouseController.setGyroEnabled(enabled)
    }

    fun onTouchpadMove(dx: Float, dy: Float) {
        if (_uiState.value.isLgMode) {
            val sensitivity = _uiState.value.mouseSensitivity
            val scaledDx = dx * sensitivity
            val scaledDy = dy * sensitivity
            val current = _uiState.value.cursorPosition
            val newX = (current.first + scaledDx * 0.35f).coerceIn(-120f, 120f)
            val newY = (current.second + scaledDy * 0.35f).coerceIn(-65f, 65f)
            _uiState.value = _uiState.value.copy(cursorPosition = Pair(newX, newY))
            lgWebOsConnectionManager.sendPointerMove(scaledDx, scaledDy)
        } else {
            airMouseController.sendMouseDelta(dx, dy)
        }
    }

    fun onMouseClick(left: Boolean) {
        Logger.log("AirMouse клик: left=$left, isLgMode=${_uiState.value.isLgMode}, btConnected=${airMouseController.isBtConnected.value}")
        if (_uiState.value.isLgMode) {
            if (left) {
                lgWebOsConnectionManager.sendPointerClick()
            } else {
                lgWebOsConnectionManager.sendPointerButton("BACK")
            }
        } else if (airMouseController.isBtConnected.value) {
            airMouseController.sendClick(left)
        } else {
            sendKey(if (left) RemoteKeyCode.KEYCODE_DPAD_CENTER else RemoteKeyCode.KEYCODE_BACK)
        }
    }

    fun onMouseScroll(wheel: Int) {
        if (_uiState.value.isLgMode) {
            lgWebOsConnectionManager.sendPointerScroll(-wheel * 15)
        } else if (airMouseController.isBtConnected.value) {
            airMouseController.sendMouseDelta(0f, 0f, wheel = wheel)
        } else {
            sendKey(if (wheel > 0) RemoteKeyCode.KEYCODE_DPAD_UP else RemoteKeyCode.KEYCODE_DPAD_DOWN)
        }
    }

    fun loadAvailableTvApps(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingApps = true)
            try {
                val isSamsung = _uiState.value.isSamsungMode
                val deviceIp = _uiState.value.selectedDeviceIp
                if (isSamsung) {
                    samsungConnectionManager.requestInstalledApps()
                    delay(300)
                }
                val allApps = com.remote.control.data.apps.TvAppCatalog.getAvailableTvApps(
                    context = context,
                    deviceIp = deviceIp,
                    isSamsung = isSamsung,
                    samsungManager = samsungConnectionManager
                )
                // Исключаем те, что уже есть на главном экране в быстром запуске
                val currentShortcuts = _uiState.value.apps
                val filtered = allApps.filter { candidate ->
                    currentShortcuts.none { existing ->
                        existing.title.equals(candidate.title, ignoreCase = true) ||
                            (candidate.deepLink.isNotBlank() && existing.deepLink.equals(candidate.deepLink, ignoreCase = true))
                    }
                }
                _uiState.value = _uiState.value.copy(availableTvApps = filtered, isLoadingApps = false)
                Logger.log("HomeViewModel.loadAvailableTvApps: доступно для добавления ${filtered.size} приложений")
            } catch (e: Exception) {
                Logger.log("HomeViewModel.loadAvailableTvApps ошибка: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoadingApps = false)
            }
        }
    }

    fun launchShortcut(shortcut: AppShortcut, context: android.content.Context? = null) {
        viewModelScope.launch {
            if (_uiState.value.isLgMode) {
                lgWebOsConnectionManager.launchApp(shortcut.title)
                return@launch
            }
            if (_uiState.value.isSamsungMode) {
                samsungConnectionManager.launchApp(shortcut.title)
                return@launch
            }
            if (shortcut.fallbackKey != null) {
                sendKey(shortcut.fallbackKey)
                return@launch
            }

            // Попытка прямого запуска через ADB по пакету, если deepLink или пакет известен
            val targetIp = _uiState.value.selectedDeviceIp
            if (!targetIp.isNullOrBlank() && context != null) {
                val adbSuccess = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        val keyPairFile = java.io.File(context.filesDir, "adbkey")
                        val pubFile = java.io.File(context.filesDir, "adbkey.pub")
                        if (keyPairFile.exists() && pubFile.exists()) {
                            val kp = dadb.AdbKeyPair.read(keyPairFile, pubFile)
                            dadb.Dadb.create(targetIp, 5555, kp).use { dadb ->
                                val pkgCandidate = if (!shortcut.deepLink.startsWith("http") && !shortcut.deepLink.contains("/")) {
                                    shortcut.deepLink
                                } else {
                                    com.remote.control.data.apps.TvAppCatalog.POPULAR_APPS.find {
                                        it.title.equals(shortcut.title, ignoreCase = true)
                                    }?.packageNameOrAppId
                                }
                                if (!pkgCandidate.isNullOrBlank()) {
                                    Logger.log("HomeViewModel.launchShortcut: ADB запуск monkey -p $pkgCandidate")
                                    dadb.shell("monkey -p $pkgCandidate -c android.intent.category.LAUNCHER 1")
                                    true
                                } else {
                                    false
                                }
                            }
                        } else false
                    } catch (e: Exception) {
                        Logger.log("HomeViewModel.launchShortcut: ADB запуск не удался (${e.message})")
                        false
                    }
                }
                if (adbSuccess) return@launch
            }

            if (shortcut.deepLink.isNotBlank()) {
                Logger.log("Запуск ярлыка приложения: ${shortcut.title} (${shortcut.deepLink})")
                val msg = RemoteMessage.newBuilder()
                    .setRemoteAppLinkLaunchRequest(
                        RemoteAppLinkLaunchRequest.newBuilder()
                            .setAppLink(shortcut.deepLink)
                            .build()
                    )
                    .build()
                connectionManager.sendMessage(msg)
            }
        }
    }
}
