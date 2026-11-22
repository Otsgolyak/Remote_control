package com.remote.control.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.remote.control.data.update.UpdateRepositoryImpl
import com.remote.control.ui.AppShortcut
import com.remote.control.ui.HomeViewModel
import kotlinx.coroutines.launch
import remote.RemoteKeyCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by homeViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var keyboardText by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var newTvName by remember { mutableStateOf("") }

    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var shortcutTitle by remember { mutableStateOf("") }
    var shortcutUrl by remember { mutableStateOf("") }
    var shortcutIcon by remember { mutableStateOf("star") }

    var showBtMouseDialog by remember { mutableStateOf(false) }
    var bondedBtList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var showSourceDialog by remember { mutableStateOf(false) }

    val tabs = listOf("D-Pad", "Air Mouse", "Клавиатура")
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val updateRepository = remember { UpdateRepositoryImpl(context) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            homeViewModel.toggleVoice()
        } else {
            Toast.makeText(context, "Разрешите доступ к микрофону для голосового поиска", Toast.LENGTH_SHORT).show()
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        homeViewModel.connectBluetoothMouse()
        bondedBtList = homeViewModel.getBondedBluetoothDevices()
        showBtMouseDialog = true
    }

    val sttLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        val spokenText = matches?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            keyboardText = spokenText
            homeViewModel.sendText(spokenText)
            Toast.makeText(context, "Отправлено на ТВ: $spokenText", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchPhoneSpeechToText() {
        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите для ввода текста на ТВ...")
            }
            sttLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Голосовой ввод Google недоступен на телефоне", Toast.LENGTH_SHORT).show()
        }
    }

    val neonGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFF6D00), Color(0xFF00E5FF))
    )

    fun pressKey(keyCode: RemoteKeyCode) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        com.remote.control.utils.Logger.log("HomeScreen UI: Нажата кнопка ${keyCode.name}")
        homeViewModel.sendKey(keyCode)
    }

    fun iconForShortcut(iconName: String): ImageVector {
        return when (iconName) {
            "youtube" -> Icons.Default.PlayCircle
            "movie" -> Icons.Default.Movie
            "video" -> Icons.Default.LiveTv
            "web" -> Icons.Default.Language
            "settings" -> Icons.Default.Settings
            "apps" -> Icons.Default.Apps
            "music" -> Icons.Default.MusicNote
            else -> Icons.Default.Star
        }
    }

    fun colorForShortcut(iconName: String): Color {
        return when (iconName) {
            "youtube" -> Color(0xFFFF3B30)
            "movie" -> Color(0xFFE50914)
            "video" -> Color(0xFF00A8E1)
            "web" -> Color(0xFF34C759)
            "settings" -> Color(0xFF00E5FF)
            "apps" -> Color(0xFFFF9F0A)
            else -> Color(0xFFFF6D00)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0C),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121216)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_POWER) }
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = Color(0xFFFF3B30)
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToPairing() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = state.selectedDeviceName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбрать ТВ",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (state.isConnected) Color(0xFF34C759) else Color(0xFFFF3B30))
                                )
                                Text(
                                    text = state.status,
                                    color = if (state.isConnected) Color(0xFF34C759) else Color(0xFFFF3B30),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (state.selectedDeviceIp != null) {
                            IconButton(
                                onClick = {
                                    newTvName = state.selectedDeviceName
                                    showRenameDialog = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Переименовать ТВ",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToPairing) {
                        Icon(Icons.Default.Tv, contentDescription = "Список ТВ", tint = Color(0xFF00E5FF))
                    }
                    IconButton(
                        enabled = !isUpdating,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (updateRepository.canInstall()) {
                                isUpdating = true
                                Toast.makeText(context, "Скачивание обновления APK...", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val ok = updateRepository.checkForUpdates()
                                    isUpdating = false
                                    if (!ok) {
                                        Toast.makeText(context, "Ошибка загрузки обновления", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Разрешите установку в настройках", Toast.LENGTH_LONG).show()
                                updateRepository.requestInstallPermission(context as android.app.Activity)
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = "Обновить APK",
                            tint = Color(0xFFFF6D00)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Shortcuts Row with Icons and '+' Add Button
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(state.apps) { shortcut ->
                    ShortcutTile(
                        shortcut = shortcut,
                        icon = iconForShortcut(shortcut.iconName),
                        iconTint = colorForShortcut(shortcut.iconName),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            homeViewModel.launchShortcut(shortcut)
                        },
                        onDelete = if (shortcut.isCustom) {
                            { homeViewModel.removeCustomShortcut(shortcut.title) }
                        } else null
                    )
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .size(width = 84.dp, height = 72.dp)
                            .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(14.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                shortcutTitle = ""
                                shortcutUrl = ""
                                showAddShortcutDialog = true
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = "Добавить",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Добавить",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Mode Switcher Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF16161C),
                contentColor = Color(0xFF00E5FF),
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selectedTab == 1 && index != 1 && state.isGyroEnabled) {
                                homeViewModel.setGyroEnabled(false)
                            }
                            selectedTab = index
                        },
                        text = { Text(title, color = Color.White, fontSize = 13.sp) }
                    )
                }
            }

            // 3. Central Control Area (D-Pad / Air Mouse / Keyboard)
            when (selectedTab) {
                0 -> {
                    // Circular D-Pad
                    Box(
                        modifier = Modifier
                            .size(248.dp)
                            .border(2.dp, neonGradient, CircleShape)
                            .background(Color(0xFF14141A), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_UP) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, "Left", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, "Right", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(Color(0xFF23232E))
                                .border(1.5.dp, neonGradient, CircleShape)
                                .clickable { pressKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                    }
                }

                1 -> {
                    // Air Mouse Touchpad + Gyroscope + BT HID Mouse Cursor on TV
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Switch(
                                    checked = state.isGyroEnabled,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        homeViewModel.setGyroEnabled(enabled)
                                    }
                                )
                                Text("Гироскоп", color = Color.White, fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            btPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.BLUETOOTH_CONNECT,
                                                    Manifest.permission.BLUETOOTH_ADVERTISE
                                                )
                                            )
                                        } else {
                                            homeViewModel.connectBluetoothMouse()
                                            bondedBtList = homeViewModel.getBondedBluetoothDevices()
                                            showBtMouseDialog = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (state.isBtMouseConnected) Color(0xFF1E3A2B) else Color(0xFF23232E)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Mouse,
                                        contentDescription = null,
                                        tint = if (state.isBtMouseConnected) Color(0xFF34C759) else Color(0xFF00E5FF),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        if (state.isBtMouseConnected) "BT Курсор: ВКЛ" else "BT Мышь",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(155.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, neonGradient, RoundedCornerShape(18.dp))
                                .background(Color(0xFF14141C))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            homeViewModel.onMouseClick(left = true)
                                        },
                                        onDoubleTap = {
                                            homeViewModel.resetCursorCenter()
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        homeViewModel.updateCursor(dragAmount.x, dragAmount.y)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text(
                                    "Тачпад (свайп = курсор, тап = ОК, 2x тап = центр)",
                                    color = Color(0xFF777788),
                                    fontSize = 11.sp
                                )
                                Text(
                                    state.btMouseStatus,
                                    color = Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Glowing Visual Cursor Dot
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = state.cursorPosition.first.dp,
                                        y = state.cursorPosition.second.dp
                                    )
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF6D00))
                                    .border(2.dp, Color.White, CircleShape)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    homeViewModel.onMouseClick(left = true)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23232E))
                            ) {
                                Text("ЛКМ (OK)", color = Color.White, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    homeViewModel.onMouseScroll(1)
                                }
                            ) {
                                Text("▲")
                            }
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    homeViewModel.onMouseScroll(-1)
                                }
                            ) {
                                Text("▼")
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    homeViewModel.onMouseClick(left = false)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23232E))
                            ) {
                                Text("ПКМ", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                2 -> {
                    // Keyboard Tab (Authentic RemoteImeBatchEdit + Key Fallback)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, neonGradient, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = keyboardText,
                                onValueChange = { keyboardText = it },
                                label = { Text("Введите текст (рус/англ) для поля ввода ТВ") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (keyboardText.isNotEmpty()) {
                                        IconButton(onClick = { keyboardText = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                        }
                                    }
                                }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        homeViewModel.sendText(keyboardText)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                                ) {
                                    Text("Вставить в поле ТВ", color = Color.White, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        homeViewModel.sendTextAsKeys(keyboardText)
                                    }
                                ) {
                                    Text("По буквам", fontSize = 12.sp)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_DEL) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("⌫ Стереть")
                                }
                                OutlinedButton(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_SPACE) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Пробел")
                                }
                                Button(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_ENTER) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                                ) {
                                    Text("Enter ↵", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Navigation & Voice Row (Назад, Голос, Домой)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Явная кнопка "Назад" с понятной подписью
                    FilledTonalButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_BACK) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF222230)),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color(0xFF00E5FF))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Назад", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(2.dp, neonGradient, CircleShape)
                            .background(if (state.isVoiceActive) Color(0xFF3A1818) else Color(0xFF1F1F28))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                launchPhoneSpeechToText()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isVoiceActive) Icons.Default.MicNone else Icons.Default.Mic,
                            contentDescription = "Голосовой ввод",
                            tint = if (state.isVoiceActive) Color(0xFFFF3B30) else Color(0xFF00E5FF),
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Кнопка "Домой"
                    FilledTonalButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_HOME) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF222230)),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Домой", tint = Color(0xFFFF6D00))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Домой", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                if (state.isVoiceActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Говорите в микрофон телефона... Нажмите для отправки",
                        color = Color(0xFFFF6D00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. Bottom Volume, Back, Source & Exit Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN) }) {
                    Text("VOL -")
                }
                IconButton(onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE) }) {
                    Icon(Icons.Default.VolumeOff, contentDescription = "Mute", tint = Color.White)
                }
                Button(onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_UP) }) {
                    Text("VOL +")
                }
                // Дополнительная кнопка быстрого возврата прямо под большим пальцем
                FilledTonalIconButton(
                    onClick = { pressKey(RemoteKeyCode.KEYCODE_BACK) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF232332))
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color(0xFF00E5FF))
                }
                // Кнопка выбора источника сигнала (HDMI 1/2/3/TV) с открытием меню
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        com.remote.control.utils.Logger.log("HomeScreen UI: Нажата кнопка Источник/HDMI -> открытие диалога выбора")
                        showSourceDialog = true
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF232332))
                ) {
                    Icon(Icons.Default.Input, contentDescription = "Источник сигнала (HDMI)", tint = Color(0xFFFF6D00))
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        (context as? Activity)?.finishAffinity()
                    }
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = "Выход", tint = Color(0xFFFF453A))
                }
            }
        }
    }

    // Rename TV Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = { Text("Переименовать ТВ", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newTvName,
                    onValueChange = { newTvName = it },
                    label = { Text("Новое название ТВ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.selectedDeviceIp?.let { ip ->
                            homeViewModel.renameDevice(ip, newTvName)
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("Сохранить", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }

    // Add Custom App Shortcut Dialog
    if (showAddShortcutDialog) {
        val presets = listOf(
            Triple("Кинопоиск", "https://hd.kinopoisk.ru", "movie"),
            Triple("VK Видео", "https://vkvideo.ru", "video"),
            Triple("Rutube", "https://rutube.ru", "youtube"),
            Triple("Twitch", "https://www.twitch.tv", "video")
        )
        AlertDialog(
            onDismissRequest = { showAddShortcutDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = { Text("Добавить приложение", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Быстрые шаблоны:", color = Color.LightGray, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets) { (pName, pUrl, pIcon) ->
                            SuggestionChip(
                                onClick = {
                                    shortcutTitle = pName
                                    shortcutUrl = pUrl
                                    shortcutIcon = pIcon
                                },
                                label = { Text(pName, color = Color.White, fontSize = 12.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = shortcutTitle,
                        onValueChange = { shortcutTitle = it },
                        label = { Text("Название (например Кинопоиск)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = shortcutUrl,
                        onValueChange = { shortcutUrl = it },
                        label = { Text("Ссылка или DeepLink (например hd.kinopoisk.ru)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (shortcutTitle.isNotBlank() && shortcutUrl.isNotBlank()) {
                            homeViewModel.addCustomShortcut(shortcutTitle, shortcutUrl, shortcutIcon)
                            showAddShortcutDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                ) {
                    Text("Добавить", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddShortcutDialog = false }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }

    // Bluetooth Mouse Selection Dialog
    if (showBtMouseDialog) {
        AlertDialog(
            onDismissRequest = { showBtMouseDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = {
                Text(
                    "Bluetooth Аэромышь (HID Курсор)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (bondedBtList.isEmpty()) {
                        Text(
                            "Нет сопряженных Bluetooth-устройств. Нажмите кнопку ниже, чтобы сделать телефон видимым, и выберите его в настройках Bluetooth на ТВ.",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    } else {
                        Text(
                            "Выберите ваш ТВ из сопряженных Bluetooth-устройств:",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                        bondedBtList.forEach { (btName, btAddr) ->
                            Button(
                                onClick = {
                                    homeViewModel.connectBluetoothMouseToAddress(btAddr)
                                    showBtMouseDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23232E))
                            ) {
                                Text(
                                    "$btName ($btAddr)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            homeViewModel.makeBluetoothDiscoverable(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                    ) {
                        Text(
                            "Сделать телефон видимым для ТВ (300 сек)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBtMouseDialog = false }) {
                    Text("Закрыть", color = Color(0xFF00E5FF))
                }
            }
        )
    }

    // Source / HDMI Selection Dialog
    // Позволяет выбрать конкретный вход (HDMI 1, HDMI 2, HDMI 3 или системное меню входов)
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            containerColor = Color(0xFF1A1A24),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Input, contentDescription = null, tint = Color(0xFFFF6D00))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Выбор источника сигнала", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Выберите вход телевизора для переключения:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    // Кнопка HDMI 1
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_1)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232332))
                    ) {
                        Text("HDMI 1", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка HDMI 2
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_2)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232332))
                    ) {
                        Text("HDMI 2", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка HDMI 3
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_3)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232332))
                    ) {
                        Text("HDMI 3", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка общего вызова меню входов (TV_INPUT)
                    OutlinedButton(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Меню входов ТВ (Source / Input)", color = Color(0xFF00E5FF))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
private fun ShortcutTile(
    shortcut: AppShortcut,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181822)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .size(width = 88.dp, height = 72.dp)
            .border(1.dp, Color(0xFF282838), RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (onDelete != null) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Удалить ярлык",
                    tint = Color.Gray,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp)
                        .clickable { onDelete() }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = shortcut.title,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = shortcut.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
