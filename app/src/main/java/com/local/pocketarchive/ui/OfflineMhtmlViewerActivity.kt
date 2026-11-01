package com.local.pocketarchive.ui

import android.content.Context
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.local.pocketarchive.ui.theme.AppThemeMode
import com.local.pocketarchive.ui.theme.PixbucheyTheme
import com.local.pocketarchive.ui.theme.ThemePreferences
import java.io.File

class OfflineMhtmlViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MHTML_PATH = "extra_mhtml_path"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Archived Snapshot"
        val mhtmlPath = intent.getStringExtra(EXTRA_MHTML_PATH)
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val themePrefs = ThemePreferences(this)

        setContent {
            val mode by themePrefs.themeMode.collectAsState()
            val useDark = when (mode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }

            PixbucheyTheme(darkTheme = useDark) {
                var selectedTab by remember { mutableIntStateOf(0) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            navigationIcon = {
                                FilledTonalButton(
                                    onClick = { finish() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Back")
                                }
                            },
                            title = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        webViewRef?.let { printWebViewToPdf(this@OfflineMhtmlViewerActivity, it, title) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "Export MHTML as PDF",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(end = 8.dp)) {
                                    SegmentedButton(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                                    ) { Text("MHTML") }
                                    SegmentedButton(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                                    ) { Text("Screenshot") }
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        if (selectedTab == 0 && mhtmlPath != null) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = false
                                        settings.blockNetworkLoads = true
                                        settings.allowFileAccess = true
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        webViewClient = WebViewClient()
                                        loadUrl("file://$mhtmlPath")
                                        webViewRef = this
                                    }
                                }
                            )
                        } else if (screenshotPath != null) {
                            PinchZoomScreenshotView(screenshotPath = screenshotPath)
                        }
                    }
                }
            }
        }
    }

    private fun printWebViewToPdf(context: Context, webView: WebView, jobTitle: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = "Pixbuchey_${jobTitle.take(25)}"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }
}

@Composable
private fun PinchZoomScreenshotView(screenshotPath: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale > 1f) {
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-1500f, 1500f),
                            y = (offset.y + pan.y).coerceIn(-4000f, 4000f)
                        )
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .verticalScroll(scrollState)
    ) {
        AsyncImage(
            model = File(screenshotPath),
            contentDescription = "Full Page Zoomable Screenshot",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}
