package com.local.pocketarchive.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.ui.theme.AppThemeMode
import com.local.pocketarchive.util.DomainColorHelper
import java.io.File

@Composable
fun SettingsAndAboutDialog(
    currentMode: AppThemeMode,
    onModeSelected: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { TechTrebuchetIcon(modifier = Modifier.size(42.dp)) },
        title = { Text("Pixbuchey Settings & About") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme Mode", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = currentMode == mode,
                            onClick = { onModeSelected(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("About Pixbuchey", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Offline MHTML, Full-Page Screenshot & Local AI Edge Archiver.\n\n" +
                        "Developer: Rafael Antonio Pena\n" +
                        "Architected & Made with Gemini Argon",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun ExpandableArchiveCard(
    page: ArchivedPage,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpenViewer: () -> Unit,
    onExportPdf: () -> Unit,
    onShareDrive: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val siteColor = DomainColorHelper.colorForPage(page)
    val siteName = DomainColorHelper.extractDisplaySiteName(page)
    val formattedDesc = DomainColorHelper.formatWebsitePrefixedDescription(page)

    Card(
        border = BorderStroke(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else siteColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = page.status == CaptureStatus.COMPLETED, onClick = onOpenViewer)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(siteColor.copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$siteName • ${page.status.name} • ${page.metadata.wordCount}w",
                    style = MaterialTheme.typography.labelMedium,
                    color = siteColor,
                    fontWeight = FontWeight.Bold
                )
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            }

            if (page.thumbnailFilePath != null) {
                AsyncImage(
                    model = File(page.thumbnailFilePath),
                    contentDescription = page.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(135.dp)
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formattedDesc,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) 20 else 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!page.errorMessage.isNullOrBlank()) {
                    Text(
                        text = page.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (page.tagsCsv.isNotBlank()) {
                        Text("Tags: ${page.tagsCsv}", style = MaterialTheme.typography.labelSmall, color = siteColor)
                    }
                    Text("URL: ${page.originalUrl}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(onClick = onExportPdf, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF")
                        }
                        FilledTonalButton(onClick = onShareDrive, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Drive")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                        Text(if (expanded) "Collapse" else "Expand")
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
