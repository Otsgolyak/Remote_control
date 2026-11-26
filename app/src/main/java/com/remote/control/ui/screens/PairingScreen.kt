package com.remote.control.ui.screens

import androidx.compose.foundation.BorderStroke
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
import com.remote.control.ui.theme.AppTheme
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
    val colors = AppTheme.colors

    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("Xiaomi TV") }
    var pinCode by remember { mutableStateOf("") }

    var deviceToRename by remember { mutableStateOf<Device?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showConnectionHelp by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.topBarBackground),
                title = {
                    Text(
                        "Выбор и подключение ТВ",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(onClick = { homeViewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить поиск", tint = colors.primary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки и логи", tint = colors.textPrimary)
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
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = colors.primary,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Text(state.status, color = colors.textPrimary)
                        }
                    }
                }
            }

            state.pairingError?.let { errorText ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Ошибка подключения", color = colors.error, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(errorText, color = colors.textPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 1. Saved Paired TVs
            if (savedDevices.isNotEmpty()) {
                item {
                    Text(
                        "Сохраненные ТВ (${savedDevices.size})",
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                items(savedDevices) { device ->
                    val isSelected = state.selectedDeviceIp == device.ip && state.isConnected
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) colors.success.copy(alpha = 0.1f) else colors.cardBackground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) colors.success else colors.cardBorder,
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
                                            if (isSelected) colors.success.copy(alpha = 0.15f) else colors.primary.copy(alpha = 0.12f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = if (isSelected) colors.success else colors.primary,
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
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Clickable mode toggle badge
                                        Surface(
                                            color = if (device.isSamsung) Color(0xFF007AFF).copy(alpha = 0.15f) else colors.primary.copy(alpha = 0.15f),
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
                                                    color = if (device.isSamsung) Color(0xFF2979FF) else colors.primary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Icon(
                                                    Icons.Default.SwapHoriz,
                                                    contentDescription = "Сменить режим",
                                                    tint = if (device.isSamsung) Color(0xFF2979FF) else colors.primary,
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
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = device.ip,
                                            color = colors.textSecondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (isSelected) {
                                            Text(
                                                text = "• Подключено",
                                                color = colors.success,
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
                                    .background(colors.divider)
                            )

                            // Bottom Actions Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isSelected) "Активное ТВ" else "Нажмите для подключения",
                                    color = if (isSelected) colors.success else colors.textSecondary,
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
                                            tint = colors.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // PIN Re-pair button (for Android TV)
                                    if (!device.isSamsung) {
                                        Surface(
                                            color = colors.secondary.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                homeViewModel.initiatePairing(device.name, device.ip)
                                            }
                                        ) {
                                            Text(
                                                "PIN",
                                                color = colors.secondary,
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
                                            tint = colors.error,
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
                        color = colors.secondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (state.isScanning) {
                        Text("Поиск Wi-Fi...", color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }

            if (state.discoveredDevices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Идет поиск Android TV / Xiaomi TV в вашей Wi-Fi сети...",
                                color = colors.textSecondary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Убедитесь, что телефон и телевизор подключены к одному роутеру, либо введите IP телевизора вручную ниже.",
                                color = colors.textSecondary.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                items(state.discoveredDevices) { tv ->
                    val isSamsungTv = tv.port == 8001 || tv.port == 8002 || tv.name.contains("Samsung", ignoreCase = true) || tv.name.contains("Tizen", ignoreCase = true)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (isSamsungTv) Color(0xFF2979FF) else colors.primary, RoundedCornerShape(12.dp))
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
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = if (isSamsungTv) Color(0xFF2979FF) else colors.primary)
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = tv.name,
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            color = if (isSamsungTv) Color(0xFF007AFF).copy(alpha = 0.15f) else colors.primary.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isSamsungTv) "Samsung Tizen" else "Android TV",
                                                color = if (isSamsungTv) Color(0xFF2979FF) else colors.primary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("${tv.ip}:${tv.port}", color = colors.textSecondary, fontSize = 12.sp)
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
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSamsungTv) Color(0xFF007AFF) else colors.secondary)
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
                    colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Ручное подключение по IP",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            IconButton(
                                onClick = { showConnectionHelp = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.HelpOutline,
                                    contentDescription = "Справка по подключению",
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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
                                colors = ButtonDefaults.buttonColors(containerColor = colors.secondary),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Сопряжение (PIN)",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "1-й раз (код с экрана)",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp
                                    )
                                }
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
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, colors.cardBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Уже спарен",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "Прямой вход без PIN",
                                        color = colors.textSecondary,
                                        fontSize = 10.sp
                                    )
                                }
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Подключить как Samsung Smart TV (Tizen)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Запрос «Разрешить» на экране ТВ",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onNavigateToHome,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                ) {
                    Text("Открыть пульт управления ->", color = colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Help Dialog: Explains connection modes clearly
    if (showConnectionHelp) {
        AlertDialog(
            onDismissRequest = { showConnectionHelp = false },
            containerColor = colors.cardBackground,
            icon = {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    "Как выбрать способ подключения?",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Option 1: Pairing PIN
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.secondary.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "🟢 Сопряжение (PIN)",
                                color = colors.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "Для первого подключения к Android TV / Xiaomi TV. Телефон и ТВ свяжутся защищенным каналом, а на экране ТВ появится код, который нужно ввести в приложении.",
                                color = colors.textPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Option 2: Already Paired
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.background),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "⚪ Уже спарен",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "Прямой вход без ввода кода. Используйте, если ТВ уже сопрягался ранее, но роутер выдал ему новый IP-адрес, либо автопоиск в Wi-Fi не видит ТВ.",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Option 3: Samsung
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF007AFF).copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, Color(0xFF007AFF).copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "🔵 Samsung Smart TV (Tizen)",
                                color = Color(0xFF2979FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "Для телевизоров марки Samsung. Ввод PIN-кода не требуется — на экране ТВ появится системный запрос «Разрешить подключение?».",
                                color = colors.textPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showConnectionHelp = false },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text(
                        "Понятно",
                        color = if (colors.isDark) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // Rename Saved TV Dialog
    deviceToRename?.let { dev ->
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            containerColor = colors.cardBackground,
            title = { Text("Переименовать ТВ", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IP: ${dev.ip}", color = colors.textSecondary, fontSize = 12.sp)
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
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Сохранить", color = if (colors.isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }

    // PIN Code Entry Dialog when TV displays the 6-char HEX PIN
    if (state.isPairingPinRequired) {
        AlertDialog(
            onDismissRequest = { homeViewModel.dismissPinDialog() },
            containerColor = colors.cardBackground,
            title = {
                Text(
                    "Введите PIN-код с экрана ТВ",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "На экране ${state.pendingPairingName} (${state.pendingPairingIp}) появился 6-значный код (цифры и буквы A-F):",
                        color = colors.textSecondary,
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
                        Text(err, color = colors.error, fontSize = 12.sp)
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
                    colors = ButtonDefaults.buttonColors(containerColor = colors.success)
                ) {
                    Text("Подтвердить", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { homeViewModel.dismissPinDialog() }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }
}
