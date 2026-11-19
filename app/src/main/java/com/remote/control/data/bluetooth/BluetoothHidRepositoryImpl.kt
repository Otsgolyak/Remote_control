package com.remote.control.data.bluetooth

import android.hardware.SensorManager
import javax.inject.Inject

/**
 * Implementation of BluetoothHidRepository.
 */
class BluetoothHidRepositoryImpl @Inject constructor(
    private val sensorManager: SensorManager,
    private val bluetoothHidDevice: BluetoothHidDevice
) : BluetoothHidRepository {

    override fun connect() {
        bluetoothHidDevice.registerApp()
    }

    override fun disconnect() {
        bluetoothHidDevice.close()
    }

    override fun sendGyroscopeEvent(x: Float, y: Float, z: Float) {
        // Here we would process sensor data and pack it into an HID report
        val report = createGyroReport(x, y, z)
        bluetoothHidDevice.sendReport(report)
    }

    override fun sendKeyEvent(keyCode: Int, pressed: Boolean) {
        // Send keyboard event via HID
    }

    private fun createGyroReport(x: Float, y: Float, z: Float): ByteArray {
        // Mock implementation of HID report creation
        return byteArrayOf(0x01, x.toInt().toByte(), y.toInt().toByte(), z.toInt().toByte())
    }
}
