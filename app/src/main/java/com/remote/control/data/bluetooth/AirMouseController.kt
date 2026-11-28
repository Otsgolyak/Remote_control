package com.remote.control.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.remote.control.domain.sensor.AirMouseMotionProcessor
import com.remote.control.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

data class DiscoveredBluetoothDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

@SuppressLint("MissingPermission")
class AirMouseController(context: Context) : SensorEventListener {

    companion object {
        private const val MOUSE_REPORT_ID = 1

        // Строгий HID Report Descriptor мыши со стандартизированным Report ID: 1
        // (3 кнопки + 5 бит padding + X, Y, Wheel) для полной совместимости с Android TV / Linux uhid
        val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1) — обязательно для корректной регистрации в SurfaceFlinger/uhid
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            // Кнопки (ЛКМ, ПКМ, СКМ)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            // Отступ (Padding) 5 бит для выравнивания байта
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x03.toByte(), //     Input (Constant, Variable, Absolute)
            // Оси X, Y и колесо прокрутки
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection (Physical)
            0xC0.toByte()                 // End Collection (Application)
        )

        private const val LOW_PASS_ALPHA = 0.25f
        private const val GYRO_DEADZONE = 0.03f
        private const val BASE_GYRO_MULTIPLIER = 18.0f
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val hidExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var bluetoothHidDevice: BluetoothHidDevice? = null

    @Volatile
    private var connectedHost: BluetoothDevice? = null

    @Volatile
    var sensitivity: Float = 1.5f

    private val motionProcessor = AirMouseMotionProcessor()
    private var activeButtonsMask = 0
    private var accumX = 0f
    private var accumY = 0f
    private var lastEventTime = 0L
    private val debounceMs = 150L

    private val _isBtConnected = MutableStateFlow(false)
    val isBtConnected: StateFlow<Boolean> = _isBtConnected.asStateFlow()

    private val _isGyroEnabled = MutableStateFlow(false)
    val isGyroEnabled: StateFlow<Boolean> = _isGyroEnabled.asStateFlow()

    private val _btStatus = MutableStateFlow("Ожидание Bluetooth HID")
    val btStatus: StateFlow<String> = _btStatus.asStateFlow()

    // Хранилище обнаруженных Bluetooth-устройств с замером мощности сигнала (RSSI)
    private val discoveredDevicesMap = ConcurrentHashMap<String, Pair<BluetoothDevice, Int>>()
    private val _discoveredDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val discoveredDevices: StateFlow<List<Pair<String, String>>> = _discoveredDevices.asStateFlow()

    private val _isBtScanning = MutableStateFlow(false)
    val isBtScanning: StateFlow<Boolean> = _isBtScanning.asStateFlow()

    private val _discoveredBtDevices = MutableStateFlow<List<DiscoveredBluetoothDevice>>(emptyList())
    val discoveredBtDevices: StateFlow<List<DiscoveredBluetoothDevice>> = _discoveredBtDevices.asStateFlow()

    private val _btPairingPromptDevice = MutableStateFlow<String?>(null)
    val btPairingPromptDevice: StateFlow<String?> = _btPairingPromptDevice.asStateFlow()

    @Volatile
    private var pendingBondDevice: BluetoothDevice? = null

    private val controllerScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanTimeoutJob = null as Job?
    private var isReceiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return

                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val devName = device.name ?: "Неизвестное ТВ"
                    val address = device.address ?: return

                    discoveredDevicesMap[address] = Pair(device, rssi)
                    updateDiscoveredList()
                    Logger.log("BT Сканер: найдено устройство \"$devName\" [$address] (RSSI=$rssi dBm)")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isBtScanning.value = false
                    Logger.log("BT Сканер: сканирование эфира завершено, всего найдено ${discoveredDevicesMap.size} устройств")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return

                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val devName = device.name ?: device.address ?: "ТВ"
                    Logger.log("BT Сопряжение: статус сменился для \"$devName\" [$prevBondState -> $bondState]")

                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Logger.log("BT Сопряжение: успешно выполнено с \"$devName\"! Немедленное подключение HID...")
                            _btPairingPromptDevice.value = null
                            _btStatus.value = "Сопряжение успешно! Подключение HID..."
                            connectToDevice(device)
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            _btStatus.value = "Выполняется сопряжение с $devName..."
                            Logger.log("BT Сопряжение: в процессе подтверждения на ТВ/телефоне для \"$devName\"")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                Logger.log("BT Сопряжение: сопряжение отменено или отклонено для \"$devName\"")
                                _btStatus.value = "Сопряжение отменено"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateDiscoveredList() {
        // Сортировка по уровню сигнала: от наибольшего (ближайший ТВ) к наименьшему
        val sortedList = discoveredDevicesMap.values
            .sortedByDescending { it.second }
            .map { (dev, rssi) ->
                val name = dev.name ?: "Bluetooth Device"
                DiscoveredBluetoothDevice(
                    device = dev,
                    name = name,
                    address = dev.address ?: "",
                    rssi = rssi
                )
            }
        _discoveredBtDevices.value = sortedList
        _discoveredDevices.value = sortedList.map { "${it.name} (${it.rssi} dBm)" to it.address }
    }

    var onFallbackDirection: ((dx: Float, dy: Float) -> Unit)? = null
    var onVisualCursorDelta: ((dx: Float, dy: Float) -> Unit)? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as BluetoothHidDevice
                Logger.log("BT HID: ServiceListener.onServiceConnected(HID_DEVICE)")
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Logger.log("BT HID: ServiceListener.onServiceDisconnected(HID_DEVICE)")
                bluetoothHidDevice = null
                connectedHost = null
                _isBtConnected.value = false
                _btStatus.value = "Bluetooth HID сервис отключен"
            }
        }
    }

    @Volatile
    private var isAppRegistered = false

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Logger.log("BT HID: onAppStatusChanged(registered=$registered, plugged=${pluggedDevice?.name}[${pluggedDevice?.address}])")
            isAppRegistered = registered
            if (!registered) {
                _isBtConnected.value = false
                connectedHost = null
                _btStatus.value = "HID регистрация снята"
                return
            }

            _btStatus.value = "HID профиль активен"
            val hid = bluetoothHidDevice ?: return
            if (pluggedDevice != null) {
                val connOk = hid.connect(pluggedDevice)
                Logger.log("BT HID: onAppStatusChanged connect(plugged=${pluggedDevice.name}[${pluggedDevice.address}]) -> result=$connOk")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val stateName = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "STATE_CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "STATE_CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
                else -> "UNKNOWN($state)"
            }
            Logger.log("BT HID: onConnectionStateChanged device=${device?.name}[${device?.address}], state=$stateName ($state)")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    _isBtConnected.value = true
                    _btStatus.value = "BT Курсор подключен к ${device?.name ?: "Android TV"}"
                    // Триггер отрисовки: отправляем первое ненулевое смещение, чтобы ТВ сразу показал системный курсор
                    val hid = bluetoothHidDevice
                    val wake1 = if (hid != null && device != null) {
                        hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(0, 1, 1, 0))
                    } else false
                    val wake2 = if (hid != null && device != null) {
                        hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(0, (-1).toByte(), (-1).toByte(), 0))
                    } else false
                    Logger.log("BT HID: Мышь подключена к ${device?.name}[${device?.address}]! Wake-up sendReport -> wake1=$wake1, wake2=$wake2")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _btStatus.value = "Подключение к ${device?.name ?: "ТВ"}..."
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                        _isBtConnected.value = false
                        _btStatus.value = "Ожидание BT сопряжения с ТВ"
                    }
                }
            }
        }
    }

    fun initBluetoothHid() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _btStatus.value = "Включите Bluetooth на смартфоне"
            Logger.log("BT HID: initBluetoothHid() — адаптер недоступен или выключен (adapter=${adapter != null}, enabled=${adapter?.isEnabled})")
            return
        }

        registerReceiverIfNeeded()

        val bondedList = adapter.bondedDevices?.joinToString { "${it.name}[${it.address}]" } ?: "none"
        Logger.log("BT HID: initBluetoothHid() — адаптер включен (state=${adapter.state}), bondedDevices=[$bondedList]")
        _btStatus.value = "Запуск Bluetooth HID..."
        val proxyOk = adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        Logger.log("BT HID: getProfileProxy(HID_DEVICE) -> result=$proxyOk")
    }

    private fun registerReceiverIfNeeded() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            appContext.registerReceiver(discoveryReceiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun registerHidApp() {
        val hid = bluetoothHidDevice ?: return
        if (isAppRegistered) {
            Logger.log("BT HID: registerHidApp() пропущено — приложение уже зарегистрировано")
            return
        }
        val deviceModel = Build.MODEL ?: "Phone"
        val appName = "$deviceModel (Air Mouse)"
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            appName,
            "Bluetooth HID Mouse for Android TV",
            Build.MANUFACTURER ?: "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            MOUSE_REPORT_DESCRIPTOR
        )
        try {
            hid.unregisterApp()
        } catch (_: Exception) {}

        // Даем небольшую паузу системному стеку Bluetooth для завершения предыдущей регистрации
        controllerScope.launch {
            delay(150L)
            var regResult = false
            try {
                regResult = hid.registerApp(sdpSettings, null, null, hidExecutor, hidCallback)
            } catch (e: Exception) {
                Logger.log("BT HID: registerApp исключение: ${e.message}")
            }
            Logger.log("BT HID: registerApp вызван для \"$appName\" -> result=$regResult")

            // Если не удалось зарегистрироваться с ходу (специфика HyperOS / Android 14-16) — повторяем через 500мс
            if (!regResult && !isAppRegistered) {
                delay(500L)
                try {
                    val retry = hid.registerApp(sdpSettings, null, null, hidExecutor, hidCallback)
                    Logger.log("BT HID: registerApp повторная попытка -> result=$retry")
                } catch (e2: Exception) {
                    Logger.log("BT HID: registerApp retry исключение: ${e2.message}")
                }
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        val hid = bluetoothHidDevice
        if (hid != null) {
            val ok = hid.connect(device)
            Logger.log("BT HID: вызов connect(${device.name} / ${device.address}) -> result=$ok")
        } else {
            Logger.log("BT HID еще не готов, запуск инициализации для подключения к ${device.address}")
            initBluetoothHid()
        }
    }

    fun connectToAddress(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Logger.log("BT HID: connectToAddress($address) — Bluetooth адаптер выключен или недоступен")
            return
        }
        val device = try {
            adapter.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: adapter.getRemoteDevice(address.uppercase())
        } catch (e: Exception) {
            Logger.log("BT HID: ошибка поиска устройства по адресу $address: ${e.message}")
            null
        }
        if (device == null) {
            Logger.log("BT HID: устройство с адресом $address не найдено")
            return
        }
        if (bluetoothHidDevice == null) {
            Logger.log("BT HID еще не готов, запуск инициализации для подключения к $address")
            initBluetoothHid()
        } else {
            val ok = bluetoothHidDevice?.connect(device)
            Logger.log("BT HID: вызов connect(${device.name} / $address) -> result=$ok")
        }
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun dismissPairingPrompt() {
        _btPairingPromptDevice.value = null
    }

    /**
     * Запускает тихое сканирование Bluetooth-эфира с измерением RSSI.
     */
    fun startBluetoothDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _btStatus.value = "Включите Bluetooth"
            return
        }

        initBluetoothHid()
        registerReceiverIfNeeded()

        discoveredDevicesMap.clear()
        _discoveredDevices.value = emptyList()

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        val scanStarted = adapter.startDiscovery()
        _isBtScanning.value = scanStarted
        Logger.log("BT Сканер: запуск startDiscovery() -> result=$scanStarted")
        _btStatus.value = "Поиск Bluetooth ТВ рядом..."

        scanTimeoutJob?.cancel()
        scanTimeoutJob = controllerScope.launch {
            delay(12000L) // 12 секунд активного сканирования
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
                Logger.log("BT Сканер: авто-остановка по тайм-ауту 12с")
            }
            _isBtScanning.value = false
        }
    }

    /**
     * Проверяет, является ли устройство телевизором или медиаприставкой:
     * - Исключает носимые/аудио устройства (watch, band, buds, headset, audio, speaker, charge, major, ble и т.д.).
     * - Ищет совпадение по имени из Wi-Fi (targetTvName) или характерным ТВ-маркерам (xiaomi, mitv, mi tv, samsung, android tv и т.д.).
     * - Важно: не использует одиночную подстроку "mi", чтобы исключить ложные срабатывания (например, TANK MINI_BLE).
     */
    fun isLikelyTv(device: BluetoothDevice, targetTvName: String = ""): Boolean {
        val name = device.name ?: return false
        val clean = name.trim()
        if (clean.isBlank()) return false

        val lower = clean.lowercase()

        // Жестко отсекаем смартфоны, аудио, часы, фитнес-трекеры и BLE-маяки
        if (lower.contains("watch") || lower.contains("band") || lower.contains("buds") ||
            lower.contains("headset") || lower.contains("earphone") || lower.contains("audio") ||
            lower.contains("speaker") || lower.contains("charge") || lower.contains("major") ||
            lower.contains("gtrs") || lower.contains("erazer") || lower.contains("phone") ||
            lower.contains("ultra") || lower.contains("galaxy") || lower.contains("s23") ||
            lower.contains("s24") || lower.contains("s22") || lower.contains("iphone") ||
            lower.contains("tank") || lower.contains("mini") || lower.contains("g9s") ||
            (lower.contains("ble") && !lower.contains("tv"))
        ) {
            return false
        }

        val target = targetTvName.trim()
        if (target.isNotBlank() && !target.equals("TV", ignoreCase = true) && !target.equals("Android TV", ignoreCase = true)) {
            if (clean.contains(target, ignoreCase = true) || target.contains(clean, ignoreCase = true)) {
                return true
            }
        }

        return lower.contains("xiaomi") ||
            lower.contains("mi tv") ||
            lower.contains("mitv") ||
            lower.contains("mi-tv") ||
            lower.contains("mibox") ||
            lower.contains("mi box") ||
            lower.contains("samsung") ||
            lower.contains("tizen") ||
            lower.contains("android tv") ||
            lower.contains("google tv") ||
            lower.contains("smart tv") ||
            lower.contains("atv") ||
            clean.equals("TV", ignoreCase = true) ||
            Regex("""\b(tv|appletv|firetv)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)
    }

    /**
     * Тихо находит ТВ в эфире:
     * 1) Сначала проверяет сопряженные устройства.
     * 2) Если ТВ среди них нет — запускает тихое сканирование эфира по уровню сигнала RSSI.
     * 3) Перебирает найденные ТВ-кандидаты от самого сильного сигнала (ближайший ТВ) к меньшему,
     *    выполняя тихое сопряжение (createBond) и HID-подключение.
     */
    fun autoConnectStrongestTv(targetTvName: String = "", onCandidateTried: ((String) -> Unit)? = null) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        initBluetoothHid()

        // Сначала проверяем уже сопряженные устройства (bonded), отсеивая посторонние (часы, колонки и т.п.)
        val bondedTvs = adapter.bondedDevices.orEmpty().filter { dev ->
            isLikelyTv(dev, targetTvName)
        }

        if (bondedTvs.isNotEmpty()) {
            val target = bondedTvs.first()
            Logger.log("BT Автоподключение: найден уже спаренный ТВ \"${target.name}\" [${target.address}], подключаем...")
            _btStatus.value = "Подключение к спаренному ТВ (${target.name})..."
            connectToDevice(target)
            return
        }

        // Если в сопряженных ТВ нет — тихо запускаем сканирование эфира
        Logger.log("BT Автоподключение: в сопряженных ТВ не найден, запускаем тихое сканирование эфира по уровню RSSI...")
        startBluetoothDiscovery()

        controllerScope.launch {
            _btStatus.value = "Поиск ТВ рядом по уровню сигнала..."
            delay(4000L) // Даем 4 секунды для сбора пакетов RSSI

            // Сортируем: ТВ-кандидаты первыми, внутри по убыванию мощности RSSI (сильнейший = ближайший ТВ)
            // Исключаем очень слабые сигналы (< -80 dBm, это соседи или помехи)
            val candidates = discoveredDevicesMap.values
                .filter { (dev, rssi) ->
                    rssi >= -80 && isLikelyTv(dev, targetTvName)
                }
                .sortedByDescending { it.second }
                .map { it.first }

            if (candidates.isEmpty()) {
                Logger.log("BT Автоподключение: подходящие ТВ в эфире не найдены")
                _btStatus.value = "ТВ рядом не найден"
                return@launch
            }

            for (device in candidates) {
                val devName = device.name ?: "TV"
                val devRssi = discoveredDevicesMap[device.address]?.second ?: 0
                Logger.log("BT Автоподключение: кандидат \"$devName\" [${device.address}], сигнал=$devRssi dBm, bondState=${device.bondState}")
                _btStatus.value = "Подключение к $devName ($devRssi dBm)..."
                onCandidateTried?.invoke(devName)

                // Если устройство еще не сопряжено (BOND_NONE / BOND_BONDING):
                // Запускаем createBond() и показываем пользователю уведомление с инструкцией
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Logger.log("BT Автоподключение: устройство \"$devName\" не спарено. Запуск createBond() и показ запроса сопряжения...")
                    _btPairingPromptDevice.value = devName
                    _btStatus.value = "Требуется сопряжение с $devName"
                    pendingBondDevice = device
                    try {
                        val bondStarted = device.createBond()
                        Logger.log("BT createBond() вызван для \"$devName\" -> $bondStarted")
                    } catch (e: Exception) {
                        Logger.log("BT createBond ошибка: ${e.message}")
                    }

                    // Ждем пока устройство перейдет в BOND_BONDED (обработается в BroadcastReceiver)
                    // или пока пользователь подтвердит запрос на ТВ / в приложении (до 60 секунд)
                    for (step in 1..120) { // до 60 секунд ожидания сопряжения
                        delay(500L)
                        if (device.bondState == BluetoothDevice.BOND_BONDED || _isBtConnected.value) {
                            break
                        }
                        if (step % 10 == 0 && device.bondState == BluetoothDevice.BOND_BONDING) {
                            val remainingSec = (120 - step) / 2
                            _btStatus.value = "Ожидание сопряжения на ТВ ($remainingSec сек)..."
                        }
                        // Если пользователь закрыл диалог сопряжения и bondState сбросился
                        if (_btPairingPromptDevice.value == null && device.bondState == BluetoothDevice.BOND_NONE && step > 20) {
                            Logger.log("BT Автоподключение: сопряжение отменено пользователем")
                            break
                        }
                    }

                    if (device.bondState != BluetoothDevice.BOND_BONDED && !_isBtConnected.value) {
                        Logger.log("BT Автоподключение: сопряжение с \"$devName\" не завершено вовремя (тайм-аут 60с)")
                    }
                }

                // Подключаем только если устройство спарено (или пытаемся)
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    connectToDevice(device)

                    // Ожидаем установления соединения
                    for (step in 1..7) {
                        delay(500L)
                        if (_isBtConnected.value) break
                    }
                }

                if (_isBtConnected.value) {
                    Logger.log("BT Автоподключение: успешно подключено к \"$devName\"!")
                    _btPairingPromptDevice.value = null
                    _btStatus.value = "Курсор подключен к $devName"
                    break
                }
            }
        }
    }

    fun makeDiscoverable(context: Context, durationSeconds: Int = 300) {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.log("AirMouse: Запрос видимости Bluetooth ($durationSeconds сек) отправлен")
        } catch (e: Exception) {
            Logger.log("BT ошибка: ${e.message}")
        }
    }

    fun setGyroEnabled(enabled: Boolean) {
        if (enabled) {
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyroSensor != null) {
                motionProcessor.reset()
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
                _isGyroEnabled.value = true
                Logger.log("Гироскоп Air Mouse включен (sensitivity=$sensitivity)")
            } else {
                _btStatus.value = "Гироскоп не поддерживается"
                _isGyroEnabled.value = false
            }
        } else {
            sensorManager.unregisterListener(this)
            _isGyroEnabled.value = false
            Logger.log("Гироскоп Air Mouse выключен")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE || !_isGyroEnabled.value) return

        val rawPitchX = event.values[0]
        val rawYawZ = event.values[2]

        val motion = motionProcessor.process(rawPitchX, rawYawZ, sensitivity)
        val deltaX = motion.intDeltaX
        val deltaY = motion.intDeltaY

        if (deltaX != 0 || deltaY != 0) {
            onVisualCursorDelta?.invoke(motion.deltaX, motion.deltaY)

            // 1. Нативный Bluetooth HID курсор (приоритет)
            if (_isBtConnected.value && connectedHost != null) {
                sendMouseDelta(deltaX, deltaY)
                return
            }

            // 2. Фоллбэк на D-Pad (если Bluetooth HID еще не подключен)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastEventTime >= debounceMs && (abs(deltaX) >= 3 || abs(deltaY) >= 3)) {
                lastEventTime = currentTime
                if (abs(deltaX) > abs(deltaY)) {
                    onFallbackDirection?.invoke(if (deltaX > 0) 10f else -10f, 0f)
                } else {
                    onFallbackDirection?.invoke(0f, if (deltaY > 0) 10f else -10f)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun wakeCursor() {
        val hid = bluetoothHidDevice ?: return
        val device = connectedHost ?: return
        if (!_isBtConnected.value) return
        controllerScope.launch {
            hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(0, 1, 1, 0))
            delay(20L)
            hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(0, (-1).toByte(), (-1).toByte(), 0))
            Logger.log("BT HID: wakeCursor() отправлен для ${device.name}")
        }
    }

    fun sendMouseDelta(dx: Int, dy: Int, wheel: Int = 0, buttons: Int = activeButtonsMask) {
        val hid = bluetoothHidDevice ?: return
        val device = connectedHost ?: return
        if (!_isBtConnected.value) return

        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val clampedWheel = wheel.coerceIn(-127, 127).toByte()

        val report = byteArrayOf(
            buttons.toByte(),
            clampedDx,
            clampedDy,
            clampedWheel
        )
        hid.sendReport(device, MOUSE_REPORT_ID, report)
    }

    fun sendMouseDelta(dx: Float, dy: Float, wheel: Int = 0, buttons: Int = activeButtonsMask) {
        val scaledDx = (dx * sensitivity).roundToInt()
        val scaledDy = (dy * sensitivity).roundToInt()

        if (_isBtConnected.value && connectedHost != null) {
            if (scaledDx != 0 || scaledDy != 0 || wheel != 0 || buttons != 0) {
                sendMouseDelta(scaledDx, scaledDy, wheel, buttons)
            }
            return
        }

        accumX += dx * sensitivity
        accumY += dy * sensitivity
        val threshold = 75f
        if (abs(accumX) >= threshold || abs(accumY) >= threshold) {
            onFallbackDirection?.invoke(accumX, accumY)
            accumX = 0f
            accumY = 0f
        }
    }

    fun sendClick(leftButton: Boolean = true) {
        val hid = bluetoothHidDevice
        val device = connectedHost
        val btnName = if (leftButton) "ЛКМ" else "ПКМ"
        if (hid == null || device == null || !_isBtConnected.value) {
            Logger.log("BT HID: sendClick($btnName) пропущен — HID не подключен (hid=${hid != null}, host=${device?.address}, connected=${_isBtConnected.value})")
            return
        }

        val buttonMask: Byte = if (leftButton) 0x01 else 0x02
        val downOk = hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(buttonMask, 0x00, 0x00, 0x00))
        val upOk = hid.sendReport(device, MOUSE_REPORT_ID, byteArrayOf(0x00, 0x00, 0x00, 0x00))
        Logger.log("BT HID: sendClick($btnName) к ${device.name}[${device.address}] -> down=$downOk, up=$upOk")
    }

    fun sendScroll(wheelDelta: Int) {
        sendMouseDelta(0, 0, wheel = wheelDelta.coerceIn(-127, 127))
    }

    fun release() {
        setGyroEnabled(false)
        if (isReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(discoveryReceiver) }
            isReceiverRegistered = false
        }
        scanTimeoutJob?.cancel()
        val hid = bluetoothHidDevice
        if (hid != null) {
            connectedHost?.let { hid.disconnect(it) }
            hid.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        bluetoothHidDevice = null
        connectedHost = null
        _isBtConnected.value = false
    }
}
