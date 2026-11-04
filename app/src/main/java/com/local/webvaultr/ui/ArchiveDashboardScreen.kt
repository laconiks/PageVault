package com.local.webvaultr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.webvaultr.data.local.ArchivedPage
import com.local.webvaultr.data.local.CaptureStatus
import com.local.webvaultr.ui.components.*
import com.local.webvaultr.util.AppUiPreferences
import com.local.webvaultr.util.ExportAndShareHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ArticleChain(
    val latestPage: ArchivedPage,
    val revisions: List<ArchivedPage>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveDashboardScreen(viewModel: ArchiveViewModel) {
    val context = LocalContext.current
    val localView = LocalView.current
    val scope = rememberCoroutineScope()
    val exportHelper = remember { ExportAndShareHelper(context) }

    val pages by viewModel.archivedPages.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val capturingUrl by viewModel.capturingUrl.collectAsState()
    val selectedUuids by viewModel.selectedUuids.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val isGroupedByWebsite by viewModel.isGroupedByWebsite.collectAsState()
    val isOneSentenceView by viewModel.isOneSentenceView.collectAsState()
    val queuedUrls by viewModel.queuedUrls.collectAsState()
    val captureStageText by viewModel.captureStageText.collectAsState()
    val rssSubscriptions by viewModel.rssSubscriptions.collectAsState()
    val currentAiModel by viewModel.aiModel.collectAsState()
    val showEdgeOnboarding by viewModel.showEdgeOnboarding.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRssDialog by remember { mutableStateOf(false) }
    var viewingMarkdownPage by remember { mutableStateOf<ArchivedPage?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Listen for UI alerts and show pop-up Snackbar
    LaunchedEffect(Unit) {
        viewModel.uiAlerts.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = "⚡ $message",
                duration = SnackbarDuration.Short
            )
        }
    }

    // Deduplication check: Suppress top banner if an existing or pending card in the list already shows in-card progress
    val isCardAlreadyRendering = remember(capturingUrl, pages) {
        capturingUrl != null && pages.any {
            it.originalUrl.equals(capturingUrl, ignoreCase = true) || it.status == CaptureStatus.PENDING
        }
    }

    // Group revisions by chain root (parentUuid ?: uuid) so v2, v3, etc. collapse into the main card
    val articleChains = remember(pages) {
        val map = LinkedHashMap<String, MutableList<ArchivedPage>>()
        for (page in pages) {
            val rootKey = page.parentUuid ?: page.uuid
            map.getOrPut(rootKey) { mutableListOf() }.add(page)
        }
        map.values.map { chainList ->
            val sorted = chainList.sortedByDescending { it.versionNumber }
            ArticleChain(
                latestPage = sorted.first(),
                revisions = sorted
            )
        }
    }

    val groupedChains = remember(articleChains) {
        articleChains.groupBy { chain ->
            chain.latestPage.siteName.ifBlank {
                runCatching { Uri.parse(chain.latestPage.originalUrl).host?.removePrefix("www.") ?: "Other Sites" }
                    .getOrDefault("Other Sites")
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        topBar = {
            if (selectedUuids.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selectedUuids.size} Selected", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            val selectedPages = pages.filter { selectedUuids.contains(it.uuid) }
                            scope.launch {
                                val mdFile = exportHelper.generateBatchMarkdown(selectedPages)
                                if (mdFile != null) {
                                    exportHelper.shareFilesToDriveOrApps(listOf(mdFile), "text/markdown")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to generate Markdown batch export")
                                }
                                viewModel.clearSelection()
                            }
                        }) {
                            Icon(Icons.Default.Description, contentDescription = "Export Batch Markdown")
                        }
                        IconButton(onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            val selectedPages = pages.filter { selectedUuids.contains(it.uuid) }
                            scope.launch {
                                val pdfFile = exportHelper.generateBatchPdf(selectedPages)
                                if (pdfFile != null) {
                                    exportHelper.shareFilesToDriveOrApps(listOf(pdfFile), "application/pdf")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to generate PDF batch export")
                                }
                                viewModel.clearSelection()
                            }
                        }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Export Batch PDF")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "WebVaultr",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("WebVaultr", fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "LOCAL AI EDGE ARCHIVER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            showRssDialog = true
                        }) {
                            BadgedBox(
                                badge = {
                                    if (rssSubscriptions.isNotEmpty()) {
                                        Badge { Text("${rssSubscriptions.size}") }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = "RSS Auto-Scraper",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        IconButton(onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            showSettingsDialog = true
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings & About")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp)
        ) {
            // Sleek Vault Intake Box: "Insert Page for the Vault" + "+ Queue" + "Vault"
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("Insert Page for the Vault") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                AppUiPreferences.triggerHaptic(localView)
                                viewModel.addToQueue(urlInput)
                                urlInput = ""
                            }
                        },
                        enabled = urlInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text("+ Queue", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                AppUiPreferences.triggerHaptic(localView)
                                viewModel.captureAndIndexUrl(urlInput)
                                urlInput = ""
                            }
                        },
                        enabled = !isCapturing && urlInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (isCapturing) {
                            GeminiRainbowSpinner(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Vault", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Page Queue Tray (If multiple URLs are queued)
            if (queuedUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Page Queue (${queuedUrls.size} pending)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = {
                                    AppUiPreferences.triggerHaptic(localView)
                                    viewModel.clearQueue()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            queuedUrls.forEach { qUrl ->
                                val domain = runCatching { Uri.parse(qUrl).host?.removePrefix("www.") ?: qUrl }.getOrDefault(qUrl)
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        AppUiPreferences.triggerHaptic(localView)
                                        viewModel.removeFromQueue(qUrl)
                                    },
                                    label = { Text(domain, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                AppUiPreferences.triggerHaptic(localView)
                                viewModel.processQueue()
                            },
                            enabled = !isCapturing,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCapturing) {
                                GeminiRainbowSpinner(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Processing Queue...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Vault All Queued (${queuedUrls.size})", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Dynamic Live Capture Progress Banner (Deduplicated: hidden if card is already rendering)
            if (isCapturing && !isCardAlreadyRendering) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GeminiRainbowSpinner(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = captureStageText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DOM locking freezes all styles, scripts, and media into an RFC-compliant snapshot for 100% offline access with zero server dependency.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar + Filter Chips: "By Site" + "1-Sentence" / "Full Cards"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Search vault...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                AppUiPreferences.triggerHaptic(localView)
                                viewModel.updateSearchQuery("")
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = isGroupedByWebsite,
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        viewModel.toggleGroupByWebsite()
                    },
                    label = { Text("By Site") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isGroupedByWebsite) Icons.Default.FolderSpecial else Icons.Default.ViewStream,
                            contentDescription = "Group by Website",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = isOneSentenceView,
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        viewModel.toggleOneSentenceView()
                    },
                    label = { Text(if (isOneSentenceView) "1-Sentence" else "Full") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isOneSentenceView) Icons.Default.ShortText else Icons.Default.ViewAgenda,
                            contentDescription = "Toggle 1-Sentence Compact View",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Articles Feed or Empty State
            if (articleChains.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.EnhancedEncryption,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching articles found for '$searchQuery'" else "Your WebVaultr is Empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "Try searching different keywords, tags, or domain names" else "Insert a page above or use Chrome -> Share -> WebVaultr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (isGroupedByWebsite) {
                        groupedChains.forEach { (siteGroup, siteChains) ->
                            stickyHeader {
                                Surface(
                                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = null,
                                            tint = getDomainAccentColor(siteGroup),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${siteGroup.uppercase()} (${siteChains.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = getDomainAccentColor(siteGroup)
                                        )
                                    }
                                }
                            }
                            items(siteChains, key = { it.latestPage.uuid }) { chain ->
                                val page = chain.latestPage
                                if (isOneSentenceView) {
                                    VaultCompactRow(
                                        page = page,
                                        isSelected = selectedUuids.contains(page.uuid),
                                        onCardClick = {
                                            if (selectedUuids.isNotEmpty()) {
                                                viewModel.toggleSelection(page.uuid)
                                            } else {
                                                val intent = Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                                                    putExtra("MHTML_PATH", page.mhtmlFilePath)
                                                    putExtra("SCREENSHOT_PATH", page.screenshotFilePath)
                                                    putExtra("PAGE_TITLE", page.title)
                                                    putExtra("ORIGINAL_URL", page.originalUrl)
                                                }
                                                context.startActivity(intent)
                                            }
                                        },
                                        onCardLongClick = { viewModel.toggleSelection(page.uuid) },
                                        onExportPdf = {
                                            scope.launch {
                                                val pdf = exportHelper.generateSinglePagePdf(page)
                                                if (pdf != null) {
                                                    exportHelper.shareFilesToDriveOrApps(listOf(pdf), "application/pdf")
                                                } else {
                                                    snackbarHostState.showSnackbar("Failed to generate PDF")
                                                }
                                            }
                                        },
                                        onExportMarkdown = {
                                            scope.launch {
                                                val md = exportHelper.generateSinglePageMarkdown(page)
                                                if (md != null) {
                                                    exportHelper.shareFilesToDriveOrApps(listOf(md), "text/markdown")
                                                } else {
                                                    snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                }
                                            }
                                        },
                                        onOpenMarkdown = { viewingMarkdownPage = it },
                                        onDelete = { viewModel.deleteArchive(page) }
                                    )
                                } else {
                                    VaultArticleCard(
                                        page = page,
                                        revisions = chain.revisions,
                                        isSelected = selectedUuids.contains(page.uuid),
                                        captureStageText = captureStageText,
                                        onCardClick = {
                                            if (selectedUuids.isNotEmpty()) {
                                                viewModel.toggleSelection(page.uuid)
                                            } else {
                                                val intent = Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                                                    putExtra("MHTML_PATH", page.mhtmlFilePath)
                                                    putExtra("SCREENSHOT_PATH", page.screenshotFilePath)
                                                    putExtra("PAGE_TITLE", page.title)
                                                    putExtra("ORIGINAL_URL", page.originalUrl)
                                                }
                                                context.startActivity(intent)
                                            }
                                        },
                                        onCardLongClick = { viewModel.toggleSelection(page.uuid) },
                                        onExportPdf = {
                                            scope.launch {
                                                val pdf = exportHelper.generateSinglePagePdf(page)
                                                if (pdf != null) {
                                                    exportHelper.shareFilesToDriveOrApps(listOf(pdf), "application/pdf")
                                                } else {
                                                    snackbarHostState.showSnackbar("Failed to generate PDF")
                                                }
                                            }
                                        },
                                        onDelete = { viewModel.deleteArchive(page) },
                                        onRecaptureNoJs = {
                                            viewModel.captureAndIndexUrl(page.originalUrl, spoofCrawler = true, disableJs = true)
                                        },
                                        onRepull = { viewModel.repullAndCompareArticle(page) },
                                        onExportMarkdown = {
                                            scope.launch {
                                                val md = exportHelper.generateSinglePageMarkdown(page)
                                                if (md != null) {
                                                    exportHelper.shareFilesToDriveOrApps(listOf(md), "text/markdown")
                                                } else {
                                                    snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                }
                                            }
                                        },
                                        onOpenMarkdown = { viewingMarkdownPage = it }
                                    )
                                }
                            }
                        }
                    } else {
                        items(articleChains, key = { it.latestPage.uuid }) { chain ->
                            val page = chain.latestPage
                            if (isOneSentenceView) {
                                VaultCompactRow(
                                    page = page,
                                    isSelected = selectedUuids.contains(page.uuid),
                                    onCardClick = {
                                        if (selectedUuids.isNotEmpty()) {
                                            viewModel.toggleSelection(page.uuid)
                                        } else {
                                            val intent = Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                                                putExtra("MHTML_PATH", page.mhtmlFilePath)
                                                putExtra("SCREENSHOT_PATH", page.screenshotFilePath)
                                                putExtra("PAGE_TITLE", page.title)
                                                putExtra("ORIGINAL_URL", page.originalUrl)
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    onCardLongClick = { viewModel.toggleSelection(page.uuid) },
                                    onExportPdf = {
                                        scope.launch {
                                            val pdf = exportHelper.generateSinglePagePdf(page)
                                            if (pdf != null) {
                                                exportHelper.shareFilesToDriveOrApps(listOf(pdf), "application/pdf")
                                            } else {
                                                snackbarHostState.showSnackbar("Failed to generate PDF")
                                            }
                                        }
                                    },
                                    onExportMarkdown = {
                                        scope.launch {
                                            val md = exportHelper.generateSinglePageMarkdown(page)
                                            if (md != null) {
                                                exportHelper.shareFilesToDriveOrApps(listOf(md), "text/markdown")
                                            } else {
                                                snackbarHostState.showSnackbar("Failed to generate Markdown")
                                            }
                                        }
                                    },
                                    onOpenMarkdown = { viewingMarkdownPage = it },
                                    onDelete = { viewModel.deleteArchive(page) }
                                )
                            } else {
                                VaultArticleCard(
                                    page = page,
                                    revisions = chain.revisions,
                                    isSelected = selectedUuids.contains(page.uuid),
                                    captureStageText = captureStageText,
                                    onCardClick = {
                                        if (selectedUuids.isNotEmpty()) {
                                            viewModel.toggleSelection(page.uuid)
                                        } else {
                                            val intent = Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                                                putExtra("MHTML_PATH", page.mhtmlFilePath)
                                                putExtra("SCREENSHOT_PATH", page.screenshotFilePath)
                                                putExtra("PAGE_TITLE", page.title)
                                                putExtra("ORIGINAL_URL", page.originalUrl)
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    onCardLongClick = { viewModel.toggleSelection(page.uuid) },
                                    onExportPdf = {
                                        scope.launch {
                                            val pdf = exportHelper.generateSinglePagePdf(page)
                                            if (pdf != null) {
                                                exportHelper.shareFilesToDriveOrApps(listOf(pdf), "application/pdf")
                                            } else {
                                                snackbarHostState.showSnackbar("Failed to generate PDF")
                                            }
                                        }
                                    },
                                    onDelete = { viewModel.deleteArchive(page) },
                                    onRecaptureNoJs = {
                                        viewModel.captureAndIndexUrl(page.originalUrl, spoofCrawler = true, disableJs = true)
                                    },
                                    onRepull = { viewModel.repullAndCompareArticle(page) },
                                    onExportMarkdown = {
                                        scope.launch {
                                            val md = exportHelper.generateSinglePageMarkdown(page)
                                            if (md != null) {
                                                exportHelper.shareFilesToDriveOrApps(listOf(md), "text/markdown")
                                            } else {
                                                snackbarHostState.showSnackbar("Failed to generate Markdown")
                                            }
                                        }
                                    },
                                    onOpenMarkdown = { viewingMarkdownPage = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // In-App Markdown Viewer Dialog
    viewingMarkdownPage?.let { page ->
        MarkdownViewerDialog(
            page = page,
            onDismiss = { viewingMarkdownPage = null },
            onShareMarkdown = {
                scope.launch {
                    val md = exportHelper.generateSinglePageMarkdown(page)
                    if (md != null) {
                        exportHelper.shareFilesToDriveOrApps(listOf(md), "text/markdown")
                    } else {
                        snackbarHostState.showSnackbar("Failed to generate Markdown")
                    }
                }
            }
        )
    }

    if (showRssDialog) {
        RssScraperManagerDialog(
            subscriptions = rssSubscriptions,
            onAddSubscription = { url, kw -> viewModel.addRssSubscription(url, kw) },
            onDeleteSubscription = { sub -> viewModel.removeRssSubscription(sub) },
            onSyncNow = {
                viewModel.triggerImmediateRssSync()
                showRssDialog = false
            },
            onDismiss = { showRssDialog = false }
        )
    }

    if (showSettingsDialog) {
        SettingsAndAboutDialog(
            currentTheme = themeMode,
            onThemeSelected = { viewModel.setThemeMode(it) },
            availableModels = availableModels,
            selectedModelId = selectedModelId,
            onSelectAiEdgeModel = { id, path -> viewModel.selectAiEdgeModel(id, path) },
            currentModel = currentAiModel,
            onModelSelected = { viewModel.setAiModel(it) },
            onExportDiagnosticLog = { exportHelper.shareTroubleshootingLog() },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showEdgeOnboarding) {
        AiEdgeGalleryOnboardingDialog(
            availableModels = availableModels,
            selectedModelId = selectedModelId,
            onSelectModel = { id, path -> viewModel.selectAiEdgeModel(id, path) },
            onDismiss = { viewModel.dismissEdgeOnboarding() }
        )
    }
}
