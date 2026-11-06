package com.local.webvaultr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val movedArticles by viewModel.movedArticles.collectAsState()
    val rssArticles by viewModel.rssArticles.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val collapsedSites = remember { mutableStateMapOf<String, Boolean>() }

    var urlInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRssDialog by remember { mutableStateOf(false) }
    var viewingMarkdownPage by remember { mutableStateOf<ArchivedPage?>(null) }
    var movingPageUuid by remember { mutableStateOf<String?>(null) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }

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

    // Filter main feed to exclude moved articles and isolated RSS pulls
    val mainFeedChains = remember(articleChains, movedArticles, rssArticles) {
        articleChains.filter { chain ->
            val uuid = chain.latestPage.uuid
            !movedArticles.containsKey(uuid) && !rssArticles.containsKey(uuid)
        }
    }

    val groupedMainFeedChains = remember(mainFeedChains) {
        mainFeedChains.groupBy { chain ->
            chain.latestPage.siteName.ifBlank {
                runCatching { Uri.parse(chain.latestPage.originalUrl).host?.removePrefix("www.") ?: "Other Sites" }
                    .getOrDefault("Other Sites")
            }
        }
    }

    val allGroupedChains = remember(articleChains) {
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
                            showBatchMoveDialog = true
                        }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move Selected to Collection")
                        }
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
        ) {
            // Top Tab Row: 📥 Main Feed vs 🗂️ Collections
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        selectedTabIndex = 0
                    },
                    text = { Text("📥 Main Feed (${mainFeedChains.size})", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        selectedTabIndex = 1
                    },
                    text = {
                        val totalCollectionsCount = movedArticles.size + rssArticles.size
                        Text(
                            text = if (totalCollectionsCount > 0) "🗂️ Collections ($totalCollectionsCount)" else "🗂️ Collections",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }

            if (selectedTabIndex == 0) {
                // 📥 MAIN FEED
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                    if (mainFeedChains.isEmpty()) {
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
                                    text = if (searchQuery.isNotBlank()) "No matching articles found for '$searchQuery'" else "Your Main Feed is Empty",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) "Try searching different keywords, tags, or domain names" else "Insert a page above, share to WebVaultr, or check the Collections tab",
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
                                groupedMainFeedChains.forEach { (siteGroup, siteChains) ->
                                    val isCollapsed = collapsedSites[siteGroup] == true
                                    stickyHeader {
                                        Surface(
                                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    AppUiPreferences.triggerHaptic(localView)
                                                    collapsedSites[siteGroup] = !isCollapsed
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isCollapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = getDomainAccentColor(siteGroup),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Language,
                                                    contentDescription = null,
                                                    tint = getDomainAccentColor(siteGroup),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "🌐 ${siteGroup.uppercase()} (${siteChains.size})",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = getDomainAccentColor(siteGroup)
                                                )
                                            }
                                        }
                                    }
                                    if (!isCollapsed) {
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
                                                                putExtra("PAGE_UUID", page.uuid)
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
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(pdf),
                                                                    "application/pdf",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to generate PDF")
                                                            }
                                                        }
                                                    },
                                                    onExportMarkdown = {
                                                        scope.launch {
                                                            val md = exportHelper.generateSinglePageMarkdown(page)
                                                            if (md != null) {
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(md),
                                                                    "text/markdown",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                            }
                                                        }
                                                    },
                                                    onOpenMarkdown = { viewingMarkdownPage = it },
                                                    onShareTxt = {
                                                        scope.launch {
                                                            val txt = exportHelper.generatePlainTextForDiscordAndIphone(page)
                                                            if (txt != null) {
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(txt),
                                                                    "text/plain",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to generate plain text")
                                                            }
                                                        }
                                                    },
                                                    onMoveToCollection = { movingPageUuid = page.uuid },
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
                                                                putExtra("PAGE_UUID", page.uuid)
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
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(pdf),
                                                                    "application/pdf",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
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
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(md),
                                                                    "text/markdown",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                            }
                                                        }
                                                    },
                                                    onOpenMarkdown = { viewingMarkdownPage = it },
                                                    onShareTxt = {
                                                        scope.launch {
                                                            val txt = exportHelper.generatePlainTextForDiscordAndIphone(page)
                                                            if (txt != null) {
                                                                exportHelper.shareFilesToDriveOrApps(
                                                                    listOf(txt),
                                                                    "text/plain",
                                                                    subject = page.title,
                                                                    previewText = exportHelper.buildDiscordPreviewText(page)
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to generate plain text")
                                                            }
                                                        }
                                                    },
                                                    onMoveToCollection = { movingPageUuid = page.uuid }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(mainFeedChains, key = { it.latestPage.uuid }) { chain ->
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
                                                        putExtra("PAGE_UUID", page.uuid)
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
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(pdf),
                                                            "application/pdf",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar("Failed to generate PDF")
                                                    }
                                                }
                                            },
                                            onExportMarkdown = {
                                                scope.launch {
                                                    val md = exportHelper.generateSinglePageMarkdown(page)
                                                    if (md != null) {
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(md),
                                                            "text/markdown",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                    }
                                                }
                                            },
                                            onOpenMarkdown = { viewingMarkdownPage = it },
                                            onShareTxt = {
                                                scope.launch {
                                                    val txt = exportHelper.generatePlainTextForDiscordAndIphone(page)
                                                    if (txt != null) {
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(txt),
                                                            "text/plain",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar("Failed to generate plain text")
                                                    }
                                                }
                                            },
                                            onMoveToCollection = { movingPageUuid = page.uuid },
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
                                                        putExtra("PAGE_UUID", page.uuid)
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
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(pdf),
                                                            "application/pdf",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
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
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(md),
                                                            "text/markdown",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar("Failed to generate Markdown")
                                                    }
                                                }
                                            },
                                            onOpenMarkdown = { viewingMarkdownPage = it },
                                            onShareTxt = {
                                                scope.launch {
                                                    val txt = exportHelper.generatePlainTextForDiscordAndIphone(page)
                                                    if (txt != null) {
                                                        exportHelper.shareFilesToDriveOrApps(
                                                            listOf(txt),
                                                            "text/plain",
                                                            subject = page.title,
                                                            previewText = exportHelper.buildDiscordPreviewText(page)
                                                        )
                                                    } else {
                                                        snackbarHostState.showSnackbar("Failed to generate plain text")
                                                    }
                                                }
                                            },
                                            onMoveToCollection = { movingPageUuid = page.uuid }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 🗂️ COLLECTIONS SCREEN
                CollectionsScreen(
                    pages = pages,
                    groupedChains = allGroupedChains,
                    articleChains = articleChains,
                    movedArticles = movedArticles,
                    rssArticles = rssArticles,
                    onOpenPage = { page ->
                        val intent = Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                            putExtra("PAGE_UUID", page.uuid)
                            putExtra("MHTML_PATH", page.mhtmlFilePath)
                            putExtra("SCREENSHOT_PATH", page.screenshotFilePath)
                            putExtra("PAGE_TITLE", page.title)
                            putExtra("ORIGINAL_URL", page.originalUrl)
                        }
                        context.startActivity(intent)
                    },
                    onSelectTag = { tag ->
                        viewModel.updateSearchQuery(tag)
                        selectedTabIndex = 0
                    },
                    onFilterSite = { site ->
                        viewModel.updateSearchQuery(site)
                        selectedTabIndex = 0
                    },
                    onReturnToFeed = { uuid ->
                        viewModel.returnArticleToMainFeed(uuid)
                        scope.launch {
                            snackbarHostState.showSnackbar("Returned article to Main Feed")
                        }
                    }
                )
            }
        }
    }

    // Move to Collection Dialog (Single Item)
    if (movingPageUuid != null) {
        MoveToCollectionDialog(
            title = "Move to Collection",
            onSelectCollection = { collectionName ->
                val uuid = movingPageUuid
                if (uuid != null) {
                    viewModel.moveArticleToCollection(uuid, collectionName)
                    scope.launch {
                        snackbarHostState.showSnackbar("Moved article to '$collectionName'")
                    }
                }
                movingPageUuid = null
            },
            onDismiss = { movingPageUuid = null }
        )
    }

    // Move to Collection Dialog (Batch Selection)
    if (showBatchMoveDialog) {
        MoveToCollectionDialog(
            title = "Move ${selectedUuids.size} Articles",
            onSelectCollection = { collectionName ->
                val count = selectedUuids.size
                viewModel.moveSelectedToCollection(collectionName)
                showBatchMoveDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Moved $count articles to '$collectionName'")
                }
            },
            onDismiss = { showBatchMoveDialog = false }
        )
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
                        exportHelper.shareFilesToDriveOrApps(
                            listOf(md),
                            "text/markdown",
                            subject = page.title,
                            previewText = exportHelper.buildDiscordPreviewText(page)
                        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoveToCollectionDialog(
    title: String,
    onSelectCollection: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customName by remember { mutableStateOf("") }
    val defaultCollections = listOf("Read Later", "Research", "Favorites", "Archive")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Choose a collection to organize these articles. Moved items stay safe in Collections and remain out of your Main Feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    defaultCollections.forEach { col ->
                        SuggestionChip(
                            onClick = { onSelectCollection(col) },
                            label = { Text(col, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }

                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    placeholder = { Text("Or enter custom collection name...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customName.isNotBlank()) onSelectCollection(customName.trim())
                },
                enabled = customName.isNotBlank()
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * 🗂️ Collections Screen
 * Organizes preserved articles into:
 * 1. Summary stats
 * 2. 📁 Custom User Collections (with Return to Feed action)
 * 3. 📡 Isolated RSS Feeds (with Return to Feed action)
 * 4. 🌐 Publication Drawers
 * 5. 🏷️ Topic Tag Stacks
 * 6. 📦 Multi-Version Revision Stacks
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(
    pages: List<ArchivedPage>,
    groupedChains: Map<String, List<ArticleChain>>,
    articleChains: List<ArticleChain>,
    movedArticles: Map<String, String>,
    rssArticles: Map<String, String>,
    onOpenPage: (ArchivedPage) -> Unit,
    onSelectTag: (String) -> Unit,
    onFilterSite: (String) -> Unit,
    onReturnToFeed: (String) -> Unit
) {
    val localView = LocalView.current
    val scrollState = rememberScrollState()
    val allTags = remember(pages) {
        pages.flatMap { it.aiTags }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
    }
    val multiVersionChains = remember(articleChains) {
        articleChains.filter { it.revisions.size > 1 }
    }
    val collapsedDrawers = remember { mutableStateMapOf<String, Boolean>() }

    // Group custom collections
    val customCollections = remember(pages, movedArticles) {
        val map = mutableMapOf<String, MutableList<ArchivedPage>>()
        movedArticles.forEach { (uuid, collectionName) ->
            pages.find { it.uuid == uuid }?.let { page ->
                map.getOrPut(collectionName) { mutableListOf() }.add(page)
            }
        }
        map
    }

    // List of isolated RSS feed items
    val isolatedRssPages = remember(pages, rssArticles) {
        pages.filter { rssArticles.containsKey(it.uuid) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Stats Summary Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${pages.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text("Total Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${customCollections.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
                    Text("Collections", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${isolatedRssPages.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.tertiary)
                    Text("RSS Ingests", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Section 1: 📁 Custom User Collections
        if (customCollections.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "📁 Custom Collections",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Articles moved from your main feed for focused reference.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                customCollections.forEach { (colName, colPages) ->
                    val isColOpen = collapsedDrawers["col_$colName"] != false // default expanded
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AppUiPreferences.triggerHaptic(localView)
                                        collapsedDrawers["col_$colName"] = !isColOpen
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (isColOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        colName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        "${colPages.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (isColOpen) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    colPages.forEach { page ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    AppUiPreferences.triggerHaptic(localView)
                                                    onOpenPage(page)
                                                }
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                page.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            IconButton(
                                                onClick = {
                                                    AppUiPreferences.triggerHaptic(localView)
                                                    onReturnToFeed(page.uuid)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                                    contentDescription = "Return to Main Feed",
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
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

        // Section 2: 📡 Isolated RSS Feeds
        if (isolatedRssPages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "📡 RSS Ingests",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Articles captured by background keyword scrapers. Kept out of main feed until approved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val isRssOpen = collapsedDrawers["rss_feed_drawer"] != false
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppUiPreferences.triggerHaptic(localView)
                                    collapsedDrawers["rss_feed_drawer"] = !isRssOpen
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (isRssOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Background RSS Queue",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    "${isolatedRssPages.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (isRssOpen) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                isolatedRssPages.forEach { page ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                AppUiPreferences.triggerHaptic(localView)
                                                onOpenPage(page)
                                            }
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("•", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            page.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                AppUiPreferences.triggerHaptic(localView)
                                                onReturnToFeed(page.uuid)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                                contentDescription = "Move to Main Feed",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 3: 🌐 Publication Drawers
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🌐 Publication Drawers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Tap a publication to expand its archived articles or filter the feed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            groupedChains.forEach { (site, chains) ->
                val isDrawerOpen = collapsedDrawers[site] == true
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(0.8.dp, getDomainAccentColor(site).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppUiPreferences.triggerHaptic(localView)
                                    collapsedDrawers[site] = !isDrawerOpen
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (isDrawerOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = getDomainAccentColor(site),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    site.uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = getDomainAccentColor(site)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = getDomainAccentColor(site).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    "${chains.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = getDomainAccentColor(site),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (isDrawerOpen) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                chains.forEach { chain ->
                                    val page = chain.latestPage
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                AppUiPreferences.triggerHaptic(localView)
                                                onOpenPage(page)
                                            }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("•", color = getDomainAccentColor(site), fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            page.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        AppUiPreferences.triggerHaptic(localView)
                                        onFilterSite(site)
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("View in Feed ➔", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 4: 🏷️ Topic Tag Stacks
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🏷️ Topic Tag Stacks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Extracted publisher and on-device AI tags. Tap any tag to filter the vault.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (allTags.isEmpty()) {
                Text("No topic tags extracted yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allTags.forEach { (tag, count) ->
                        Surface(
                            onClick = {
                                AppUiPreferences.triggerHaptic(localView)
                                onSelectTag(tag)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        "$count",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 5: 📦 Multi-Version Revision Stacks
        if (multiVersionChains.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "📦 Article Revision Stacks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Articles captured multiple times across breaking news updates.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                multiVersionChains.forEach { chain ->
                    val page = chain.latestPage
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppUiPreferences.triggerHaptic(localView)
                                onOpenPage(page)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    page.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${chain.revisions.size} versions tracked • Latest: v${page.versionNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}
