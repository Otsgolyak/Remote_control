package com.remote.control.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null

    @Volatile
    var sensitivity: Float = 1.5f

    private val _isBtConnected = MutableStateFlow(false)
    val isBtConnected: StateFlow<Boolean> = _isBtConnected.asStateFlow()

    private val _isGyroEnabled = MutableStateFlow(false)
    val isGyroEnabled: StateFlow<Boolean> = _isGyroEnabled.asStateFlow()

    private val _btStatus = MutableStateFlow("Режим Wi-Fi тачпада (Для курсора на ТВ нажмите «Курсор BT»)")
    val btStatus: StateFlow<String> = _btStatus.asStateFlow()

    var onFallbackDirection: ((dx: Float, dy: Float) -> Unit)? = null
    var onVisualCursorDelta: ((dx: Float, dy: Float) -> Unit)? = null

    private val mouseDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x02.toByte(), 0xA1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x01.toByte(), 0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(), 0x29.toByte(), 0x03.toByte(),
        0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(), 0x81.toByte(), 0x02.toByte(), 0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x05.toByte(), 0x81.toByte(), 0x01.toByte(), 0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(), 0x09.toByte(), 0x31.toByte(), 0x09.toByte(), 0x38.toByte(),
        0x15.toByte(), 0x81.toByte(), 0x25.toByte(), 0x7F.toByte(), 0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x03.toByte(), 0x81.toByte(), 0x06.toByte(), 0xC0.toByte(), 0xC0.toByte()
    )

    fun initBluetoothHid() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        adapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHidApp()
                }
            }
            override fun onServiceDisconnected(profile: Int) { hidDevice = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings("Xiaomi TV Air Mouse", "Android TV Remote HID Mouse", "Xiaomi Remote", BluetoothHidDevice.SUBCLASS1_MOUSE, mouseDescriptor)
        hid.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered && pluggedDevice != null) hid.connect(pluggedDevice)
            }
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                val connected = state == BluetoothProfile.STATE_CONNECTED
                _isBtConnected.value = connected
                connectedHost = if (connected) device else null
            }
        })
    }

    fun setGyroEnabled(enabled: Boolean) {
        _isGyroEnabled.value = enabled
        if (enabled) {
            val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensorManager.unregisterListener(this)
        }
    }

    private var accumX = 0f
    private var accumY = 0f
    private var lastEventTime = 0L
    private val debounceMs = 150L

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE || !_isGyroEnabled.value) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < debounceMs) return
        val rawDx = -event.values[2] * 8f * sensitivity
        val rawDy = -event.values[0] * 8f * sensitivity
        
        if (abs(rawDx) > 1.5f || abs(rawDy) > 1.5f) {
            lastEventTime = currentTime
            if (_isBtConnected.value && connectedHost != null) {
                sendMouseDelta(rawDx, rawDy, 0, 0)
            } else {
                if (abs(rawDx) > abs(rawDy)) onFallbackDirection?.invoke(if (rawDx > 0) 10f else -10f, 0f)
                else onFallbackDirection?.invoke(0f, if (rawDy > 0) 10f else -10f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun sendMouseDelta(dx: Float, dy: Float, wheel: Int = 0, buttons: Int = 0) {
        val scaledDx = dx * sensitivity
        val scaledDy = dy * sensitivity
        val hid = hidDevice
        val host = connectedHost

        if (hid != null && host != null && _isBtConnected.value) {
            val bx = scaledDx.roundToInt().coerceIn(-127, 127).toByte()
            val by = scaledDy.roundToInt().coerceIn(-127, 127).toByte()
            val bw = wheel.coerceIn(-127, 127).toByte()
            hid.sendReport(host, 1, byteArrayOf(buttons.toByte(), bx, by, bw))
        } else {
            accumX += scaledDx
            accumY += scaledDy
            val threshold = 75f
            if (abs(accumX) >= threshold || abs(accumY) >= threshold) {
                onFallbackDirection?.invoke(accumX, accumY)
                accumX = 0f
                accumY = 0f
            }
        }
    }

    fun sendClick(leftButton: Boolean = true) {
        val hid = hidDevice
        val host = connectedHost
        if (hid != null && host != null && _isBtConnected.value) {
            val btnMask = if (leftButton) 1 else 2
            hid.sendReport(host, 1, byteArrayOf(btnMask.toByte(), 0, 0, 0))
            hid.sendReport(host, 1, byteArrayOf(0, 0, 0, 0))
        }
    }

    fun makeDiscoverable(context: Context) {
        try {
            val intent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) { Logger.log("BT: ${e.message}") }
    }
}
