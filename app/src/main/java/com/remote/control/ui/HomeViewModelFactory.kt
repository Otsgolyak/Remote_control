package com.remote.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.remote.control.data.device.DeviceRepository

class HomeViewModelFactory(private val repository: DeviceRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
