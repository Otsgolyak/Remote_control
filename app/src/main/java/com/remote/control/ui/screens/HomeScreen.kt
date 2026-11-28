package com.remote.control.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.remote.control.BuildConfig
import com.remote.control.data.bluetooth.DiscoveredBluetoothDevice
import com.remote.control.data.update.UpdateRepositoryImpl
import com.remote.control.ui.AppShortcut
import com.remote.control.ui.HomeViewModel
import com.remote.control.ui.theme.AppTheme
import com.remote.control.utils.Logger
import kotlinx.coroutines.launch
import remote.RemoteKeyCode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by homeViewModel.uiState.collectAsState()
    val subscriptionTier by homeViewModel.subscriptionTier.collectAsState()
    val colors = AppTheme.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    var keyboardText by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var newTvName by remember { mutableStateOf("") }

    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var shortcutTitle by remember { mutableStateOf("") }
    var shortcutUrl by remember { mutableStateOf("") }
    var shortcutIcon by remember { mutableStateOf("star") }

    var showSourceDialog by remember { mutableStateOf(false) }

    val tabs = listOf("D-Pad", "Air Mouse", "Клавиатура")
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val updateRepository = remember { UpdateRepositoryImpl(context) }
    val downloadProgress by updateRepository.downloadProgress.collectAsState()
    var availableUpdateVersion by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            val newVer = updateRepository.checkForNewVersion()
            if (newVer != null) {
                availableUpdateVersion = newVer
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            homeViewModel.onAirMouseTabOpened()
        }
    }

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
        // После предоставления разрешений Bluetooth:
        // тихо запускаем автоподключение к ближайшему ТВ
        Logger.log("HomeScreen: получены разрешения Bluetooth, тихое автоподключение к ТВ")
        homeViewModel.autoConnectBluetoothMouse()
    }

    val sttLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        val spokenText = matches?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            if (selectedTab == 2) {
                // На вкладке клавиатуры: не отправляем сразу на ТВ, а вписываем в поле ввода для проверки и редактирования
                keyboardText = if (keyboardText.isBlank()) spokenText else "$keyboardText $spokenText"
                Toast.makeText(context, "Текст добавлен в поле ввода", Toast.LENGTH_SHORT).show()
            } else {
                // На вкладках пульта или мыши: отправляем сразу на ТВ
                keyboardText = spokenText
                homeViewModel.sendText(spokenText)
                Toast.makeText(context, "Отправлено на ТВ: $spokenText", Toast.LENGTH_SHORT).show()
            }
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
        colors = listOf(colors.secondary, colors.primary)
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
            "settings" -> colors.primary
            "apps" -> Color(0xFFFF9F0A)
            else -> colors.secondary
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.topBarBackground
                ),
                navigationIcon = {
                    IconButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_POWER) }
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = colors.error
                        )
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToPairing() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = state.selectedDeviceName,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Выбрать ТВ",
                                    tint = colors.primary,
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
                                        .background(if (state.isConnected) colors.success else colors.error)
                                )
                                Text(
                                    text = if (state.isConnected) "Connected" else "Not connected",
                                    color = if (state.isConnected) colors.success else colors.error,
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
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Переименовать ТВ",
                                    tint = colors.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Центральный бейдж: Free / Premium
                        val isPremium = subscriptionTier.equals("Premium", ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isPremium) Color(0xFFFFB300).copy(alpha = 0.15f) else colors.cardBackground,
                            border = BorderStroke(1.dp, if (isPremium) Color(0xFFFFB300) else colors.cardBorder)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isPremium) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "Premium",
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Text(
                                    text = subscriptionTier,
                                    color = if (isPremium) Color(0xFFFFB300) else colors.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = colors.textPrimary)
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
                            homeViewModel.launchShortcut(shortcut, context)
                        },
                        onDelete = if (shortcut.isCustom) {
                            { homeViewModel.removeCustomShortcut(shortcut.title) }
                        } else null
                    )
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .size(width = 84.dp, height = 72.dp)
                            .border(1.dp, colors.primary, RoundedCornerShape(14.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                homeViewModel.loadAvailableTvApps(context)
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
                                tint = colors.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Добавить",
                                color = colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Mode Switcher Tabs
            val isPremium = subscriptionTier.equals("Premium", ignoreCase = true)

            LaunchedEffect(isPremium) {
                if (!isPremium && (selectedTab == 1 || selectedTab == 2)) {
                    selectedTab = 0
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colors.cardBackground,
                contentColor = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            ) {
                tabs.forEachIndexed { index, title ->
                    val isLocked = !isPremium && (index == 1 || index == 2)
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isLocked) {
                                Toast.makeText(
                                    context,
                                    "Для использования этой функции приобретите премиум",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                if (selectedTab == 1 && index != 1 && state.isGyroEnabled) {
                                    homeViewModel.setGyroEnabled(false)
                                }
                                selectedTab = index
                            }
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = if (isLocked) Modifier.alpha(0.5f) else Modifier
                            ) {
                                Text(
                                    title,
                                    color = if (selectedTab == index) colors.primary else colors.textSecondary,
                                    fontSize = 13.sp
                                )
                                if (isLocked) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Премиум",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
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
                            .background(colors.dpadBackground, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_UP) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Up", tint = colors.dpadIcon, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, "Down", tint = colors.dpadIcon, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, "Left", tint = colors.dpadIcon, modifier = Modifier.size(40.dp))
                        }
                        IconButton(
                            onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .size(56.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, "Right", tint = colors.dpadIcon, modifier = Modifier.size(40.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(colors.dpadOk)
                                .border(1.5.dp, neonGradient, CircleShape)
                                .clickable { pressKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("OK", color = colors.dpadIcon, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = colors.surface,
                                        checkedTrackColor = colors.primary,
                                        uncheckedThumbColor = colors.surface,
                                        uncheckedTrackColor = colors.divider
                                    )
                                )
                                Text("Гироскоп", color = colors.textPrimary, fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (state.isLgMode) {
                                    Surface(
                                        color = Color(0xFFA50034).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AdsClick,
                                                contentDescription = null,
                                                tint = Color(0xFFE50914),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                "Magic Remote (Wi-Fi)",
                                                fontSize = 11.sp,
                                                color = Color(0xFFE50914),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                btPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.BLUETOOTH_CONNECT,
                                                        Manifest.permission.BLUETOOTH_ADVERTISE,
                                                        Manifest.permission.BLUETOOTH_SCAN
                                                    )
                                                )
                                            } else {
                                                homeViewModel.autoConnectBluetoothMouse()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (state.isBtMouseConnected) colors.success.copy(alpha = 0.2f) else colors.actionButton
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Mouse,
                                            contentDescription = null,
                                            tint = if (state.isBtMouseConnected) colors.success else colors.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            if (state.isBtMouseConnected) "BT Курсор: ВКЛ" else "BT Мышь",
                                            fontSize = 11.sp,
                                            color = if (state.isBtMouseConnected) colors.success else colors.actionButtonText
                                        )
                                    }
                                }
                            }
                        }

                        if (!state.isLgMode && !state.isBtMouseConnected) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.9f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        homeViewModel.makeBluetoothDiscoverable(context)
                                        Toast.makeText(context, "Телефон видим для ТВ 5 минут. На ТВ выберите его в 'Пульты и аксессуары'", Toast.LENGTH_LONG).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.BluetoothSearching,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Стрелка мыши на ТВ (Android / Samsung)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            "Нажмите, затем на ТВ в 'Пульты' выберите телефон",
                                            fontSize = 10.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                    Surface(
                                        color = colors.primary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Подключить",
                                            color = colors.primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(155.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, neonGradient, RoundedCornerShape(18.dp))
                                .background(colors.touchpadBackground)
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
                                    color = colors.textSecondary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    state.btMouseStatus,
                                    color = colors.primary,
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
                                    .background(colors.touchpadCursor)
                                    .border(2.dp, colors.surface, CircleShape)
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
                                colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                            ) {
                                Text("ОК", color = colors.actionButtonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                                    homeViewModel.onMouseClick(left = true)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                            ) {
                                Text("ОК", color = colors.actionButtonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                2 -> {
                    // Keyboard Tab (Authentic RemoteImeBatchEdit + Key Fallback)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { launchPhoneSpeechToText() }) {
                                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод", tint = colors.primary)
                                        }
                                        if (keyboardText.isNotEmpty()) {
                                            IconButton(onClick = { keyboardText = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                            }
                                        }
                                    }
                                }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        homeViewModel.sendText(keyboardText)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.secondary),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Text("Вставить на ТВ", color = Color.White, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        homeViewModel.sendTextAsKeys(keyboardText)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("По буквам", fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_DEL) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Backspace, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Text("Стереть", fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_SPACE) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Пробел", fontSize = 12.sp, maxLines = 1)
                                }
                                Button(
                                    onClick = { pressKey(RemoteKeyCode.KEYCODE_ENTER) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Enter ↵", color = if (colors.isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
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
                    // Явная кнопка "Назад" с понятной подписью (долгое нажатие = ПКМ / контекстное меню)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.actionButton,
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { pressKey(RemoteKeyCode.KEYCODE_BACK) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    homeViewModel.onMouseClick(left = false)
                                    Toast.makeText(context, "ПКМ (Меню)", Toast.LENGTH_SHORT).show()
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = colors.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Назад", color = colors.actionButtonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(2.dp, neonGradient, CircleShape)
                            .background(if (state.isVoiceActive) colors.error.copy(alpha = 0.25f) else colors.actionButton)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                launchPhoneSpeechToText()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isVoiceActive) Icons.Default.MicNone else Icons.Default.Mic,
                            contentDescription = "Голосовой ввод",
                            tint = if (state.isVoiceActive) colors.error else colors.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Кнопка "Домой"
                    FilledTonalButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_HOME) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.actionButton),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(44.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Домой", tint = colors.secondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Домой", color = colors.actionButtonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                if (state.isVoiceActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Говорите в микрофон телефона... Нажмите для отправки",
                        color = colors.secondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. Bottom Volume, Source & Exit Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка выбора источника сигнала (HDMI 1/2/3/TV) с открытием меню слева
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        com.remote.control.utils.Logger.log("HomeScreen UI: Нажата кнопка Источник/HDMI -> открытие диалога выбора")
                        showSourceDialog = true
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.actionButton)
                ) {
                    Icon(Icons.Default.Input, contentDescription = "Источник сигнала (HDMI)", tint = colors.secondary)
                }

                // Отцентрированный блок управления громкостью
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.actionButton),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("VOL -", color = colors.actionButtonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    IconButton(onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE) }) {
                        Icon(Icons.Default.VolumeOff, contentDescription = "Mute", tint = colors.textPrimary)
                    }
                    FilledTonalButton(
                        onClick = { pressKey(RemoteKeyCode.KEYCODE_VOLUME_UP) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.actionButton),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("VOL +", color = colors.actionButtonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Кнопка выхода из приложения справа
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        (context as? Activity)?.finishAffinity()
                    }
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = "Выход", tint = colors.error)
                }
            }
        }
    }

    // Rename TV Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = colors.cardBackground,
            title = { Text("Переименовать ТВ", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Сохранить", color = if (colors.isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }

    // New Version Available Dialog
    if (showUpdateDialog && availableUpdateVersion != null) {
        AlertDialog(
            onDismissRequest = {
                if (!downloadProgress.isDownloading) {
                    showUpdateDialog = false
                }
            },
            containerColor = colors.cardBackground,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Доступно обновление",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Найдена новая версия $availableUpdateVersion (у вас установлена v${BuildConfig.VERSION_NAME}).",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                    if (downloadProgress.isDownloading) {
                        LinearProgressIndicator(
                            progress = downloadProgress.percent / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = colors.primary,
                            trackColor = colors.divider
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${downloadProgress.percent}%",
                                color = colors.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${downloadProgress.downloadedMb} / ${downloadProgress.totalMb} МБ",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (downloadProgress.isDownloading) return@Button
                        if (updateRepository.canInstall()) {
                            scope.launch {
                                val ok = updateRepository.checkForUpdates()
                                if (!ok) {
                                    Toast.makeText(context, "Ошибка загрузки обновления", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
                            updateRepository.requestInstallPermission(context)
                        }
                    },
                    enabled = !downloadProgress.isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (downloadProgress.isDownloading) "Загрузка..." else "Обновить сейчас",
                        color = if (colors.isDark) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                if (!downloadProgress.isDownloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Позже", color = colors.textSecondary)
                    }
                }
            }
        )
    }

    // Add Custom App Shortcut Dialog (выбор из списка установленных/доступных приложений)
    if (showAddShortcutDialog) {
        var searchQuery by remember { mutableStateOf("") }
        var selectedCandidate by remember { mutableStateOf<com.remote.control.data.apps.TvAppCandidate?>(null) }
        var showManualInput by remember { mutableStateOf(false) }

        val availableApps = state.availableTvApps
        val filteredApps = remember(availableApps, searchQuery) {
            if (searchQuery.isBlank()) availableApps
            else availableApps.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = {
                showAddShortcutDialog = false
                selectedCandidate = null
                showManualInput = false
            },
            containerColor = colors.cardBackground,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Добавить в быстрый запуск",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    IconButton(onClick = { showManualInput = !showManualInput }) {
                        Icon(
                            if (showManualInput) Icons.Default.List else Icons.Default.Edit,
                            contentDescription = "Режим ввода",
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showManualInput) {
                        Text(
                            "Вручную укажите название и ссылку/пакет:",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = shortcutTitle,
                            onValueChange = { shortcutTitle = it },
                            label = { Text("Название (например Kinopoisk)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = shortcutUrl,
                            onValueChange = { shortcutUrl = it },
                            label = { Text("Ссылка или DeepLink (например https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Поиск приложения...") },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.isLoadingApps) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Опрос приложений на ТВ...", color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        } else if (filteredApps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (searchQuery.isBlank()) "Все доступные приложения уже добавлены" else "Приложение не найдено",
                                    color = colors.textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredApps) { candidate ->
                                    val isSelected = selectedCandidate?.packageNameOrAppId == candidate.packageNameOrAppId
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) colors.primary.copy(alpha = 0.2f) else colors.surface
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(
                                                1.5.dp,
                                                if (isSelected) colors.primary else Color.Transparent,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                selectedCandidate = candidate
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = iconForShortcut(candidate.iconName),
                                                    contentDescription = null,
                                                    tint = colorForShortcut(candidate.iconName),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Column {
                                                    Text(
                                                        candidate.title,
                                                        color = colors.textPrimary,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp
                                                    )
                                                    if (candidate.isInstalledOnTv) {
                                                        Text(
                                                            "Установлено на ТВ",
                                                            color = colors.success,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (showManualInput) {
                            if (shortcutTitle.isNotBlank() && shortcutUrl.isNotBlank()) {
                                homeViewModel.addCustomShortcut(shortcutTitle, shortcutUrl, "tv")
                                showAddShortcutDialog = false
                                shortcutTitle = ""
                                shortcutUrl = ""
                                showManualInput = false
                            }
                        } else {
                            val chosen = selectedCandidate
                            if (chosen != null) {
                                val link = chosen.deepLink.ifBlank { chosen.packageNameOrAppId }
                                homeViewModel.addCustomShortcut(chosen.title, link, chosen.iconName)
                                showAddShortcutDialog = false
                                selectedCandidate = null
                            }
                        }
                    },
                    enabled = (showManualInput && shortcutTitle.isNotBlank() && shortcutUrl.isNotBlank()) ||
                        (!showManualInput && selectedCandidate != null),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.success)
                ) {
                    Text("Добавить", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddShortcutDialog = false
                    selectedCandidate = null
                    showManualInput = false
                }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }

    // Source / HDMI Selection Dialog
    // Позволяет выбрать конкретный вход (HDMI 1, HDMI 2, HDMI 3 или системное меню входов)
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            containerColor = colors.cardBackground,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Input, contentDescription = null, tint = colors.secondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Выбор источника сигнала", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Выберите вход телевизора для переключения:",
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )

                    // Кнопка HDMI 1
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_1)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                    ) {
                        Text("HDMI 1", color = colors.actionButtonText, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка HDMI 2
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_2)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                    ) {
                        Text("HDMI 2", color = colors.actionButtonText, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка HDMI 3
                    Button(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_3)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.actionButton)
                    ) {
                        Text("HDMI 3", color = colors.actionButtonText, fontWeight = FontWeight.Bold)
                    }

                    // Кнопка общего вызова меню входов (TV_INPUT)
                    OutlinedButton(
                        onClick = {
                            pressKey(RemoteKeyCode.KEYCODE_TV_INPUT)
                            showSourceDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Меню входов ТВ (Source / Input)", color = colors.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }

    // Диалог сопряжения Bluetooth для мыши/курсора
    state.btPairingPromptDevice?.let { devName ->
        AlertDialog(
            onDismissRequest = { homeViewModel.dismissBtPairingPrompt() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = colors.primary)
                    Text("Сопряжение Bluetooth с ТВ", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Телевизор \"$devName\" требует сопряжения для работы курсора мыши.",
                        color = colors.textPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        "1. Подтвердите запрос на экране ТВ (или на телефоне).\n2. Либо на ТВ откройте «Настройки» ➔ «Пульты и аксессуары» ➔ «Добавить устройство» и нажмите кнопку ниже, чтобы ТВ увидел телефон.",
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        homeViewModel.makeBluetoothDiscoverable(context)
                        homeViewModel.dismissBtPairingPrompt()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Сделать телефон видимым", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { homeViewModel.dismissBtPairingPrompt() }) {
                    Text("Понятно", color = colors.textSecondary)
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
    val colors = AppTheme.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .size(width = 88.dp, height = 72.dp)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (onDelete != null) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Удалить ярлык",
                    tint = colors.textSecondary,
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
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
