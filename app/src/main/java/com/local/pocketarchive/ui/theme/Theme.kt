package com.local.pocketarchive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Cyber-Vault Dark Palette (Obsidian Slate + Laser Cyan + Vault Gold)
private val VaultDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),          // Cyber Laser Cyan
    onPrimary = Color(0xFF00262C),
    primaryContainer = Color(0xFF004E59),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFFFB300),        // Vault Lock Gold
    onSecondary = Color(0xFF3E2800),
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFDEA1),
    tertiary = Color(0xFF7C4DFF),         // Neural Edge Violet
    background = Color(0xFF090D16),       // Deep Obsidian Vault
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),          // Sleek Vault Card Surface
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),   // Elevated Glass Border/Pill
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFFF5252)
)

// Clean Vault Light Palette
private val VaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6EEFF),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFFB26A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA1),
    onSecondaryContainer = Color(0xFF2A1700),
    tertiary = Color(0xFF5E35B1),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFD32F2F)
)

@Composable
fun PocketArchiveTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (useDarkTheme) VaultDarkColorScheme else VaultLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}