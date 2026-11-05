package com.local.webvaultr.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CameraAlt
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

    var allPages by remember { mutableStateOf<List<ArchivedPage>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }

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
                    if (idx >= 0) currentIndex = idx
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

    fun saveQaSnapshot() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching {
            val qaDir = File(context.filesDir, "qa_snapshots").apply { mkdirs() }
            val snapFile = File(qaDir, "qa_snap_${System.currentTimeMillis()}.png")

            val activity = context as? Activity
            val decorView = activity?.window?.decorView?.rootView
            if (decorView != null && decorView.width > 0 && decorView.height > 0) {
                val bmp = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                decorView.draw(canvas)
                FileOutputStream(snapFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                bmp.recycle()
                Toast.makeText(context, "📸 QA Snap saved: ${snapFile.name}", Toast.LENGTH_SHORT).show()
            } else if (!screenshotPath.isNullOrBlank() && File(screenshotPath).exists()) {
                File(screenshotPath).copyTo(snapFile, overwrite = true)
                Toast.makeText(context, "📸 QA Snap saved: ${snapFile.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Unable to capture QA snap", Toast.LENGTH_SHORT).show()
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
                    // 📸 QA Snap Button
                    IconButton(onClick = { saveQaSnapshot() }) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Take QA Snapshot",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }

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
        },
        bottomBar = {
            if (allPages.size > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                if (currentIndex > 0) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentIndex--
                                }
                            },
                            enabled = currentIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous Article")
                        }

                        Text(
                            text = "Article ${currentIndex + 1} of ${allPages.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(
                            onClick = {
                                if (currentIndex < allPages.size - 1) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentIndex++
                                }
                            },
                            enabled = currentIndex < allPages.size - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next Article")
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
            when (viewerMode) {
                ViewerMode.SCREENSHOT -> {
                    if (!screenshotPath.isNullOrBlank() && File(screenshotPath).exists()) {
                        ZoomableScreenshotCanvas(
                            screenshotPath = screenshotPath,
                            onSwipeLeft = {
                                if (currentIndex < allPages.size - 1) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentIndex++
                                }
                            },
                            onSwipeRight = {
                                if (currentIndex > 0) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentIndex--
                                }
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Screenshot file not available for this capture.")
                        }
                    }
                }
                ViewerMode.READER -> {
                    OfflineReaderExtractedView(
                        title = title,
                        fallbackText = readerText,
                        onSwipeLeft = {
                            if (currentIndex < allPages.size - 1) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentIndex++
                            }
                        },
                        onSwipeRight = {
                            if (currentIndex > 0) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentIndex--
                            }
                        }
                    )
                }
                ViewerMode.MHTML -> {
                    if (!mhtmlPath.isNullOrBlank() && File(mhtmlPath).exists()) {
                        OfflineMhtmlWebView(
                            key = mhtmlPath,
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
    fallbackText: String,
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {}
) {
    var dragAmount by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, drag -> dragAmount += drag },
                    onDragEnd = {
                        if (dragAmount < -60f) onSwipeLeft()
                        else if (dragAmount > 60f) onSwipeRight()
                        dragAmount = 0f
                    }
                )
            }
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
    screenshotPath: String,
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    var isZoomed by remember { mutableStateOf(false) }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    var dragAmount by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(verticalScrollState)
            .then(if (isZoomed) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
            .pointerInput(isZoomed) {
                if (!isZoomed) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, drag -> dragAmount += drag },
                        onDragEnd = {
                            if (dragAmount < -60f) onSwipeLeft()
                            else if (dragAmount > 60f) onSwipeRight()
                            dragAmount = 0f
                        }
                    )
                }
            }
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
