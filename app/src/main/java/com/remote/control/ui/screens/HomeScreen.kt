package com.remote.control.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    // Dialog state for renaming
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val neonGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFF6D00), Color(0xFF00E5FF))
    )

    fun pressKey(keyCode: RemoteKeyCode) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        homeViewModel.sendKey(keyCode)
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать ТВ") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Новое имя") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    homeViewModel.renameDevice(state.selectedDeviceIp ?: "", newName)
                    showRenameDialog = false
                }) { Text("ОК") }
            }
        )
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0C),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121216)),
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showRenameDialog = true }
                    ) {
                        Text(state.selectedDeviceName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(state.status, color = if (state.isConnected) Color(0xFF34C759) else Color(0xFFFF3B30), fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                    IconButton(onClick = { (context as? Activity)?.finishAffinity() }) { Icon(Icons.Default.ExitToApp, "Exit", tint = Color(0xFFFF453A)) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            
            // Mode Tabs
            val tabs = listOf("D-Pad", "Air Mouse", "Keyboard")
            TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF16161C)) {
                tabs.forEachIndexed { i, t -> Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(t, color = Color.White) }) }
            }

            Box(
                modifier = Modifier
                    .size(250.dp)
                    .border(2.dp, neonGradient, CircleShape)
                    .background(Color(0xFF14141A), CircleShape)
                    .pointerInput(Unit) {
                        if (selectedTab == 1) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                // Mouse not supported in current protocol
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (selectedTab == 0) {
                     // ... D-Pad implementation (assume existing) ...
                } else if (selectedTab == 2) {
                     OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        label = { Text("Ввод") }
                    )
                }
            }
        }
    }
}
