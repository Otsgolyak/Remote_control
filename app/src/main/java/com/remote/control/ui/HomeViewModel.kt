package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.remote.control.data.device.DeviceRepository
import com.remote.control.data.device.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

data class RemoteState(
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val apps: List<String> = emptyList(),
    val selectedDeviceIp: String? = null
)

class HomeViewModel(private val repository: DeviceRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteState())
    val uiState: StateFlow<RemoteState> = _uiState.asStateFlow()

    val pairedDevices: StateFlow<List<Device>> = repository.pairedDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun selectDevice(ip: String) {
        _uiState.value = _uiState.value.copy(selectedDeviceIp = ip)
    }

    init {
        _uiState.value = RemoteState(
            isConnected = true,
            status = "Connected to TV",
            apps = listOf("YouTube", "Netflix", "Disney+", "Prime Video")
        )
    }
}
