package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remote.control.data.ConnectionManager
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

data class AppShortcut(
    val title: String,
    val deepLink: String,
    val fallbackKey: RemoteKeyCode? = null
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
    val apps: List<AppShortcut> = listOf(
        AppShortcut("YouTube", "https://www.youtube.com"),
        AppShortcut("Netflix", "https://www.netflix.com/title"),
        AppShortcut("Prime Video", "https://app.primevideo.com"),
        AppShortcut("Настройки ТВ", "", RemoteKeyCode.KEYCODE_SETTINGS),
        AppShortcut("Все приложения", "", RemoteKeyCode.KEYCODE_ALL_APPS)
    )
)

class HomeViewModel(
    private val repository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val pairingRepository: PairingRepository,
    private val nsdScanner: NsdScanner
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
        viewModelScope.launch {
            connectionManager.isConnected.collect { connected ->
                val ip = _uiState.value.selectedDeviceIp
                val name = _uiState.value.selectedDeviceName
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

        // Auto-select last saved TV on startup
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
                    // TV closed socket -> requires pairing on 6467
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

    fun renameDevice(ip: String, newName: String) {
        viewModelScope.launch {
            repository.renameDevice(ip, newName)
            if (_uiState.value.selectedDeviceIp == ip) {
                _uiState.value = _uiState.value.copy(selectedDeviceName = newName)
            }
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

    fun sendKey(keyCode: RemoteKeyCode) {
        viewModelScope.launch {
            if (!connectionManager.isConnected.value) {
                val ip = _uiState.value.selectedDeviceIp
                if (ip != null) {
                    Logger.log("Переподключение к $ip перед отправкой ${keyCode.name}...")
                    runCatching { connectionManager.connect(ip, 6466) }
                } else {
                    Logger.log("Невозможно отправить ${keyCode.name}: ТВ не выбран")
                    return@launch
                }
            }
            connectionManager.sendKey(keyCode)
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
