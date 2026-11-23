package com.remote.control.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.control.data.device.Device
import com.remote.control.ui.HomeViewModel
import com.remote.control.utils.Logger

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
                            containerColor = if (isSelected) Color(0xFF14241C) else Color(0xFF161622)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) Color(0xFF34C759) else Color(0xFF282838),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                homeViewModel.connectToSavedDevice(device) {
                                    onNavigateToHome()
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Top Row: Icon + Name + Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(
                                            if (isSelected) Color(0xFF34C759).copy(alpha = 0.15f) else Color(0xFF00E5FF).copy(alpha = 0.12f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = if (isSelected) Color(0xFF34C759) else Color(0xFF00E5FF),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = device.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Clickable mode toggle badge
                                        Surface(
                                            color = if (device.isSamsung) Color(0xFF007AFF).copy(alpha = 0.2f) else Color(0xFF00E5FF).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                homeViewModel.toggleDeviceType(device.ip)
                                            }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = if (device.isSamsung) "Samsung" else "Android TV",
                                                    color = if (device.isSamsung) Color(0xFF2979FF) else Color(0xFF00E5FF),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Icon(
                                                    Icons.Default.SwapHoriz,
                                                    contentDescription = "Сменить режим",
                                                    tint = if (device.isSamsung) Color(0xFF2979FF) else Color(0xFF00E5FF),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = Color.Gray,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = device.ip,
                                            color = Color(0xFFAAAAAA),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (isSelected) {
                                            Text(
                                                text = "• Подключено",
                                                color = Color(0xFF34C759),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            // Divider
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF242434))
                            )

                            // Bottom Actions Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isSelected) "Активное ТВ" else "Нажмите для подключения",
                                    color = if (isSelected) Color(0xFF34C759) else Color(0xFF7E7E92),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Rename button
                                    IconButton(
                                        onClick = {
                                            renameText = device.name
                                            deviceToRename = device
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Переименовать",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // PIN Re-pair button (for Android TV)
                                    if (!device.isSamsung) {
                                        Surface(
                                            color = Color(0xFFFF6D00).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                homeViewModel.initiatePairing(device.name, device.ip)
                                            }
                                        ) {
                                            Text(
                                                "PIN",
                                                color = Color(0xFFFF6D00),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    // Delete button
                                    IconButton(
                                        onClick = { homeViewModel.removeSavedDevice(device.ip) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Удалить",
                                            tint = Color(0xFFFF453A),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
                    val isSamsungTv = tv.port == 8001 || tv.port == 8002 || tv.name.contains("Samsung", ignoreCase = true) || tv.name.contains("Tizen", ignoreCase = true)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF181822)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (isSamsungTv) Color(0xFF2979FF) else Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                            .clickable {
                                if (isSamsungTv) {
                                    homeViewModel.connectToSamsungTv(tv.name, tv.ip) {
                                        onNavigateToHome()
                                    }
                                } else {
                                    val existing = savedDevices.find { it.ip == tv.ip }
                                    if (existing != null) {
                                        homeViewModel.connectToSavedDevice(existing) {
                                            onNavigateToHome()
                                        }
                                    } else {
                                        homeViewModel.initiatePairing(tv.name, tv.ip)
                                    }
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = if (isSamsungTv) Color(0xFF2979FF) else Color(0xFF00E5FF))
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = tv.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            color = if (isSamsungTv) Color(0xFF007AFF).copy(alpha = 0.2f) else Color(0xFF00E5FF).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isSamsungTv) "Samsung Tizen" else "Android TV",
                                                color = if (isSamsungTv) Color(0xFF2979FF) else Color(0xFF00E5FF),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("${tv.ip}:${tv.port}", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    if (isSamsungTv) {
                                        homeViewModel.connectToSamsungTv(tv.name, tv.ip) {
                                            onNavigateToHome()
                                        }
                                    } else {
                                        val existing = savedDevices.find { it.ip == tv.ip }
                                        if (existing != null) {
                                            homeViewModel.connectToSavedDevice(existing) {
                                                onNavigateToHome()
                                            }
                                        } else {
                                            homeViewModel.initiatePairing(tv.name, tv.ip)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSamsungTv) Color(0xFF007AFF) else Color(0xFFFF6D00))
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
                        Button(
                            onClick = {
                                if (manualIp.isNotBlank()) {
                                    homeViewModel.connectToSamsungTv(manualName.ifBlank { "Samsung TV" }, manualIp) {
                                        onNavigateToHome()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                        ) {
                            Text("Подключить как Samsung Smart TV (Tizen)", color = Color.White, fontWeight = FontWeight.Bold)
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
