package com.remote.control.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val logsText by Logger.logsFlow.collectAsState()
    val updateRepository = remember { UpdateRepositoryImpl(context) }
    val downloadProgress by updateRepository.downloadProgress.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0A0A0C),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121216)),
                title = { Text("Настройки и диагностика", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Xiaomi TV Remote v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.Bold
            )

            // Air Mouse Sensitivity Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Чувствительность аэромыши (Air Mouse): ${"%.1f".format(state.mouseSensitivity)}x",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Slider(
                        value = state.mouseSensitivity,
                        onValueChange = { homeViewModel.setSensitivity(it) },
                        valueRange = 0.5f..3.5f
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
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
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "v${BuildConfig.VERSION_NAME}",
                                color = Color(0xFF00E5FF),
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
                            color = Color(0xFF00E5FF),
                            trackColor = Color(0xFF242436)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${downloadProgress.percent}%",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${downloadProgress.downloadedMb} / ${downloadProgress.totalMb} МБ",
                                color = Color.Gray,
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (downloadProgress.isDownloading) "Загрузка обновления..." else "Проверить и обновить с GitHub",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        Logger.sendLogsByEmail(context)
                    },
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.18f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "На почту",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RemoteLogs", logsText))
                        Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFF2C2C3A)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Копия", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = { Logger.clearLogs() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF453A)),
                    border = BorderStroke(1.dp, Color(0xFF3A2024)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF453A),
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Сброс", fontSize = 12.sp)
                    }
                }
            }

            Text(
                "Журнал событий (logs.txt → otsgolyak@gmail.com):",
                color = Color.LightGray,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF14141C), RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = logsText.ifBlank { "Логи инициализированы..." },
                    color = Color(0xFF34C759),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}
