package com.remote.control.ui.screens

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState(initial = emptyList())
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

    Scaffold(
        containerColor = Color(0xFF0A0A0C),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121216)
                ),
                navigationIcon = {
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = Color(0xFFFF3B30)
                        )
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Xiaomi TV",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = state.status,
                            color = if (state.isConnected) Color(0xFF34C759) else Color(0xFFFF3B30),
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    // Indicator
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = "Status",
                        tint = if (state.isConnected) Color(0xFF34C759) else Color(0xFFFF3B30),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                    IconButton(
                        enabled = !isUpdating,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isUpdating = true
                            Toast.makeText(context, "Скачивание обновления...", Toast.LENGTH_SHORT).show()
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
                            contentDescription = "Update APK",
                            tint = Color(0xFF00E5FF)
                        )
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
                items(state.apps) { app ->
                    ElevatedFilterChip(
                        selected = false,
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        label = { Text(app, color = Color.White) },
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
                    .size(240.dp)
                    .border(2.dp, neonGradient, CircleShape)
                    .background(Color(0xFF14141A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when (selectedTab) {
                    0 -> {
                        // Circular D-Pad
                        Box(modifier = Modifier.fillMaxSize()) {
                            IconButton(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconButton(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconButton(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowLeft, "Left", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconButton(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowRight, "Right", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .align(Alignment.Center)
                                    .clip(CircleShape)
                                    .background(Color(0xFF23232E))
                                    .border(1.5.dp, neonGradient, CircleShape)
                                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        }
                    }
                    1 -> {
                        Text("Air Mouse Touchpad\n(Gyro Active)", color = Color.White)
                    }
                    2 -> {
                        OutlinedTextField(
                            value = keyboardText,
                            onValueChange = { keyboardText = it },
                            label = { Text("Ввод текста на ТВ") },
                            modifier = Modifier.padding(16.dp)
                        )
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
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(2.dp, neonGradient, CircleShape)
                        .background(Color(0xFF1F1F28))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice", tint = Color(0xFF00E5FF), modifier = Modifier.size(32.dp))
                }

                FilledTonalIconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Home")
                }
            }

            // Bottom Volume & Source Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                    Text("VOL -")
                }
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                    Icon(Icons.Default.VolumeOff, contentDescription = "Mute", tint = Color.White)
                }
                Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                    Text("VOL +")
                }
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                    Icon(Icons.Default.Input, contentDescription = "Source", tint = Color.White)
                }
            }
        }
    }
}
