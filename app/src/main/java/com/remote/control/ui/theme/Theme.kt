package com.remote.control.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Расширенная палитра для пульта ДУ (кнопки D-Pad, тачпад, статусбары, карточки).
 */
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val dpadBackground: Color,
    val dpadButton: Color,
    val dpadOk: Color,
    val dpadIcon: Color,
    val touchpadBackground: Color,
    val touchpadBorder: Color,
    val touchpadCursor: Color,
    val primary: Color,
    val secondary: Color,
    val actionButton: Color,
    val actionButtonText: Color,
    val divider: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val topBarBackground: Color
)

val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF121216),
    cardBackground = Color(0xFF161622),
    cardBorder = Color(0xFF282838),
    textPrimary = Color.White,
    textSecondary = Color(0xFFAAAAAA),
    dpadBackground = Color(0xFF14141A),
    dpadButton = Color(0xFF1A1A24),
    dpadOk = Color(0xFF23232E),
    dpadIcon = Color.White,
    touchpadBackground = Color(0xFF14141C),
    touchpadBorder = Color(0xFF282838),
    touchpadCursor = Color(0xFFFF6D00),
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFFFF6D00),
    actionButton = Color(0xFF222230),
    actionButtonText = Color.White,
    divider = Color(0xFF2C2C3A),
    success = Color(0xFF34C759),
    error = Color(0xFFFF453A),
    warning = Color(0xFFFF9500),
    topBarBackground = Color(0xFF121216)
)

val LightAppColors = AppColors(
    isDark = false,
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF1A202C),
    textSecondary = Color(0xFF718096),
    dpadBackground = Color(0xFFE2E8F0),
    dpadButton = Color(0xFFEDF2F7),
    dpadOk = Color(0xFFCBD5E0),
    dpadIcon = Color(0xFF2D3748),
    touchpadBackground = Color(0xFFFFFFFF),
    touchpadBorder = Color(0xFFCBD5E0),
    touchpadCursor = Color(0xFFFF6D00),
    primary = Color(0xFF00838F),      // Глубокий бирюзовый для отличной контрастности на светлом
    secondary = Color(0xFFE65100),    // Тёплый оранжевый
    actionButton = Color(0xFFE2E8F0),
    actionButtonText = Color(0xFF2D3748),
    divider = Color(0xFFE2E8F0),
    success = Color(0xFF2E7D32),
    error = Color(0xFFD32F2F),
    warning = Color(0xFFED6C02),
    topBarBackground = Color(0xFFFFFFFF)
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

val AppDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFFFF6D00),
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF121216),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

val AppLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF00838F),
    secondary = Color(0xFFE65100),
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A202C),
    onSurface = Color(0xFF1A202C)
)
