package com.remote.control.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remote.control.data.update.UpdateRepository
import com.remote.control.ui.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("D-Pad", "Air Mouse", "Keyboard")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateRepository = remember { UpdateRepository(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Control") },
                actions = { 
                    IconButton(onClick = { scope.launch { updateRepository.checkForUpdates() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Update")
                    }
                    IconButton(onClick = {}) { Icon(Icons.Default.Menu, contentDescription = "Menu") } 
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(text = state.status, style = MaterialTheme.typography.bodyLarge)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Shortcuts
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.apps) { app ->
                    ElevatedFilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text(app) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Selected: ${tabs[selectedTab]}")
            }
        }
    }
}
