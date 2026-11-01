package com.local.pocketarchive.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.local.pocketarchive.ai.AiEdgeModel
import com.local.pocketarchive.ai.EdgeModelOption
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.data.local.RssSubscription
import com.local.pocketarchive.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun getDomainAccentColor(siteOrUrl: String): Color {
    val palette = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFFB300), // Gold
        Color(0xFF7C4DFF), // Violet
        Color(0xFF00E676), // Matrix Green
        Color(0xFFFF4081), // Neon Pink
        Color(0xFF40C4FF)  // Sky Laser
    )
    val hash = siteOrUrl.lowercase().trim().hashCode().let { if (it < 0) -it else it }
    return palette[hash % palette.size]
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VaultArticleCard(
    page: ArchivedPage,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onExportPdf: () -> Unit,
    onDelete: () -> Unit,
    onRecaptureNoJs: (() -> Unit)? = null,
    onExportMarkdown: (() -> Unit)? = null,
    onRepull: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val domainLabel = remember(page.siteName, page.originalUrl) {
        page.siteName.ifBlank {
            runCatching { Uri.parse(page.originalUrl).host?.removePrefix("www.") ?: "Web" }.getOrDefault("Web")
        }
    }
    val accentColor = remember(domainLabel) { getDomainAccentColor(domainLabel) }
    val secondsStr = remember(page.captureDurationMs) {
        if (page.captureDurationMs > 0) {
            String.format(Locale.US, "⏱ %.1fs", page.captureDurationMs / 1000.0)
        } else {
            "⏱ Instant"
        }
    }
    val dateFormatted = remember(page.timestampEpochMillis) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(page.timestampEpochMillis))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = onCardLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            brush = Brush.horizontalGradient(
                colors = if (isSelected) listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                else listOf(accentColor.copy(alpha = 0.7f), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Header Row: Domain Badge + Generation Time in Seconds + Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).padding(end = 6.dp)
                        )
                    }
                    Surface(
                        color = accentColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, accentColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = domainLabel.uppercase(),
                            color = accentColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Generation Duration Badge in Seconds
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = secondsStr,
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    if (page.versionNumber > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "v${page.versionNumber} • CHILD REVISION",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Row: Title, Summary, and Thumbnail
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 5 else 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Clickable Original URL -> Opens directly in Google Chrome / Browser
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                runCatching {
                                    val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(page.originalUrl)).apply {
                                        setPackage("com.android.chrome")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(chromeIntent)
                                    } catch (_: Exception) {
                                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(page.originalUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(fallbackIntent)
                                    }
                                }
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = "Open in Chrome",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = page.originalUrl,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.Underline
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!page.screenshotFilePath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(12.dp))
                    AsyncImage(
                        model = File(page.screenshotFilePath),
                        contentDescription = "Snapshot preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status or AI Summary
            if (page.status == CaptureStatus.PENDING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Freezing DOM & running local AI edge summary...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                val summaryPrefix = buildString {
                    if (domainLabel.isNotBlank()) append(domainLabel)
                    if (page.author.isNotBlank()) append(" • ${page.author}")
                    if (isNotEmpty()) append(" — ")
                }
                val bodySummary = page.aiSummary.ifBlank { page.extractedTextSummary }
                if (bodySummary.isNotBlank()) {
                    Text(
                        text = "$summaryPrefix$bodySummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 12 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (page.changeDiffSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CompareArrows,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Diff: ${page.changeDiffSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (expanded) 4 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Tags + Quick Action Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    page.aiTags.take(4).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand Summary",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onRepull != null) {
                        IconButton(
                            onClick = onRepull,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Re-pull & Compare Child Version",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (onRecaptureNoJs != null) {
                        IconButton(
                            onClick = onRecaptureNoJs,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recapture (No-JS Bypass)",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (onExportMarkdown != null) {
                        IconButton(
                            onClick = onExportMarkdown,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = "Export Markdown",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onExportPdf,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Export PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RssScraperManagerDialog(
    subscriptions: List<RssSubscription>,
    onAddSubscription: (String, String) -> Unit,
    onDeleteSubscription: (RssSubscription) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit
) {
    var feedUrl by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("RSS Keyword Auto-Vault", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Automatically scrape & archive articles from RSS/Atom feeds matching your keywords in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text("RSS Feed URL (e.g. feeds.bbci.co.uk/news/rss.xml)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Keywords (comma-separated, blank = all)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (feedUrl.isNotBlank()) {
                            onAddSubscription(feedUrl, keywords)
                            feedUrl = ""
                            keywords = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Auto-Scraper Feed")
                }

                if (subscriptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Active Feeds (${subscriptions.size})", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(subscriptions, key = { it.id }) { sub ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sub.feedUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = if (sub.keywordsCsv.isBlank()) "Keywords: ALL" else "Keywords: ${sub.keywordsCsv}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onDeleteSubscription(sub) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSyncNow) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SettingsAndAboutDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    availableModels: List<EdgeModelOption> = emptyList(),
    selectedModelId: String = "gemma3n_2b",
    onSelectAiEdgeModel: (String, String?) -> Unit = { _, _ -> },
    currentModel: AiEdgeModel = AiEdgeModel.GEMMA_2B,
    onModelSelected: (AiEdgeModel) -> Unit = {},
    onDismiss: () -> Unit
) {
    var customPathInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("PageVault Settings & About", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Appearance Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = currentTheme == mode,
                            onClick = { onThemeSelected(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
                HorizontalDivider()
                Text("Google AI Edge Model Selector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (availableModels.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableModels.forEach { option ->
                            val isSelected = option.id == selectedModelId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectAiEdgeModel(option.id, null) }
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = option.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Active",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = option.recommendationBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (option.isAvailableOnDisk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        if (selectedModelId == "custom_path") {
                            OutlinedTextField(
                                value = customPathInput,
                                onValueChange = {
                                    customPathInput = it
                                    onSelectAiEdgeModel("custom_path", it)
                                },
                                label = { Text("Custom Model Path (.task / .litertlm)") },
                                placeholder = { Text("/sdcard/Download/model.task") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiEdgeModel.entries.forEach { model ->
                            FilterChip(
                                selected = currentModel == model,
                                onClick = { onModelSelected(model) },
                                label = { Text(model.displayName) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                // 100% Local / Manual RSS Privacy Ethos Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "100% Local / Manual RSS Privacy Ethos",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Zero external analytics, zero cloud telemetry. All article parsing, DOM unmasking, MHTML snapshots, OCR, and AI summaries run entirely on your device with complete user sovereignty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider()
                Text("About PageVault v0.6", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "PageVault is a private, local-first web preservation and on-device AI archival engine. Captures RFC-compliant MHTML snapshots, full-height screenshots, ML Kit OCR, and LiteRT edge summaries with zero cloud dependencies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Architected & Engineered by:", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "@laconiks",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Feedback & Contact: laconik@gmail.com",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "github.com/laconiks/PageVault",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun AiEdgeGalleryOnboardingDialog(
    availableModels: List<EdgeModelOption>,
    selectedModelId: String,
    onSelectModel: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var customPath by remember { mutableStateOf("") }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "Google AI Edge Onboarding",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "OPEN ANDROID ETHOS • 100% OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Open Android Ethos & Zero-Cloud Guarantee",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "PageVault analyzes, summarizes, and diffs web captures entirely on your device using LiteRT & MediaPipe LLM. No personal reading data or tokens ever leave your hardware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recommended Models:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = {
                            uriHandler.openUri("https://ai.google.dev/edge")
                        }
                    ) {
                        Text("AI Edge Gallery ↗", style = MaterialTheme.typography.labelSmall)
                    }
                }

                availableModels.forEach { option ->
                    val isSelected = option.id == selectedModelId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(option.id, null) }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.recommendationBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (option.isAvailableOnDisk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                            )
                            if (option.filePath != null && option.filePath != "built_in") {
                                Text(
                                    text = "Path: ${option.filePath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (selectedModelId == "custom_path") {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = {
                            customPath = it
                            onSelectModel("custom_path", it)
                        },
                        label = { Text("Model File Absolute Path") },
                        placeholder = { Text("/sdcard/Download/model.task") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Confirm & Launch Vault")
            }
        }
    )
}