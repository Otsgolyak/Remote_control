package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remote.control.data.ConnectionManager
import com.remote.control.data.bluetooth.AirMouseController
import com.remote.control.data.device.CustomShortcutData
import com.remote.control.data.device.Device
import com.remote.control.data.device.DeviceRepository
import com.remote.control.data.wifi.DiscoveredTv
import com.remote.control.data.wifi.NsdScanner
import com.remote.control.data.wifi.PairingRepository
import com.remote.control.utils.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import remote.RemoteAppLinkLaunchRequest
import remote.RemoteKeyCode
import remote.RemoteMessage
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
    val isGyroEnabled: Boolean = false,
    val isBtMouseConnected: Boolean = false,
    val btMouseStatus: String = "Тачпад (Wi-Fi) / Курсор BT HID",
    val apps: List<AppShortcut> = defaultShortcuts,
    val mouseSensitivity: Float = 1.5f,
    val cursorPosition: Pair<Float, Float> = Pair(0f, 0f)
)

class HomeViewModel(
    private val repository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val pairingRepository: PairingRepository,
    private val nsdScanner: NsdScanner,
    private val airMouseController: AirMouseController
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteState())
    val uiState: StateFlow<RemoteState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    val pairedDevices: StateFlow<List<Device>> = repository.pairedDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        airMouseController.onFallbackDirection = { dx, dy ->
            if (abs(dx) > abs(dy)) {
                sendKey(if (dx > 0) RemoteKeyCode.KEYCODE_DPAD_RIGHT else RemoteKeyCode.KEYCODE_DPAD_LEFT)
            } else {
                sendKey(if (dy > 0) RemoteKeyCode.KEYCODE_DPAD_DOWN else RemoteKeyCode.KEYCODE_DPAD_UP)
            }
        }

        airMouseController.onVisualCursorDelta = { dx, dy ->
            val current = _uiState.value.cursorPosition
            val newX = (current.first + dx * 0.4f).coerceIn(-120f, 120f)
            val newY = (current.second + dy * 0.4f).coerceIn(-65f, 65f)
            _uiState.value = _uiState.value.copy(cursorPosition = Pair(newX, newY))
        }

        viewModelScope.launch {
            connectionManager.isConnected.collect { connected ->
                val ip = _uiState.value.selectedDeviceIp
                _uiState.value = _uiState.value.copy(
                    isConnected = connected,
                    status = when {
                        connected && ip != null -> "Подключено ($ip)"
                        ip != null -> "Отключено ($ip)"
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
                _uiState.value = _uiState.value.copy(
                    selectedDeviceIp = savedDevice.ip,
                    selectedDeviceName = savedDevice.name
                )
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
        airMouseController.sendMouseDelta(dx, dy)
    }

    fun startDiscovery() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = true)
        scanJob = viewModelScope.launch {
            try {
                nsdScanner.discover().collect { list ->
                    _uiState.value = _uiState.value.copy(
                        discoveredDevices = list,
                        isScanning = true
                    )
                }
            } catch (e: Exception) {
                Logger.log("Ошибка сканирования сети: ${e.message}")
                _uiState.value = _uiState.value.copy(isScanning = false)
            }
        }
    }

    fun connectToSavedDevice(device: Device, onConnected: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selectedDeviceName = device.name,
                selectedDeviceIp = device.ip,
                isConnecting = true,
                pairingError = null,
                status = "Подключение к ${device.ip}..."
            )
            repository.setSelectedDevice(device.ip)
            try {
                connectionManager.connect(device.ip, 6466)
                delay(400)
                if (connectionManager.isConnected.value) {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        status = "Подключено (${device.ip})"
                    )
                    onConnected()
                } else {
                    Logger.log("ТВ ${device.ip} требует сопряжения по PIN-коду, запуск Pairing...")
                    initiatePairing(device.name, device.ip)
                }
            } catch (e: Exception) {
                Logger.log("Прямое подключение к ${device.ip}:6466 не удалось (${e.message}), пробуем сопряжение 6467...")
                initiatePairing(device.name, device.ip)
            }
        }
    }

    fun initiatePairing(name: String, ip: String) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
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
                    val device = Device(ip = ip, name = name)
                    repository.addDevice(device)
                    _uiState.value = _uiState.value.copy(
                        isPairingPinRequired = false,
                        selectedDeviceIp = ip,
                        selectedDeviceName = name,
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

    fun removeSavedDevice(ip: String) {
        viewModelScope.launch {
            repository.removeDevice(ip)
            if (_uiState.value.selectedDeviceIp == ip) {
                connectionManager.disconnect()
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

import java.util.concurrent.atomic.AtomicBoolean

class HomeViewModel(...) : ViewModel() {
    private val isConnecting = AtomicBoolean(false)

    fun sendKey(keyCode: RemoteKeyCode) {
        viewModelScope.launch {
            if (!connectionManager.isConnected.value) {
                if (isConnecting.compareAndSet(false, true)) {
                    try {
                        val ip = _uiState.value.selectedDeviceIp ?: return@launch
                        Logger.log("Попытка подключения к $ip...")
                        connectionManager.connect(ip, 6466)
                    } catch (e: Exception) {
                        Logger.log("Ошибка подключения: ${e.message}")
                    } finally {
                        isConnecting.set(false)
                    }
                }
            }
            if (connectionManager.isConnected.value) {
                connectionManager.sendKey(keyCode)
            }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            if (!connectionManager.isConnected.value) {
                val ip = _uiState.value.selectedDeviceIp ?: return@launch
                runCatching { connectionManager.connect(ip, 6466) }
            }
            connectionManager.sendText(text)
        }
    }

    fun sendTextAsKeys(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            for (ch in text) {
                connectionManager.sendCharKey(ch)
                delay(55)
            }
        }
    }

    fun toggleVoice() {
        viewModelScope.launch {
            // Если голос уже активен - просто стопаем, не переподключаемся
            if (connectionManager.isVoiceActive.value) {
                connectionManager.stopVoiceStream()
                return@launch
            }

            // Если не подключены - подключаемся один раз
            if (!connectionManager.isConnected.value) {
                val ip = _uiState.value.selectedDeviceIp
                if (ip != null) {
                    runCatching { connectionManager.connect(ip, 6466) }
                }
            }
            
            // Если после попытки подключения (или если были подключены) соединение активно - запускаем
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

    fun setGyroEnabled(enabled: Boolean) {
        airMouseController.setGyroEnabled(enabled)
    }

    fun onTouchpadMove(dx: Float, dy: Float) {
        airMouseController.sendMouseDelta(dx, dy)
    }

    fun onMouseClick(left: Boolean) {
        if (airMouseController.isBtConnected.value) {
            airMouseController.sendClick(left)
        } else {
            sendKey(if (left) RemoteKeyCode.KEYCODE_DPAD_CENTER else RemoteKeyCode.KEYCODE_BACK)
        }
    }

    fun onMouseScroll(wheel: Int) {
        if (airMouseController.isBtConnected.value) {
            airMouseController.sendMouseDelta(0f, 0f, wheel = wheel)
        } else {
            sendKey(if (wheel > 0) RemoteKeyCode.KEYCODE_DPAD_UP else RemoteKeyCode.KEYCODE_DPAD_DOWN)
        }
    }

    fun launchShortcut(shortcut: AppShortcut) {
        viewModelScope.launch {
            if (shortcut.fallbackKey != null) {
                sendKey(shortcut.fallbackKey)
                return@launch
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
