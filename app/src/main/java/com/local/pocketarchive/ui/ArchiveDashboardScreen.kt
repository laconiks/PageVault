package com.local.pocketarchive.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.pocketarchive.ui.components.*
import com.local.pocketarchive.util.ExportAndShareHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchiveDashboardScreen(viewModel: ArchiveViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportHelper = remember { ExportAndShareHelper(context) }

    val pages by viewModel.archivedPages.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val selectedUuids by viewModel.selectedUuids.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val isGroupedByWebsite by viewModel.isGroupedByWebsite.collectAsState()
    val rssSubscriptions by viewModel.rssSubscriptions.collectAsState()
    val currentAiModel by viewModel.aiModel.collectAsState()
    val showEdgeOnboarding by viewModel.showEdgeOnboarding.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRssDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Listen for "Generated in X.Xs" alerts and show pop-up Snackbar
    LaunchedEffect(Unit) {
        viewModel.uiAlerts.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = "⚡ $message",
                duration = SnackbarDuration.Short
            )
        }
    }

    val groupedPages = remember(pages) {
        pages.groupBy { page ->
            page.siteName.ifBlank {
                runCatching { Uri.parse(page.originalUrl).host?.removePrefix("www.") ?: "Other Sites" }
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
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val selectedPages = pages.filter { selectedUuids.contains(it.uuid) }
                            scope.launch {
                                val mdFile = exportHelper.generateBatchMarkdown(selectedPages)
                                if (mdFile != null) {
                                    exportHelper.shareFilesToDriveOrApps(listOf(mdFile), "text/markdown")
                                }
                                viewModel.clearSelection()
                            }
                        }) {
                            Icon(Icons.Default.Description, contentDescription = "Export Batch Markdown")
                        }
                        IconButton(onClick = {
                            val selectedPages = pages.filter { selectedUuids.contains(it.uuid) }
                            scope.launch {
                                val pdfFile = exportHelper.generateBatchPdf(selectedPages)
                                if (pdfFile != null) {
                                    exportHelper.shareFilesToDriveOrApps(listOf(pdfFile), "application/pdf")
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
                                contentDescription = "PageVault",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("PageVault", fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "LOCAL AI EDGE ARCHIVER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRssDialog = true }) {
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
                        IconButton(onClick = { showSettingsDialog = true }) {
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
            // Sleek Vault Intake Box: "Insert Page for the Vault"
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                viewModel.captureAndIndexUrl(urlInput)
                                urlInput = ""
                            }
                        },
                        enabled = !isCapturing && urlInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Vault", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar + "Group by Website" Filter Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("Search vault (FTS4 + OCR)...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = isGroupedByWebsite,
                    onClick = { viewModel.toggleGroupByWebsite() },
                    label = { Text("By Site") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isGroupedByWebsite) Icons.Default.FolderSpecial else Icons.Default.ViewStream,
                            contentDescription = "Group by Website",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (pages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.EnhancedEncryption,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your PageVault is Empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Insert a page above or use Chrome -> Share -> PageVault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        groupedPages.forEach { (siteGroup, sitePages) ->
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
                                            text = "${siteGroup.uppercase()} (${sitePages.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = getDomainAccentColor(siteGroup)
                                        )
                                    }
                                }
                            }
                            items(sitePages, key = { it.uuid }) { page ->
                                VaultArticleCard(
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
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        items(pages, key = { it.uuid }) { page ->
                            VaultArticleCard(
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
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
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