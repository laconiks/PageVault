package com.local.pocketarchive.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.ui.components.ExpandableArchiveCard
import com.local.pocketarchive.ui.components.SettingsAndAboutDialog
import com.local.pocketarchive.ui.components.TechTrebuchetIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveDashboardScreen(viewModel: ArchiveViewModel) {
    val context = LocalContext.current
    val archives by viewModel.archives.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val selectedUuids by viewModel.selectedUuids.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var urlInput by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsAndAboutDialog(
            currentMode = themeMode,
            onModeSelected = viewModel::setThemeMode,
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TechTrebuchetIcon(modifier = Modifier.padding(start = 12.dp).size(28.dp))
                },
                title = {
                    val titleText = if (selectedUuids.isEmpty()) "Pixbuchey" else "${selectedUuids.size} Selected"
                    Text(titleText, modifier = Modifier.padding(start = 8.dp))
                },
                actions = {
                    if (selectedUuids.isNotEmpty()) {
                        val selectedPages = archives.filter { it.uuid in selectedUuids }
                        IconButton(onClick = { viewModel.exportPagesAsPdf(context, selectedPages, false) }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Export PDF")
                        }
                        IconButton(onClick = { viewModel.exportPagesAsPdf(context, selectedPages, true) }) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Share Drive")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL to launch over paywall") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !isCapturing && urlInput.isNotBlank(),
                    onClick = { viewModel.submitUrlForArchive(urlInput); urlInput = "" }
                ) { Text("Launch") }
            }

            if (isCapturing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TechTrebuchetIcon(modifier = Modifier.size(28.dp))
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                label = { Text("Search DOM, AI summary, tags, OCR") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(archives, key = { it.uuid }) { page ->
                    ExpandableArchiveCard(
                        page = page,
                        isSelected = page.uuid in selectedUuids,
                        onToggleSelect = { viewModel.toggleSelection(page.uuid) },
                        onOpenViewer = {
                            if (page.status == CaptureStatus.COMPLETED && page.mhtmlFilePath != null) {
                                context.startActivity(
                                    Intent(context, OfflineMhtmlViewerActivity::class.java).apply {
                                        putExtra(OfflineMhtmlViewerActivity.EXTRA_TITLE, page.title)
                                        putExtra(OfflineMhtmlViewerActivity.EXTRA_MHTML_PATH, page.mhtmlFilePath)
                                        putExtra(OfflineMhtmlViewerActivity.EXTRA_SCREENSHOT_PATH, page.screenshotFilePath)
                                    }
                                )
                            }
                        },
                        onExportPdf = { viewModel.exportPagesAsPdf(context, listOf(page), false) },
                        onShareDrive = { viewModel.exportPagesAsPdf(context, listOf(page), true) },
                        onDelete = { viewModel.deleteArchive(page) }
                    )
                }
            }
        }
    }
}
