package com.local.webvaultr.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
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
import com.local.webvaultr.ai.OnDeviceAiSummarizer
import com.local.webvaultr.data.local.ArchiveDatabase
import com.local.webvaultr.data.local.ArchivedPage
import com.local.webvaultr.ui.theme.PocketArchiveTheme
import com.local.webvaultr.ui.theme.ThemeMode
import com.local.webvaultr.util.AppUiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class ViewerMode { MHTML, SCREENSHOT, READER }

class OfflineMhtmlViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mhtmlPath = intent.getStringExtra("MHTML_PATH")
        val screenshotPath = intent.getStringExtra("SCREENSHOT_PATH")
        val pageTitle = intent.getStringExtra("PAGE_TITLE") ?: "Vault Snapshot"
        val originalUrl = intent.getStringExtra("ORIGINAL_URL") ?: ""
        val pageUuid = intent.getStringExtra("PAGE_UUID") ?: ""

        AppUiPreferences.init(applicationContext)

        setContent {
            val themeMode by AppUiPreferences.themeMode.collectAsState()
            PocketArchiveTheme(themeMode = themeMode) {
                OfflineViewerScreen(
                    initialTitle = pageTitle,
                    initialUrl = originalUrl,
                    initialMhtmlPath = mhtmlPath,
                    initialScreenshotPath = screenshotPath,
                    initialUuid = pageUuid,
                    themeMode = themeMode,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineViewerScreen(
    initialTitle: String,
    initialUrl: String,
    initialMhtmlPath: String?,
    initialScreenshotPath: String?,
    initialUuid: String,
    themeMode: ThemeMode = ThemeMode.CYBER,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var viewerMode by remember { mutableStateOf(ViewerMode.MHTML) }
    var showMenu by remember { mutableStateOf(false) }

    var allPages by remember { mutableStateOf<List<ArchivedPage>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var aiTakeawaysText by remember { mutableStateOf<String?>(null) }
    var isGeneratingTakeaways by remember { mutableStateOf(false) }

    val isSystemDark = isSystemInDarkTheme()
    val effectiveMode = when (themeMode) {
        ThemeMode.SYSTEM -> if (isSystemDark) ThemeMode.DARK else ThemeMode.LIGHT
        else -> themeMode
    }

    val (barContainerColor, barContentColor, barAccentColor) = when (effectiveMode) {
        ThemeMode.LIGHT -> Triple(
            Color(0xFFF1F5F9), // crisp light slate
            Color(0xFF0F172A), // deep slate text/icons
            Color(0xFF0284C7)  // vibrant ocean blue
        )
        ThemeMode.DARK -> Triple(
            Color(0xFF1E293B),
            Color(0xFFF8FAFC),
            Color(0xFF38BDF8)  // sky blue accent
        )
        else -> Triple(
            Color(0xFF0C0D14),
            Color(0xFFF8FAFC),
            Color(0xFFFCEE09)  // Cyber yellow accent
        )
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = ArchiveDatabase.getInstance(context).archiveDao()
                val list = dao.getAllPagesSync()
                if (list.isNotEmpty()) {
                    allPages = list
                    val idx = list.indexOfFirst {
                        (initialUuid.isNotBlank() && it.uuid == initialUuid) ||
                            (initialUrl.isNotBlank() && it.originalUrl.equals(initialUrl, ignoreCase = true)) ||
                            it.title.equals(initialTitle, ignoreCase = true)
                    }
                    if (idx >= 0) {
                        currentIndex = idx
                    }
                }
            }
        }
    }

    val currentPage = allPages.getOrNull(currentIndex)
    val title = currentPage?.title ?: initialTitle
    val originalUrl = currentPage?.originalUrl ?: initialUrl
    val mhtmlPath = currentPage?.mhtmlFilePath ?: initialMhtmlPath
    val screenshotPath = currentPage?.screenshotFilePath ?: initialScreenshotPath
    val readerText = currentPage?.extractedTextSummary?.ifBlank { currentPage.aiSummary } ?: ""

    fun saveQaSnapshot(targetScreenshotPath: String?) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching {
            val qaDir = File(context.filesDir, "qa_snapshots").apply { mkdirs() }
            val snapFile = File(qaDir, "qa_snap_${System.currentTimeMillis()}.png")

            val activity = context as? Activity
            val window = activity?.window
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val decorView = window.decorView
                val width = decorView.width.coerceAtLeast(1)
                val height = decorView.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val location = IntArray(2)
                decorView.getLocationInWindow(location)
                val rect = Rect(
                    location[0],
                    location[1],
                    location[0] + width,
                    location[1] + height
                )
                PixelCopy.request(window, rect, bitmap, { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        runCatching {
                            FileOutputStream(snapFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                            }
                        }
                        bitmap.recycle()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "📸 QA Snap saved: ${snapFile.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        bitmap.recycle()
                        fallbackQaSnap(context, snapFile, targetScreenshotPath)
                    }
                }, Handler(Looper.getMainLooper()))
            } else {
                fallbackQaSnap(context, snapFile, targetScreenshotPath)
            }
        }.onFailure { err ->
            Toast.makeText(context, "QA Snap error: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barContainerColor,
                    titleContentColor = barContentColor,
                    navigationIconContentColor = barContentColor,
                    actionIconContentColor = barContentColor
                ),
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = barContentColor
                        )
                        val modeLabel = when (viewerMode) {
                            ViewerMode.MHTML -> "Offline MHTML Archive"
                            ViewerMode.SCREENSHOT -> "Full-Page Vertical Screenshot (Scroll & Zoom)"
                            ViewerMode.READER -> "Clean Unblocked Reader View"
                        }
                        Text(
                            text = if (allPages.size > 1) {
                                "${currentIndex + 1} of ${allPages.size} • $modeLabel"
                            } else {
                                modeLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = barAccentColor
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
                            contentDescription = "Back to Vault",
                            tint = barContentColor
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
                            tint = if (viewerMode == ViewerMode.READER) barAccentColor else barContentColor
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
                                tint = if (viewerMode == ViewerMode.SCREENSHOT) barAccentColor else barContentColor
                            )
                        }
                    }

                    // 3-Dot Overflow Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = barContentColor
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("✨ AI Key Takeaways & Entities") },
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = barAccentColor)
                                },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        isGeneratingTakeaways = true
                                        val text = OnDeviceAiSummarizer(context).generateArticleTakeaways(
                                            title = currentPage?.title ?: title,
                                            bodyText = currentPage?.extractedTextSummary?.ifBlank { readerText } ?: readerText
                                        )
                                        aiTakeawaysText = text
                                        isGeneratingTakeaways = false
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📸 Capture QA Snapshot") },
                                leadingIcon = {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                },
                                onClick = {
                                    showMenu = false
                                    saveQaSnapshot(screenshotPath)
                                }
                            )
                            if (originalUrl.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("🌐 Open Live Webpage") },
                                    leadingIcon = {
                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = barAccentColor)
                                    },
                                    onClick = {
                                        showMenu = false
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
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (allPages.size > 1) {
                Surface(
                    color = barContainerColor,
                    contentColor = barContentColor,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (currentIndex > 0) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    aiTakeawaysText = null
                                    currentIndex--
                                }
                            },
                            enabled = currentIndex > 0,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = barAccentColor,
                                disabledContentColor = barContentColor.copy(alpha = 0.35f)
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                            Spacer(Modifier.width(4.dp))
                            Text("Previous", fontWeight = FontWeight.SemiBold)
                        }

                        Text(
                            text = "Article ${currentIndex + 1} of ${allPages.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = barAccentColor,
                            fontWeight = FontWeight.Bold
                        )

                        TextButton(
                            onClick = {
                                if (currentIndex < allPages.size - 1) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    aiTakeawaysText = null
                                    currentIndex++
                                }
                            },
                            enabled = currentIndex < allPages.size - 1,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = barAccentColor,
                                disabledContentColor = barContentColor.copy(alpha = 0.35f)
                            )
                        ) {
                            Text("Next", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isGeneratingTakeaways) {
                    Surface(
                        color = barContainerColor,
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, barAccentColor.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = barAccentColor
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Generating Executive Takeaways & Figures...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = barContentColor
                            )
                        }
                    }
                } else if (aiTakeawaysText != null) {
                    Surface(
                        color = barContainerColor,
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .animateContentSize(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.2.dp, barAccentColor)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "✨ On-Device AI Takeaways",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = barAccentColor
                                )
                                IconButton(
                                    onClick = { aiTakeawaysText = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = barContentColor.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    text = aiTakeawaysText.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    color = barContentColor
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val stableKey = currentPage?.uuid ?: mhtmlPath ?: screenshotPath ?: "viewer_surface"
                    key(stableKey) {
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
                                OfflineReaderExtractedView(
                                    title = title,
                                    fallbackText = readerText
                                )
                            }
                            ViewerMode.MHTML -> {
                                if (!mhtmlPath.isNullOrBlank() && File(mhtmlPath).exists()) {
                                    OfflineMhtmlWebView(
                                        pageUuid = currentPage?.uuid ?: stableKey,
                                        mhtmlPath = mhtmlPath
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
        }
    }
}

private fun fallbackQaSnap(context: Context, snapFile: File, screenshotPath: String?) {
    val activity = context as? Activity
    val decorView = activity?.window?.decorView?.rootView
    if (decorView != null && decorView.width > 0 && decorView.height > 0) {
        runCatching {
            val bmp = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            decorView.draw(canvas)
            FileOutputStream(snapFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bmp.recycle()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "📸 QA Snap saved: ${snapFile.name}", Toast.LENGTH_SHORT).show()
            }
            return
        }
    }

    if (!screenshotPath.isNullOrBlank() && File(screenshotPath).exists()) {
        runCatching {
            val srcFile = File(screenshotPath)
            srcFile.copyTo(snapFile, overwrite = true)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "📸 QA Snap saved: ${snapFile.name}", Toast.LENGTH_SHORT).show()
            }
            return
        }
    }

    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, "Unable to capture QA snap", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun OfflineMhtmlWebView(
    pageUuid: String,
    mhtmlPath: String
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = false
                    domStorageEnabled = false
                    loadsImagesAutomatically = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    blockNetworkLoads = false
                    allowFileAccess = true
                    allowContentAccess = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = true
                }
            }
        },
        update = { webView ->
            if (webView.tag != pageUuid) {
                webView.tag = pageUuid
                val file = File(mhtmlPath)
                val headerSnippet = runCatching {
                    file.bufferedReader().use { reader ->
                        val buf = CharArray(256)
                        val read = reader.read(buf)
                        if (read > 0) String(buf, 0, read).trim().lowercase() else ""
                    }
                }.getOrDefault("")

                if (headerSnippet.startsWith("<!doctype") || headerSnippet.startsWith("<html") || headerSnippet.startsWith("<div")) {
                    val fullHtml = runCatching { file.readText() }.getOrDefault("")
                    webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                } else {
                    webView.loadUrl(Uri.fromFile(file).toString())
                }
            }
        }
    )
}

@Composable
fun OfflineReaderExtractedView(
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
            text = fallbackText.ifBlank { "No reader text available for this capture." },
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun ZoomableScreenshotCanvas(
    screenshotPath: String
) {
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
