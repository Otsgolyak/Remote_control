package com.remote.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remote.control.data.ConnectionManagerImpl
import com.remote.control.data.bluetooth.AirMouseController
import com.remote.control.data.device.DeviceRepository
import com.remote.control.data.update.UpdateRepositoryImpl
import com.remote.control.data.wifi.NsdScanner
import com.remote.control.data.wifi.PairingRepositoryImpl
import com.remote.control.ui.HomeViewModel
import com.remote.control.ui.HomeViewModelFactory
import com.remote.control.ui.screens.HomeScreen
import com.remote.control.ui.screens.PairingScreen
import com.remote.control.ui.screens.SettingsScreen
import com.remote.control.utils.Logger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        UpdateRepositoryImpl(applicationContext).cleanupOldApks(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF6D00),
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF121216)
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
    val pairingRepository = remember { PairingRepositoryImpl() }
    val connectionManager = remember { ConnectionManagerImpl(pairingRepository) }
    val nsdScanner = remember { NsdScanner(context) }
    val airMouseController = remember { AirMouseController(context) }
    val viewModelFactory = remember {
        HomeViewModelFactory(
            repository,
            connectionManager,
            pairingRepository,
            nsdScanner,
            airMouseController
        )
    }
    val homeViewModel: HomeViewModel = viewModel(factory = viewModelFactory)

    NavHost(navController = navController, startDestination = "pairing") {
        composable("pairing") {
            PairingScreen(
                homeViewModel = homeViewModel,
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("pairing") { inclusive = false }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("home") {
            HomeScreen(
                homeViewModel = homeViewModel,
                onNavigateToPairing = {
                    navController.navigate("pairing")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                homeViewModel = homeViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
