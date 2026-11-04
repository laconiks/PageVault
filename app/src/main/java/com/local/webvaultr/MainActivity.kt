package com.local.webvaultr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.local.webvaultr.ui.ArchiveDashboardScreen
import com.local.webvaultr.ui.ArchiveViewModel
import com.local.webvaultr.ui.theme.PocketArchiveTheme
import com.local.webvaultr.util.AppUiPreferences

class MainActivity : ComponentActivity() {

    private val viewModel: ArchiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { android.webkit.WebView.enableSlowWholeDocumentDraw() }
        super.onCreate(savedInstanceState)

        AppUiPreferences.init(applicationContext)

        handleIncomingShareIntent(intent)

        setContent {
            val themeMode by AppUiPreferences.themeMode.collectAsState()
            PocketArchiveTheme(themeMode = themeMode) {
                ArchiveDashboardScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action == Intent.ACTION_SEND && incomingIntent.type == "text/plain") {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val extractedUrl = extractFirstUrl(sharedText)
            if (!extractedUrl.isNullOrBlank()) {
                viewModel.captureAndIndexUrl(extractedUrl)
                // Clear EXTRA_TEXT so screen rotation doesn't re-trigger the same capture
                incomingIntent.removeExtra(Intent.EXTRA_TEXT)
            }
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val urlRegex = "(https?://[^\\s\"<>]+)".toRegex(RegexOption.IGNORE_CASE)
        return urlRegex.find(text)?.value ?: text.trim().takeIf { it.contains(".") && !it.contains(" ") }
    }
}
