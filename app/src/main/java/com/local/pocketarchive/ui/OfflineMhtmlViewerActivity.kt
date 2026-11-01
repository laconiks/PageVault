package com.local.pocketarchive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.local.pocketarchive.ui.theme.PocketArchiveTheme
import java.io.File

class OfflineMhtmlViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mhtmlPath = intent.getStringExtra("MHTML_PATH")
        val screenshotPath = intent.getStringExtra("SCREENSHOT_PATH")
        val pageTitle = intent.getStringExtra("PAGE_TITLE") ?: "Vault Snapshot"
        val originalUrl = intent.getStringExtra("ORIGINAL_URL") ?: ""

        setContent {
            PocketArchiveTheme {
                OfflineViewerScreen(
                    title = pageTitle,
                    originalUrl = originalUrl,
                    mhtmlPath = mhtmlPath,
                    screenshotPath = screenshotPath,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineViewerScreen(
    title: String,
    originalUrl: String,
    mhtmlPath: String?,
    screenshotPath: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showScreenshotMode by remember { mutableStateOf(false) }

    // System Back gesture / hardware back button with haptic feedback
    BackHandler {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (showScreenshotMode) "1080p Full-Canvas Screenshot (Pinch to Zoom)" else "Offline MHTML DOM Snapshot",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Vault"
                        )
                    }
                },
                actions = {
                    if (originalUrl.isNotBlank()) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                runCatching {
                                    val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)).apply {
                                        setPackage("com.android.chrome")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(chromeIntent)
                                    } catch (_: Exception) {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = "Open Live URL in Chrome",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (!screenshotPath.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showScreenshotMode = !showScreenshotMode
                            }
                        ) {
                            Icon(
                                imageVector = if (showScreenshotMode) Icons.Default.Web else Icons.Default.Image,
                                contentDescription = "Toggle MHTML / Screenshot",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (showScreenshotMode && !screenshotPath.isNullOrBlank()) {
                ZoomableScreenshotCanvas(screenshotPath = screenshotPath)
            } else if (!mhtmlPath.isNullOrBlank() && File(mhtmlPath).exists()) {
                OfflineMhtmlWebView(mhtmlPath = mhtmlPath)
            } else if (!screenshotPath.isNullOrBlank() && File(screenshotPath).exists()) {
                ZoomableScreenshotCanvas(screenshotPath = screenshotPath)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Snapshot file not found on disk.")
                }
            }
        }
    }
}

@Composable
fun OfflineMhtmlWebView(mhtmlPath: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = true
                    allowContentAccess = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = true // Keep strictly offline inside MHTML viewer
                }
                loadUrl(Uri.fromFile(File(mhtmlPath)).toString())
            }
        }
    )
}

@Composable
fun ZoomableScreenshotCanvas(screenshotPath: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offset += panChange
        } else {
            offset = Offset.Zero
        }
    }

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
            .transformable(state = transformableState),
        contentAlignment = Alignment.TopCenter
    ) {
        AsyncImage(
            model = File(screenshotPath),
            contentDescription = "Full-Page Snapshot",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}