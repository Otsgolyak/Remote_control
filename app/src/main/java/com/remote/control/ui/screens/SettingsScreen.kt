package com.remote.control.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.control.BuildConfig
import com.remote.control.data.update.UpdateRepositoryImpl
import com.remote.control.utils.Logger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logsText by Logger.logsFlow.collectAsState()
    val updateRepository = remember { UpdateRepositoryImpl(context) }
    var isUpdating by remember { mutableStateOf(false) }

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

            Button(
                enabled = !isUpdating,
                onClick = {
                    isUpdating = true
                    Toast.makeText(context, "Скачивание новой версии с GitHub...", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val ok = updateRepository.checkForUpdates()
                        isUpdating = false
                        if (!ok) {
                            Toast.makeText(context, "Ошибка скачивания обновления", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text(
                    if (isUpdating) "Скачивание APK..." else "Скачать и установить обновление с GitHub",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        Logger.sendLogsByEmail(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                ) {
                    Text("Отправить на почту", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RemoteLogs", logsText))
                        Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Копировать")
                }

                OutlinedButton(
                    onClick = { Logger.clearLogs() }
                ) {
                    Text("Очистить")
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
