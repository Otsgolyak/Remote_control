package com.remote.control.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.remote.control.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class AirMouseController(context: Context) : SensorEventListener {

    companion object {
        // Строгий 50-байтовый HID Report Descriptor мыши (3 кнопки + 5 бит padding + X, Y, Wheel)
        val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
            // Кнопки (ЛКМ, ПКМ, СКМ)
            0x05.toByte(), 0x09.toByte(), // Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), // Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(), // Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), // Report Count (3)
            0x75.toByte(), 0x01.toByte(), // Report Size (1)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
            // Отступ (Padding) 5 бит для выравнивания байта
            0x95.toByte(), 0x01.toByte(), // Report Count (1)
            0x75.toByte(), 0x05.toByte(), // Report Size (5)
            0x81.toByte(), 0x03.toByte(), // Input (Constant, Variable, Absolute)
            // Оси X, Y и колесо прокрутки
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), // Usage (X)
            0x09.toByte(), 0x31.toByte(), // Usage (Y)
            0x09.toByte(), 0x38.toByte(), // Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x95.toByte(), 0x03.toByte(), // Report Count (3)
            0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative)
            0xC0.toByte(),                // End Collection (Physical)
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

    private var filteredGyroX = 0f
    private var filteredGyroZ = 0f
    private var activeButtonsMask = 0
    private var accumX = 0f
    private var accumY = 0f
    private var lastEventTime = 0L
    private val debounceMs = 150L

    private val _isBtConnected = MutableStateFlow(false)
    val isBtConnected: StateFlow<Boolean> = _isBtConnected.asStateFlow()

    private val _isGyroEnabled = MutableStateFlow(false)
    val isGyroEnabled: StateFlow<Boolean> = _isGyroEnabled.asStateFlow()

    private val _btStatus = MutableStateFlow("Режим Wi-Fi тачпада / Курсор на ТВ")
    val btStatus: StateFlow<String> = _btStatus.asStateFlow()

    var onFallbackDirection: ((dx: Float, dy: Float) -> Unit)? = null
    var onVisualCursorDelta: ((dx: Float, dy: Float) -> Unit)? = null
    var onCompanionMove: ((dx: Float, dy: Float) -> Boolean)? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as BluetoothHidDevice
                Logger.log("AirMouse: HID Service Connected")
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = null
                connectedHost = null
                _isBtConnected.value = false
                _btStatus.value = "Bluetooth HID сервис отключен"
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Logger.log("AirMouse: HID registered=$registered, plugged=${pluggedDevice?.name}")
            if (!registered) {
                _isBtConnected.value = false
                connectedHost = null
                _btStatus.value = "HID регистрация снята"
                return
            }

            _btStatus.value = "HID профиль активен"
            val hid = bluetoothHidDevice ?: return
            if (pluggedDevice != null) {
                hid.connect(pluggedDevice)
            } else {
                bluetoothAdapter?.bondedDevices?.forEach { device ->
                    Logger.log("AirMouse: Попытка BT HID подключения к ${device.name}")
                    hid.connect(device)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Logger.log("AirMouse: BT HID state=$state (${device?.name})")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    _isBtConnected.value = true
                    _btStatus.value = "BT Курсор подключен к ${device?.name ?: "Android TV"}"
                    // Триггер отрисовки: отправляем первое ненулевое смещение, чтобы ТВ сразу показал системный курсор
                    sendMouseDelta(1, 1)
                    sendMouseDelta(-1, -1)
                    Logger.log("BT HID Мышь подключена к ${device?.name}! Отправлен wake-up импульс для включения курсора на ТВ")
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
            Logger.log("AirMouse: Bluetooth выключен или недоступен")
            return
        }
        _btStatus.value = "Запуск Bluetooth HID..."
        adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val hid = bluetoothHidDevice ?: return
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Xiaomi TV Air Mouse",
            "Android TV Bluetooth HID Mouse",
            "Xiaomi",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            MOUSE_REPORT_DESCRIPTOR
        )
        hid.registerApp(sdpSettings, null, null, hidExecutor, hidCallback)
    }

    fun connectToDevice(device: BluetoothDevice) {
        val hid = bluetoothHidDevice
        if (hid != null) {
            Logger.log("AirMouse: Попытка подключения к ${device.name} (${device.address})")
            hid.connect(device)
        } else {
            Logger.log("AirMouse: BluetoothHidDevice не инициализирован")
        }
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
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
                filteredGyroX = 0f
                filteredGyroZ = 0f
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

        // Фильтр нижних частот (Low-Pass Filter) для плавного движения курсора без дрожания
        filteredGyroZ += LOW_PASS_ALPHA * (rawYawZ - filteredGyroZ)
        filteredGyroX += LOW_PASS_ALPHA * (rawPitchX - filteredGyroX)

        val deltaX = if (abs(filteredGyroZ) > GYRO_DEADZONE) {
            (-filteredGyroZ * BASE_GYRO_MULTIPLIER * sensitivity).roundToInt()
        } else 0

        val deltaY = if (abs(filteredGyroX) > GYRO_DEADZONE) {
            (-filteredGyroX * BASE_GYRO_MULTIPLIER * sensitivity).roundToInt()
        } else 0

        if (deltaX != 0 || deltaY != 0) {
            onVisualCursorDelta?.invoke(deltaX.toFloat(), deltaY.toFloat())

            // 1. Нативный Bluetooth HID курсор (приоритет)
            if (_isBtConnected.value && connectedHost != null) {
                sendMouseDelta(deltaX, deltaY)
                return
            }

            // 2. Wi-Fi курсор-компаньон (если активен)
            if (onCompanionMove?.invoke(deltaX.toFloat(), deltaY.toFloat()) == true) {
                return
            }

            // 3. Фоллбэк на D-Pad (если Bluetooth HID еще не подключен)
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
        hid.sendReport(device, 0, report)
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

        if (onCompanionMove?.invoke(dx * sensitivity, dy * sensitivity) == true) {
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
        val hid = bluetoothHidDevice ?: return
        val device = connectedHost ?: return
        if (!_isBtConnected.value) return

        val buttonMask: Byte = if (leftButton) 0x01 else 0x02
        hid.sendReport(device, 0, byteArrayOf(buttonMask, 0x00, 0x00, 0x00))
        hid.sendReport(device, 0, byteArrayOf(0x00, 0x00, 0x00, 0x00))
    }

    fun sendScroll(wheelDelta: Int) {
        sendMouseDelta(0, 0, wheel = wheelDelta.coerceIn(-127, 127))
    }

    fun release() {
        setGyroEnabled(false)
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
