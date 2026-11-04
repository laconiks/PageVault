package com.local.webvaultr.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pixbuchey_prefs", Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(loadInitialMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private fun loadInitialMode(): AppThemeMode {
        val saved = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name
        return runCatching { AppThemeMode.valueOf(saved) }.getOrDefault(AppThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }
}
