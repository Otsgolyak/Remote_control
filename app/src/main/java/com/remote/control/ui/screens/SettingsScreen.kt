package com.remote.control.ui.screens

import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.remote.control.ui.theme.AppTheme
import com.remote.control.data.report.BugReportResult
import com.remote.control.data.report.BugReportSender
import com.remote.control.data.adb.AdbCleanResult
import com.remote.control.data.adb.AdbCleaner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.control.BuildConfig
import com.remote.control.data.update.UpdateRepositoryImpl
import com.remote.control.ui.HomeViewModel
import com.remote.control.utils.Logger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    homeViewModel: HomeViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by homeViewModel.uiState.collectAsState()
    val isDarkTheme by homeViewModel.isDarkTheme.collectAsState()
    val subscriptionTier by homeViewModel.subscriptionTier.collectAsState()
    val colors = AppTheme.colors
    val logsText by Logger.logsFlow.collectAsState()
    val updateRepository = remember { UpdateRepositoryImpl(context) }
    val downloadProgress by updateRepository.downloadProgress.collectAsState()
    var promoCodeInput by remember { mutableStateOf("") }
    var isSendingBugReport by remember { mutableStateOf(false) }
    var isSendingFeedback by remember { mutableStateOf(false) }
    var activeAdbTask by remember { mutableStateOf<String?>(null) }
    var cleanResultDialog by remember { mutableStateOf<AdbCleanResult?>(null) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var feedbackSenderName by remember { mutableStateOf("") }
    var feedbackMessageText by remember { mutableStateOf("") }
    var attachLogsToEmail by remember { mutableStateOf(true) }
    var isLogsExpanded by remember { mutableStateOf(false) }

    val deviceIp = state.selectedDeviceIp
    val isAdbAvailable = state.isConnected && !state.isSamsungMode && !state.isLgMode && deviceIp != null
    val connectionWarning = when {
        state.isSamsungMode -> "Функции ADB доступны только для Android TV"
        state.isLgMode -> "Функции ADB доступны только для Android TV"
        !state.isConnected || deviceIp == null -> "Подключитесь к Android TV для работы с ADB"
        else -> null
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (deviceIp != null && isAdbAvailable) {
                activeAdbTask = "apk"
                Toast.makeText(context, "Передача и установка APK на ТВ...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val res = AdbCleaner.installCustomApk(context, deviceIp, uri)
                    activeAdbTask = null
                    cleanResultDialog = res
                }
            } else {
                Toast.makeText(context, "Подключитесь к Android TV для установки APK", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.topBarBackground),
                title = { Text("Настройки и диагностика", color = colors.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = colors.textPrimary)
                    }
                }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Xiaomi TV Remote v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                color = colors.primary,
                fontWeight = FontWeight.Bold
            )

            // Тема оформления (Светлая / Тёмная)
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                            tint = if (isDarkTheme) colors.primary else Color(0xFFFFB300),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "Тема оформления",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                if (isDarkTheme) "Тёмная тема активна" else "Светлая тема активна",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { homeViewModel.setDarkTheme(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.surface,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.surface,
                            uncheckedTrackColor = colors.divider
                        )
                    )
                }
            }

            // Тарифный план и ввод промокода (Free / Premium)
            val isPremium = subscriptionTier.equals("Premium", ignoreCase = true)
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (isPremium) Color(0xFFFFB300) else colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                if (isPremium) Icons.Default.WorkspacePremium else Icons.Default.CardGiftcard,
                                contentDescription = null,
                                tint = if (isPremium) Color(0xFFFFB300) else colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    "Тарифный план",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Текущий статус: $subscriptionTier",
                                    color = colors.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPremium) Color(0xFFFFB300).copy(alpha = 0.2f) else colors.actionButton,
                            border = BorderStroke(1.dp, if (isPremium) Color(0xFFFFB300) else colors.cardBorder)
                        ) {
                            Text(
                                text = subscriptionTier,
                                color = if (isPremium) Color(0xFFFFB300) else colors.actionButtonText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = promoCodeInput,
                            onValueChange = { promoCodeInput = it },
                            placeholder = { Text("Введите промокод (1 или 2)", fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Button(
                            onClick = {
                                val result = homeViewModel.applyPromoCode(promoCodeInput)
                                if (result != null) {
                                    Toast.makeText(context, "Активирован тариф: $result", Toast.LENGTH_SHORT).show()
                                    promoCodeInput = ""
                                } else {
                                    Toast.makeText(context, "Неверный промокод", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("OK", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Air Mouse Sensitivity Card
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Чувствительность аэромыши (Air Mouse): ${"%.1f".format(state.mouseSensitivity)}x",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = state.mouseSensitivity,
                        onValueChange = { homeViewModel.setSensitivity(it) },
                        valueRange = 0.5f..3.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primary,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.divider
                        )
                    )
                }
            }

            // 1. Очистка кэша ТВ
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isPremium) Modifier.alpha(0.6f) else Modifier),
                border = BorderStroke(1.dp, if (isPremium) colors.primary.copy(alpha = 0.35f) else colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = if (isPremium) colors.primary else colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Очистка кэша ТВ",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        if (!isPremium) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        "Premium",
                                        color = Color(0xFFFFB300),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "Очищает системный кэш приложений и временные файлы в памяти Android TV, освобождая оперативную и постоянную память.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Button(
                        enabled = isPremium && isAdbAvailable && activeAdbTask == null,
                        onClick = {
                            if (!isPremium) {
                                Toast.makeText(context, "Для использования этой функции приобретите премиум", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (deviceIp != null) {
                                activeAdbTask = "cache"
                                Toast.makeText(context, "Очистка кэша ТВ по ADB...", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val res = AdbCleaner.cleanCache(context, deviceIp)
                                    activeAdbTask = null
                                    cleanResultDialog = res
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPremium) colors.primary else colors.actionButton)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (activeAdbTask == "cache") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    if (isPremium) Icons.Default.DeleteSweep else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isPremium) Color.White else colors.actionButtonText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                if (activeAdbTask == "cache") "Очистка кэша..." else "Очистить кэш",
                                color = if (isPremium) Color.White else colors.actionButtonText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (!isPremium) {
                        Text(
                            "Доступно в Premium подписке. Активируйте промокод выше.",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp
                        )
                    } else if (connectionWarning != null) {
                        Text(
                            connectionWarning,
                            color = colors.warning,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 2. Блокировка рекламы и трекеров
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isPremium) Modifier.alpha(0.6f) else Modifier),
                border = BorderStroke(1.dp, if (isPremium) colors.success.copy(alpha = 0.35f) else colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = if (isPremium) colors.success else colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Блокировка рекламы",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        if (!isPremium) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        "Premium",
                                        color = Color(0xFFFFB300),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "Включает приватный AdGuard DNS (DoT), отключает рекламный ID и встроенные рекламные службы Xiaomi и Google TV.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = isPremium && isAdbAvailable && activeAdbTask == null,
                            onClick = {
                                if (!isPremium) {
                                    Toast.makeText(context, "Для использования этой функции приобретите премиум", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (deviceIp != null) {
                                    activeAdbTask = "ads"
                                    Toast.makeText(context, "Блокировка рекламы по ADB...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val res = AdbCleaner.blockAds(context, deviceIp)
                                        activeAdbTask = null
                                        cleanResultDialog = res
                                    }
                                }
                            },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isPremium) colors.success else colors.actionButton)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (activeAdbTask == "ads") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        if (isPremium) Icons.Default.Security else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isPremium) Color.White else colors.actionButtonText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    if (activeAdbTask == "ads") "Блокировка..." else "Блокировать рекламу",
                                    color = if (isPremium) Color.White else colors.actionButtonText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = isPremium && isAdbAvailable && activeAdbTask == null,
                            onClick = {
                                if (!isPremium) {
                                    Toast.makeText(context, "Для использования этой функции приобретите премиум", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                if (deviceIp != null) {
                                    activeAdbTask = "restore"
                                    Toast.makeText(context, "Восстановление настроек ТВ...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val res = AdbCleaner.restoreTv(context, deviceIp)
                                        activeAdbTask = null
                                        cleanResultDialog = res
                                    }
                                }
                            },
                            modifier = Modifier.weight(0.9f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                            border = BorderStroke(1.dp, colors.cardBorder)
                        ) {
                            Text(
                                if (activeAdbTask == "restore") "Сброс..." else "Сброс",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (!isPremium) {
                        Text(
                            "Доступно в Premium подписке. Активируйте промокод выше.",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp
                        )
                    } else if (connectionWarning != null) {
                        Text(
                            connectionWarning,
                            color = colors.warning,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 3. Установка файлов и приложений
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isPremium) Modifier.alpha(0.6f) else Modifier),
                border = BorderStroke(1.dp, if (isPremium) Color(0xFF64B5F6).copy(alpha = 0.5f) else colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = if (isPremium) Color(0xFF64B5F6) else colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Установка файлов и приложений",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        if (!isPremium) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        "Premium",
                                        color = Color(0xFFFFB300),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "Установка медиа-комбайна Media Station X (msx.cc.ua) для бесплатного просмотра фильмов и ТВ, либо установка любого APK-файла со смартфона на ТВ.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = isPremium && isAdbAvailable && activeAdbTask == null,
                            onClick = {
                                if (!isPremium) {
                                    Toast.makeText(context, "Для использования этой функции приобретите премиум", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (deviceIp != null) {
                                    activeAdbTask = "msx"
                                    Toast.makeText(context, "Установка Media Station X по ADB...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val res = AdbCleaner.installMsx(context, deviceIp)
                                        activeAdbTask = null
                                        cleanResultDialog = res
                                    }
                                }
                            },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isPremium) Color(0xFF1976D2) else colors.actionButton)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (activeAdbTask == "msx") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        if (isPremium) Icons.Default.PlayCircle else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isPremium) Color.White else colors.actionButtonText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    if (activeAdbTask == "msx") "Установка..." else "Установить MSX",
                                    color = if (isPremium) Color.White else colors.actionButtonText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = isPremium && isAdbAvailable && activeAdbTask == null,
                            onClick = {
                                if (!isPremium) {
                                    Toast.makeText(context, "Для использования этой функции приобретите премиум", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                try {
                                    apkPickerLauncher.launch("*/*")
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Не удалось открыть выбор файлов: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1.1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                            border = BorderStroke(1.dp, if (isPremium) Color(0xFF64B5F6).copy(alpha = 0.5f) else colors.cardBorder)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (activeAdbTask == "apk") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = colors.primary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        if (isPremium) Icons.Default.FolderOpen else Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isPremium) colors.textPrimary else colors.actionButtonText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    if (activeAdbTask == "apk") "Установка..." else "Выбрать APK",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    if (!isPremium) {
                        Text(
                            "Доступно в Premium подписке. Активируйте промокод выше.",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp
                        )
                    } else if (connectionWarning != null) {
                        Text(
                            connectionWarning,
                            color = colors.warning,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Версия приложения",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Surface(
                            color = colors.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "v${BuildConfig.VERSION_NAME}",
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

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
                                "${downloadProgress.percent}%",
                                color = colors.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${downloadProgress.downloadedMb} / ${downloadProgress.totalMb} МБ",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Button(
                        enabled = !downloadProgress.isDownloading,
                        onClick = {
                            if (updateRepository.canInstall()) {
                                Toast.makeText(context, "Проверка и загрузка с GitHub...", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val ok = updateRepository.checkForUpdates()
                                    if (!ok) {
                                        Toast.makeText(context, "У вас уже последняя версия или ошибка сети", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
                                updateRepository.requestInstallPermission(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (colors.isDark) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (downloadProgress.isDownloading) "Загрузка обновления..." else "Проверить и обновить с GitHub",
                                color = if (colors.isDark) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Раздел обратной связи и диагностики
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { showFeedbackDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Обратная связь",
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Button(
                    enabled = !isSendingBugReport,
                    onClick = {
                        isSendingBugReport = true
                        Toast.makeText(context, "Отправка баг-репорта на GitHub...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val result = BugReportSender.sendReport()
                            isSendingBugReport = false
                            when (result) {
                                is BugReportResult.Success -> {
                                    Toast.makeText(context, "✅ Репорт #${result.issueNumber} успешно отправлен разработчику!", Toast.LENGTH_LONG).show()
                                }
                                is BugReportResult.Error -> {
                                    Toast.makeText(context, "❌ Не удалось отправить: ${result.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.warning.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSendingBugReport) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.warning,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = null,
                                tint = colors.warning,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            if (isSendingBugReport) "Отправка..." else "Баг-репорт",
                            color = colors.warning,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Журнал событий (логи диагностики)
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Журнал событий (Логи)",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        TextButton(
                            onClick = { isLogsExpanded = !isLogsExpanded },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    if (isLogsExpanded) "Скрыть" else "Показать",
                                    color = colors.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    if (isLogsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (isLogsExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("RemoteLogs", logsText))
                                    Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                                border = BorderStroke(1.dp, colors.cardBorder),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text("Копировать", fontSize = 11.sp, color = colors.textPrimary)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    Logger.clearLogs()
                                    Toast.makeText(context, "Журнал логов очищен", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                                border = BorderStroke(1.dp, colors.error.copy(alpha = 0.4f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = colors.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text("Очистить", fontSize = 11.sp, color = colors.error)
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 220.dp)
                                .background(
                                    if (colors.isDark) Color(0xFF14141C) else Color(0xFFEDF2F7),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = logsText.ifBlank { "Логи пусты" },
                                color = if (colors.isDark) Color(0xFF34C759) else Color(0xFF1B5E20),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    cleanResultDialog?.let { result ->
        AlertDialog(
            onDismissRequest = { cleanResultDialog = null },
            containerColor = colors.cardBackground,
            title = {
                Text(
                    if (result.success) "Операция выполнена успешно" else "Результат операции",
                    color = if (result.success) colors.success else colors.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(result.summary, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Divider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))
                    result.details.forEach { detail ->
                        Text(detail, color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { cleanResultDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("OK", color = if (colors.isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            containerColor = colors.cardBackground,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, tint = colors.primary)
                    Text("Обратная связь", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Напишите ваше имя и текст обращения. Сообщение и диагностические логи будут отправлены напрямую разработчику.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    OutlinedTextField(
                        value = feedbackSenderName,
                        onValueChange = { feedbackSenderName = it },
                        label = { Text("Ваше имя (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.cardBorder,
                            focusedLabelColor = colors.primary,
                            unfocusedLabelColor = colors.textSecondary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )

                    OutlinedTextField(
                        value = feedbackMessageText,
                        onValueChange = { feedbackMessageText = it },
                        label = { Text("Текст письма / описание проблемы") },
                        placeholder = { Text("Опишите, что произошло или ваше пожелание...") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.cardBorder,
                            focusedLabelColor = colors.primary,
                            unfocusedLabelColor = colors.textSecondary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Checkbox(
                            checked = attachLogsToEmail,
                            onCheckedChange = { attachLogsToEmail = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.primary,
                                uncheckedColor = colors.textSecondary
                            )
                        )
                        Text(
                            "Прикрепить журнал событий (логи)",
                            color = colors.textPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = feedbackMessageText.isNotBlank() && !isSendingFeedback,
                    onClick = {
                        isSendingFeedback = true
                        scope.launch {
                            val result = BugReportSender.sendFeedback(
                                senderName = feedbackSenderName,
                                message = feedbackMessageText,
                                attachLogs = attachLogsToEmail
                            )
                            isSendingFeedback = false
                            when (result) {
                                is BugReportResult.Success -> {
                                    Toast.makeText(context, "✅ Ваше сообщение успешно отправлено разработчику!", Toast.LENGTH_LONG).show()
                                    showFeedbackDialog = false
                                    feedbackMessageText = ""
                                }
                                is BugReportResult.Error -> {
                                    Toast.makeText(context, "Не удалось отправить напрямую (${result.message}). Открываем почту...", Toast.LENGTH_LONG).show()
                                    Logger.sendFeedbackByEmail(
                                        context = context,
                                        senderName = feedbackSenderName,
                                        message = feedbackMessageText,
                                        attachLogs = attachLogsToEmail
                                    )
                                    showFeedbackDialog = false
                                    feedbackMessageText = ""
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSendingFeedback) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = if (colors.isDark) Color.Black else Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            if (isSendingFeedback) "Отправка..." else "Отправить",
                            color = if (colors.isDark) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFeedbackDialog = false }) {
                    Text("Отмена", color = colors.textSecondary)
                }
            }
        )
    }
}
