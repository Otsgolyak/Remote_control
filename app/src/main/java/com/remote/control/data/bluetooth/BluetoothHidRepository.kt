package com.remote.control.data.bluetooth

/**
 * Interface for Bluetooth HID operations, including gyroscope input.
 */
interface BluetoothHidRepository {
    fun connect()
    fun disconnect()
    fun sendGyroscopeEvent(x: Float, y: Float, z: Float)
    fun sendKeyEvent(keyCode: Int, pressed: Boolean)
}
