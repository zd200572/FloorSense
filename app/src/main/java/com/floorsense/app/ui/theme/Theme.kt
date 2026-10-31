package com.floorsense.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF00696E),
    tertiary = Color(0xFFB3005E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    primaryContainer = Color(0xFF0D3AA0),
    secondary = Color(0xFF4FD8E0),
    tertiary = Color(0xFFFFB1C8)
)

@Composable
fun FloorSenseTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
