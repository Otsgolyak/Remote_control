package com.remote.control.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.control.data.device.Device
import com.remote.control.ui.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    homeViewModel: HomeViewModel,
    onNavigateToHome: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by homeViewModel.uiState.collectAsState()
    val savedDevices by homeViewModel.pairedDevices.collectAsState()

    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("Xiaomi TV") }
    var pinCode by remember { mutableStateOf("") }

    var deviceToRename by remember { mutableStateOf<Device?>(null) }
    var renameText by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color(0xFF0A0A0C),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121216)),
                title = {
                    Text(
                        "Выбор и подключение ТВ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(onClick = { homeViewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить поиск", tint = Color(0xFF00E5FF))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки и логи", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.isConnecting) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Text(state.status, color = Color.White)
                        }
                    }
                }
            }

            state.pairingError?.let { errorText ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF331414)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Ошибка подключения", color = Color(0xFFFF453A), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(errorText, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 1. Saved Paired TVs
            if (savedDevices.isNotEmpty()) {
                item {
                    Text(
                        "Сохраненные ТВ (${savedDevices.size})",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                items(savedDevices) { device ->
                    val isSelected = state.selectedDeviceIp == device.ip && state.isConnected
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF182B24) else Color(0xFF16161E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF34C759) else Color(0xFF2C2C3A),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                homeViewModel.connectToSavedDevice(device) {
                                    onNavigateToHome()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = if (isSelected) Color(0xFF34C759) else Color(0xFF00E5FF)
                                )
                                Column {
                                    Text(device.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("IP: ${device.ip}", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        renameText = device.name
                                        deviceToRename = device
                                    }
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Переименовать", tint = Color(0xFF00E5FF))
                                }
                                TextButton(
                                    onClick = {
                                        homeViewModel.initiatePairing(device.name, device.ip)
                                    }
                                ) {
                                    Text("PIN", color = Color(0xFFFF6D00))
                                }
                                IconButton(
                                    onClick = { homeViewModel.removeSavedDevice(device.ip) }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color(0xFFFF453A))
                                }
                            }
                        }
                    }
                }
            }

            // 2. Discovered TVs in local Wi-Fi network
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Найденные ТВ в сети (${state.discoveredDevices.size})",
                        color = Color(0xFFFF6D00),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (state.isScanning) {
                        Text("Поиск Wi-Fi...", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            if (state.discoveredDevices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Идет поиск Android TV / Xiaomi TV в вашей Wi-Fi сети...",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Убедитесь, что телефон и телевизор подключены к одному роутеру, либо введите IP телевизора вручную ниже.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                items(state.discoveredDevices) { tv ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF181822)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                            .clickable {
                                val existing = savedDevices.find { it.ip == tv.ip }
                                if (existing != null) {
                                    homeViewModel.connectToSavedDevice(existing) {
                                        onNavigateToHome()
                                    }
                                } else {
                                    homeViewModel.initiatePairing(tv.name, tv.ip)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF00E5FF))
                                Column {
                                    Text(tv.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("${tv.ip}:${tv.port}", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    val existing = savedDevices.find { it.ip == tv.ip }
                                    if (existing != null) {
                                        homeViewModel.connectToSavedDevice(existing) {
                                            onNavigateToHome()
                                        }
                                    } else {
                                        homeViewModel.initiatePairing(tv.name, tv.ip)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                            ) {
                                Text("Подключить", color = Color.White)
                            }
                        }
                    }
                }
            }

            // 3. Manual IP Connection
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Ручное подключение по IP",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it },
                            label = { Text("Название ТВ") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("IP-адрес ТВ (например 192.168.68.116)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (manualIp.isNotBlank()) {
                                        homeViewModel.initiatePairing(manualName, manualIp)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                            ) {
                                Text("Сопряжение (PIN)")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (manualIp.isNotBlank()) {
                                        val dev = Device(ip = manualIp.trim(), name = manualName.ifBlank { "Xiaomi TV" })
                                        homeViewModel.connectToSavedDevice(dev) {
                                            onNavigateToHome()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Уже спарен")
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onNavigateToHome,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23232E))
                ) {
                    Text("Открыть пульт управления ->", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Rename Saved TV Dialog
    deviceToRename?.let { dev ->
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            containerColor = Color(0xFF1A1A24),
            title = { Text("Переименовать ТВ", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IP: ${dev.ip}", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Новое название ТВ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        homeViewModel.renameDevice(dev.ip, renameText)
                        deviceToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("Сохранить", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }

    // PIN Code Entry Dialog when TV displays the 6-char HEX PIN
    if (state.isPairingPinRequired) {
        AlertDialog(
            onDismissRequest = { homeViewModel.dismissPinDialog() },
            containerColor = Color(0xFF1A1A24),
            title = {
                Text(
                    "Введите PIN-код с экрана ТВ",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "На экране ${state.pendingPairingName} (${state.pendingPairingIp}) появился 6-значный код (цифры и буквы A-F):",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { pinCode = it.uppercase().take(6) },
                        label = { Text("6-значный PIN (например 902E60)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.pairingError?.let { err ->
                        Text(err, color = Color(0xFFFF453A), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        homeViewModel.confirmPin(pinCode) {
                            pinCode = ""
                            onNavigateToHome()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                ) {
                    Text("Подтвердить", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { homeViewModel.dismissPinDialog() }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }
}
