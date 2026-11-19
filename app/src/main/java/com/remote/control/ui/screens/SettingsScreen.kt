package com.remote.control.ui.screens

import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Update Settings", style = MaterialTheme.typography.titleMedium)
            var updateEnabled by remember { mutableStateOf(true) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Auto-updates")
                Switch(checked = updateEnabled, onCheckedChange = { updateEnabled = it })
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("App Version 1.0.0")
            Text("Developed for Android TV Remote Control")
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = {
                val file = File(context.filesDir, "logs.txt")
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf("otsgolyak@gmail.com"))
                        putExtra(Intent.EXTRA_SUBJECT, "Remote Control Logs")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Send logs via"))
                }
            }) {
                Text("Send Logs")
            }
        }
    }
}
