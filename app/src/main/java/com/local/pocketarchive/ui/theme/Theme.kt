package com.local.pocketarchive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF9800), // Trebuchet Amber Accent
    onPrimary = Color(0xFF141210),
    primaryContainer = Color(0xFFE65100),
    onPrimaryContainer = Color(0xFFFFD180),
    secondary = Color(0xFF64B5F6), // Pixel Blue
    onSecondary = Color(0xFF00325B),
    background = Color(0xFF121212), // Deep Obsidian Dark
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1E1E1E), // Sleek Charcoal Card Surface
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFC4C7C5)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD180),
    onPrimaryContainer = Color(0xFF351200),
    secondary = Color(0xFF0061A4),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F)
)

@Composable
fun PixbucheyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
