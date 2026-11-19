package com.remote.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remote.control.ui.HomeViewModel
import com.remote.control.ui.screens.HomeScreen
import com.remote.control.ui.screens.PairingScreen
import com.remote.control.ui.screens.SettingsScreen
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.remote.control.data.device.DeviceRepository
import com.remote.control.ui.HomeViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBB86FC),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val repository = remember { DeviceRepository(context) }
    val viewModelFactory = remember { HomeViewModelFactory(repository) }
    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory)

    NavHost(navController = navController, startDestination = "pairing") {
        composable("pairing") {
            PairingScreen(onNavigateToHome = {
                navController.navigate("home") {
                    popUpTo("pairing") { inclusive = true }
                }
            })
        }
        composable("home") {
            HomeScreen(
                homeViewModel = homeViewModel,
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("settings") {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
