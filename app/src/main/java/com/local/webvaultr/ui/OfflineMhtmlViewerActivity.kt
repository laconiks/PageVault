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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
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
import com.local.webvaultr.data.local.ArchiveDatabase
import com.local.webvaultr.data.local.ArchivedPage
import com.local.webvaultr.ui.theme.PocketArchiveTheme
import kotlinx.coroutines.Dispatchers
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

        setContent {
            PocketArchiveTheme {
                OfflineViewerScreen(
                    initialTitle = pageTitle,
                    initialUrl = originalUrl,
                    initialMhtmlPath = mhtmlPath,
                    initialScreenshotPath = screenshotPath,
                    initialUuid = pageUuid,
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var viewerMode by remember { mutableStateOf(ViewerMode.MHTML) }
    var showMenu by remember { mutableStateOf(false) }

    var allPages by remember { mutableStateOf<List<ArchivedPage>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = ArchiveDatabase.getInstance(context).archiveDao()
                val list = dao.getAllPagesSync()
                if (list.isNotEmpty()) {
                    allPages = list
                }
            }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { allPages.size.coerceAtLeast(1) }
    )

    LaunchedEffect(allPages) {
        if (allPages.isNotEmpty()) {
            val idx = allPages.indexOfFirst {
                (initialUuid.isNotBlank() && it.uuid == initialUuid) ||
                    (initialUrl.isNotBlank() && it.originalUrl.equals(initialUrl, ignoreCase = true)) ||
                    it.title.equals(initialTitle, ignoreCase = true)
            }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }

    val currentPage = allPages.getOrNull(pagerState.currentPage)
    val title = currentPage?.title ?: initialTitle
    val originalUrl = currentPage?.originalUrl ?: initialUrl
    val screenshotPath = currentPage?.screenshotFilePath ?: initialScreenshotPath

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
                                ViewerMode.MHTML -> "Offline MHTML Archive"
                                ViewerMode.SCREENSHOT -> "Full-Page Vertical Screenshot (Scroll & Zoom)"
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

                    // 3-Dot Overflow Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
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
                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) { pageIndex ->
            val page = allPages.getOrNull(pageIndex)
            val pageTitle = page?.title ?: initialTitle
            val pageMhtmlPath = page?.mhtmlFilePath ?: initialMhtmlPath
            val pageScreenshotPath = page?.screenshotFilePath ?: initialScreenshotPath
            val pageReaderText = page?.extractedTextSummary?.ifBlank { page.aiSummary } ?: ""

            when (viewerMode) {
                ViewerMode.SCREENSHOT -> {
                    if (!pageScreenshotPath.isNullOrBlank() && File(pageScreenshotPath).exists()) {
                        ZoomableScreenshotCanvas(screenshotPath = pageScreenshotPath)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Screenshot file not available for this capture.")
                        }
                    }
                }
                ViewerMode.READER -> {
                    OfflineReaderExtractedView(
                        title = pageTitle,
                        fallbackText = pageReaderText
                    )
                }
                ViewerMode.MHTML -> {
                    if (!pageMhtmlPath.isNullOrBlank() && File(pageMhtmlPath).exists()) {
                        OfflineMhtmlWebView(
                            key = pageMhtmlPath,
                            mhtmlPath = pageMhtmlPath
                        )
                    } else if (!pageScreenshotPath.isNullOrBlank() && File(pageScreenshotPath).exists()) {
                        ZoomableScreenshotCanvas(screenshotPath = pageScreenshotPath)
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
    key: String,
    mhtmlPath: String
) {
    key(key) {
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
                        blockNetworkLoads = true
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
                    loadUrl(Uri.fromFile(File(mhtmlPath)).toString())
                }
            }
        )
    }
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
