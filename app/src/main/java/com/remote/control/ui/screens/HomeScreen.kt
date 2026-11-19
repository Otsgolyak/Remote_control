package com.remote.control.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.control.data.update.UpdateRepositoryImpl
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
    val tabs = listOf("D-Pad", "Air Mouse", "Keyboard")
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val updateRepository = remember { UpdateRepositoryImpl(context) }

    val neonGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFF6D00), Color(0xFF00E5FF))
    )

    fun pressKey(keyCode: RemoteKeyCode) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        homeViewModel.sendKey(keyCode)
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateToPairing() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = state.selectedDeviceName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Выбрать ТВ",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                fontSize = 11.sp
                            )
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
                            isUpdating = true
                            Toast.makeText(context, "Скачивание обновления APK...", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val ok = updateRepository.checkForUpdates()
                                isUpdating = false
                                if (!ok) {
                                    Toast.makeText(context, "Ошибка загрузки обновления", Toast.LENGTH_SHORT).show()
                                }
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Shortcuts Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(state.apps) { shortcut ->
                    ElevatedFilterChip(
                        selected = false,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            homeViewModel.launchShortcut(shortcut)
                        },
                        label = { Text(shortcut.title, color = Color.White) },
                        colors = FilterChipDefaults.elevatedFilterChipColors(
                            containerColor = Color(0xFF1C1C22)
                        )
                    )
                }
            }

            // Mode Tabs
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
                            selectedTab = index
                        },
                        text = { Text(title, color = Color.White) }
                    )
                }
            }

            // Central Control Area
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .border(2.dp, neonGradient, CircleShape)
                    .background(Color(0xFF14141A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when (selectedTab) {
                    0 -> {
                        // Circular D-Pad
                        Box(modifier = Modifier.fillMaxSize()) {
                            IconButton(
                                onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_UP) },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 14.dp)
                                    .size(56.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                            IconButton(
                                onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 14.dp)
                                    .size(56.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                            IconButton(
                                onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 14.dp)
                                    .size(56.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowLeft, "Left", tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                            IconButton(
                                onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 14.dp)
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Air Mouse / Тачпад", color = Color.White, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { pressKey(RemoteKeyCode.KEYCODE_DPAD_CENTER) }) {
                                    Text("ЛКМ")
                                }
                                OutlinedButton(onClick = { pressKey(RemoteKeyCode.KEYCODE_BACK) }) {
                                    Text("ПКМ")
                                }
                            }
                        }
                    }
                    2 -> {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = keyboardText,
                                onValueChange = { keyboardText = it },
                                label = { Text("Текст / Поиск") },
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pressKey(RemoteKeyCode.KEYCODE_DEL) }) {
                                    Text("⌫")
                                }
                                Button(onClick = { pressKey(RemoteKeyCode.KEYCODE_ENTER) }) {
                                    Text("Enter")
                                }
                            }
                        }
                    }
                }
            }

            // Navigation & Voice Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { pressKey(RemoteKeyCode.KEYCODE_BACK) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(2.dp, neonGradient, CircleShape)
                        .background(Color(0xFF1F1F28))
                        .clickable { pressKey(RemoteKeyCode.KEYCODE_VOICE_ASSIST) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Голос", tint = Color(0xFF00E5FF), modifier = Modifier.size(32.dp))
                }

                FilledTonalIconButton(
                    onClick = { pressKey(RemoteKeyCode.KEYCODE_HOME) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Домой")
                }
            }

            // Bottom Volume, Source & Exit Row
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
                IconButton(onClick = { pressKey(RemoteKeyCode.KEYCODE_TV_INPUT) }) {
                    Icon(Icons.Default.Input, contentDescription = "Источник (HDMI)", tint = Color(0xFF00E5FF))
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
}
