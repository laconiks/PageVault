package com.local.pocketarchive

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.local.pocketarchive.ui.ArchiveDashboardScreen
import com.local.pocketarchive.ui.ArchiveViewModel
import com.local.pocketarchive.ui.theme.AppThemeMode
import com.local.pocketarchive.ui.theme.PixbucheyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ArchiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { WebView.enableSlowWholeDocumentDraw() }
        super.onCreate(savedInstanceState)
        setContent {
            val mode by viewModel.themeMode.collectAsState()
            val useDark = when (mode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            PixbucheyTheme(darkTheme = useDark) {
                ArchiveDashboardScreen(viewModel = viewModel)
            }
        }
    }
}
