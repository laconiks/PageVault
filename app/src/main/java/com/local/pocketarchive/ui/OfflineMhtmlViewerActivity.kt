package com.local.pocketarchive.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.local.pocketarchive.data.local.ArchiveDatabase
import com.local.pocketarchive.ui.theme.PocketArchiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class ViewerMode { MHTML, SCREENSHOT, READER }

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
    var viewerMode by remember { mutableStateOf(ViewerMode.MHTML) }
    var readerText by remember { mutableStateOf("") }

    LaunchedEffect(originalUrl) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = ArchiveDatabase.getInstance(context).archiveDao()
                val page = dao.getLatestByUrl(originalUrl)
                if (page != null) {
                    val text = page.extractedTextSummary.ifBlank { page.aiSummary }
                    if (text.isNotBlank()) readerText = text
                }
            }
        }
    }

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
                            text = when (viewerMode) {
                                ViewerMode.MHTML -> "Offline MHTML Snapshot (Modals Stripped)"
                                ViewerMode.SCREENSHOT -> "Full-Page Vertical Screenshot (Scroll & Double-Tap Zoom)"
                                ViewerMode.READER -> "Clean Unblocked Reader View"
                            },
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
                    // Toggle Clean Reader View
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewerMode = if (viewerMode == ViewerMode.READER) ViewerMode.MHTML else ViewerMode.READER
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Clean Reader Mode",
                            tint = if (viewerMode == ViewerMode.READER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Toggle MHTML / Scrollable Screenshot
                    if (!screenshotPath.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewerMode = if (viewerMode == ViewerMode.SCREENSHOT) ViewerMode.MHTML else ViewerMode.SCREENSHOT
                            }
                        ) {
                            Icon(
                                imageVector = if (viewerMode == ViewerMode.SCREENSHOT) Icons.Default.Web else Icons.Default.Image,
                                contentDescription = "Toggle MHTML / Screenshot",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

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
            when (viewerMode) {
                ViewerMode.SCREENSHOT -> {
                    if (!screenshotPath.isNullOrBlank() && File(screenshotPath).exists()) {
                        ZoomableScreenshotCanvas(screenshotPath = screenshotPath)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Screenshot file not available for this capture.")
                        }
                    }
                }
                ViewerMode.READER -> {
                    OfflineReaderExtractedView(mhtmlPath = mhtmlPath, title = title, fallbackText = readerText)
                }
                ViewerMode.MHTML -> {
                    if (!mhtmlPath.isNullOrBlank() && File(mhtmlPath).exists()) {
                        OfflineMhtmlWebView(
                            mhtmlPath = mhtmlPath,
                            onExtractedTextReady = { extracted -> if (extracted.length > readerText.length) readerText = extracted }
                        )
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
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfflineMhtmlWebView(
    mhtmlPath: String,
    onExtractedTextReady: (String) -> Unit = {}
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    // Enable JS strictly for our local DOM-unmasking script while blocking all network loads
                    javaScriptEnabled = true
                    blockNetworkLoads = true
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
                    ): Boolean = true

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Strip residual paywall modals & unlock scroll inside the loaded MHTML file
                        val cleanupJs = """
                            (function() {
                                var badSelectors = [
                                    '[class*="paywall"]', '[id*="paywall"]', '[class*="modal"]',
                                    '[class*="gate"]', '[id*="gate"]', '[class*="overlay"]',
                                    '[class*="piano"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                                    '[class*="regwall"]', '[class*="subscribe"]', '[data-qa*="paywall"]',
                                    'iframe', '[class*="barrier"]', '[id*="barrier"]'
                                ];
                                badSelectors.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                                            if (el.innerText.length < 700 || el.style.position === 'fixed') {
                                                el.remove();
                                            }
                                        }
                                    });
                                });
                                document.querySelectorAll('*').forEach(function(el) {
                                    var st = window.getComputedStyle(el);
                                    if (st.position === 'fixed' || st.position === 'sticky') {
                                        el.style.position = 'static';
                                        if (el.innerText.length < 400) el.style.display = 'none';
                                    }
                                    if (st.overflow === 'hidden' || st.overflowY === 'hidden' || st.maxHeight !== 'none') {
                                        el.style.setProperty('overflow', 'visible', 'important');
                                        el.style.setProperty('overflow-y', 'visible', 'important');
                                        el.style.setProperty('max-height', 'none', 'important');
                                        el.style.setProperty('-webkit-mask-image', 'none', 'important');
                                        el.style.setProperty('mask-image', 'none', 'important');
                                    }
                                });
                                document.documentElement.style.setProperty('overflow', 'auto', 'important');
                                document.body.style.setProperty('overflow', 'auto', 'important');
                                document.body.style.setProperty('position', 'static', 'important');
                                return document.body.innerText || '';
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(cleanupJs) { rawResult ->
                            val unescaped = rawResult?.trim('"')?.replace("\\n", "\n") ?: ""
                            onExtractedTextReady(unescaped)
                        }
                    }
                }
                loadUrl(Uri.fromFile(File(mhtmlPath)).toString())
            }
        }
    )
}

@Composable
fun OfflineReaderExtractedView(
    @Suppress("UNUSED_PARAMETER") mhtmlPath: String?,
    title: String,
    fallbackText: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = fallbackText.ifBlank { "Switch to MHTML view for 1 second first to hydrate Reader text." },
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun ZoomableScreenshotCanvas(screenshotPath: String) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    var isZoomed by remember { mutableStateOf(false) }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(verticalScrollState)
            .then(if (isZoomed) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { isZoomed = !isZoomed }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        AsyncImage(
            model = File(screenshotPath),
            contentDescription = "Full-Page Scrollable Snapshot",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .width(if (isZoomed) screenWidthDp * 2f else screenWidthDp)
                .wrapContentHeight()
        )
    }
}
