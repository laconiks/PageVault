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
                        // Strip residual subscriber modals, gates, sticky bars & unlock scroll inside loaded MHTML
                        val cleanupJs = """
                            (function() {
                                // 1. Remove ONLY true subscriber/piano/regwall modal overlays
                                var paywallSelectors = [
                                    '[class*="paywall-"]', '[id*="paywall"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                                    '[class*="piano-"]', '[id*="piano-"]', '[class*="regwall"]', '[data-qa*="paywall"]',
                                    '[class*="subscription-wall"]', '[class*="meter-wall"]', '[id*="gateway-content"]',
                                    // Substack modals & subscription popups
                                    '[class*="substack-modal"]', 'div.modal-wrapper', 'div.modal-table',
                                    '[class*="subscribe-modal"]', '[class*="paywall-banner"]',
                                    '[class*="post-preview-overlay"]', '[class*="paywall-boundary"]',
                                    '[class*="modal-overlay"]', '[class*="reader-popover"]',
                                    '[class*="subscriber-only-banner"]', '[class*="subscribe-widget"]',
                                    '[class*="pencraft"][role="dialog"]', 'div[class*="gate-container"]',
                                    // NBC News floating video players and modals
                                    '[class*="floating-video"]', '[class*="sticky-player"]', '[class*="mini-player"]'
                                ];
                                paywallSelectors.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                                            el.remove();
                                        }
                                    });
                                });

                                // 2. Ensure off-canvas mobile drawers (like NBC News blue menu) and state dropdowns stay hidden
                                var hiddenDrawers = [
                                    '[aria-hidden="true"]', '[class*="nav-drawer"]', '[class*="mobile-menu"]',
                                    '[class*="menu-drawer"]', '[class*="offcanvas"]', 'nav [class*="drawer"]',
                                    'header ul', 'nav ul', '[class*="state-list"]', '[class*="navigation__list"]',
                                    '[class*="quick-links"]', '[class*="global-nav__menu"]', '[class*="hamburger-menu"]',
                                    '[class*="msg-drawer"]', '[class*="drawer-container"]', 'header [role="dialog"]',
                                    '[class*="slide-out"]', '[class*="nav-panel"]'
                                ];
                                hiddenDrawers.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        el.style.setProperty('display', 'none', 'important');
                                    });
                                });

                                // 3. Text-Aware Gate Removal (The Verge, WaPo, Substack, etc.)
                                var gatePhrases = [
                                    "continue reading",
                                    "with a verge subscription",
                                    "two ways to read this article",
                                    "terms of use/your privacy rights",
                                    "go beyond the headline",
                                    "subscribe to the verge",
                                    "read in the substack app",
                                    "expand post",
                                    "sign up to keep reading",
                                    "subscribe to keep reading",
                                    "get the substack app",
                                    "read in app",
                                    "free preview",
                                    "this post is for paid subscribers",
                                    "upgrade to paid",
                                    "already a subscriber? sign in",
                                    "let's be inbox friends",
                                    "open in app",
                                    "open in the substack app",
                                    "continue in app"
                                ];
                                var potentialGates = document.querySelectorAll('div, section, aside, form, dialog, [role="dialog"], [role="alertdialog"]');
                                potentialGates.forEach(function(el) {
                                    if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                                    if (el.closest('article') && (el.innerText || '').length > 1200) return;
                                    var text = (el.innerText || '').toLowerCase();
                                    if (text.length > 0 && text.length < 1500) {
                                        for (var i = 0; i < gatePhrases.length; i++) {
                                            if (text.indexOf(gatePhrases[i]) !== -1) {
                                                el.remove();
                                                break;
                                            }
                                        }
                                    }
                                });

                                // 3b. Auto-dismiss or click Substack expand/dismiss buttons if present
                                document.querySelectorAll('button, a, div[role="button"]').forEach(function(btn) {
                                    var t = (btn.innerText || '').trim().toLowerCase();
                                    if (t === 'expand post' || t === 'expand' || t === 'continue reading' || t === 'no thanks' || t === 'read first') {
                                        try { btn.click(); } catch(e) {}
                                    }
                                });

                                // 4. Bottom-Anchored Sticky Bar & Backdrop Dimmer Removal
                                document.querySelectorAll('*').forEach(function(el) {
                                    if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                                    var style = window.getComputedStyle(el);
                                    var pos = style.position;
                                    if (pos === 'fixed' || pos === 'sticky') {
                                        var rect = el.getBoundingClientRect();
                                        if (rect.top > window.innerHeight * 0.40 && rect.height < window.innerHeight * 0.85) {
                                            el.remove();
                                            return;
                                        }
                                        var bg = style.backgroundColor;
                                        if (bg && (bg.indexOf('rgba') !== -1 || bg.indexOf('hsla') !== -1)) {
                                            if (rect.width >= window.innerWidth * 0.8 && rect.height >= window.innerHeight * 0.8) {
                                                el.remove();
                                                return;
                                            }
                                        }
                                    } else {
                                        var bg = style.backgroundColor;
                                        if (bg && (bg.indexOf('rgba') !== -1 || bg.indexOf('hsla') !== -1)) {
                                            var z = parseInt(style.zIndex, 10);
                                            if (z > 100) {
                                                var rect = el.getBoundingClientRect();
                                                if (rect.width >= window.innerWidth * 0.8 && rect.height >= window.innerHeight * 0.8) {
                                                    el.remove();
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                });

                                // 5. Unlock scroll & truncation ONLY on document and article containers (NEVER on '*')
                                var contentContainers = document.querySelectorAll(
                                    'html, body, main, article, [role="main"], [class*="article-body"], [class*="story-body"], [class*="article-content"], [class*="paywall-content"], [class*="post-content"], [class*="substack-post"], [class*="post-body"], [class*="body-markup"], [class*="single-post"], [class*="article-body__content"]'
                                );
                                contentContainers.forEach(function(el) {
                                    el.style.setProperty('overflow', 'visible', 'important');
                                    el.style.setProperty('overflow-y', 'visible', 'important');
                                    el.style.setProperty('max-height', 'none', 'important');
                                    el.style.setProperty('height', 'auto', 'important');
                                    el.style.setProperty('-webkit-mask-image', 'none', 'important');
                                    el.style.setProperty('mask-image', 'none', 'important');
                                });
                                document.documentElement.style.setProperty('overflow', 'auto', 'important');
                                document.body.style.setProperty('overflow', 'auto', 'important');
                                return document.body ? (document.body.innerText || '') : '';
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
