package com.remote.control.data.bluetooth

import android.hardware.SensorManager

class BluetoothHidRepositoryImpl(
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
        val report = createGyroReport(x, y, z)
        bluetoothHidDevice.sendReport(report)
    }

    override fun sendKeyEvent(keyCode: Int, pressed: Boolean) {
        val report = byteArrayOf(0x02, if (pressed) 0x01 else 0x00, keyCode.toByte())
        bluetoothHidDevice.sendReport(report)
    }

    private fun createGyroReport(x: Float, y: Float, z: Float): ByteArray {
        return byteArrayOf(
            0x01,
            x.toInt().coerceIn(-127, 127).toByte(),
            y.toInt().coerceIn(-127, 127).toByte(),
            z.toInt().coerceIn(-127, 127).toByte()
        )
    }
}
