package com.remote.control.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteState(
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val apps: List<String> = emptyList()
)

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteState())
    val uiState: StateFlow<RemoteState> = _uiState.asStateFlow()

    init {
        // Simulate initial state
        _uiState.value = RemoteState(
            isConnected = true,
            status = "Connected to TV",
            apps = listOf("YouTube", "Netflix", "Disney+", "Prime Video")
        )
    }
}
