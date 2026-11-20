package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.remote.control.data.ConnectionManager
import com.remote.control.data.adb.TvCompanionClient
import com.remote.control.data.bluetooth.AirMouseController
import com.remote.control.data.device.DeviceRepository
import com.remote.control.data.samsung.SamsungConnectionManager
import com.remote.control.data.samsung.SamsungScanner
import com.remote.control.data.wifi.NsdScanner
import com.remote.control.data.wifi.PairingRepository

class HomeViewModelFactory(
    private val repository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val pairingRepository: PairingRepository,
    private val nsdScanner: NsdScanner,
    private val airMouseController: AirMouseController,
    private val samsungConnectionManager: SamsungConnectionManager,
    private val tvCompanionClient: TvCompanionClient,
    private val samsungScanner: SamsungScanner
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                repository,
                connectionManager,
                pairingRepository,
                nsdScanner,
                airMouseController,
                samsungConnectionManager,
                tvCompanionClient,
                samsungScanner
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
