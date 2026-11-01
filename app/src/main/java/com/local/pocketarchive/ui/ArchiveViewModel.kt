package com.local.pocketarchive.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.pocketarchive.ai.LocalAiEdgeProcessor
import com.local.pocketarchive.capture.WebArchiveCaptureManager
import com.local.pocketarchive.data.local.ArchiveDatabase
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.repository.ArchiveRepository
import com.local.pocketarchive.ui.theme.AppThemeMode
import com.local.pocketarchive.ui.theme.ThemePreferences
import com.local.pocketarchive.util.ExportAndShareHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ArchiveRepository
    private val themePreferences = ThemePreferences(application)

    val themeMode: StateFlow<AppThemeMode> = themePreferences.themeMode

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids.asStateFlow()

    init {
        val db = ArchiveDatabase.getInstance(application)
        repository = ArchiveRepository(
            archiveDao = db.archiveDao(),
            captureManager = WebArchiveCaptureManager(application),
            aiProcessor = LocalAiEdgeProcessor(application)
        )
    }

    val archives: StateFlow<List<ArchivedPage>> = _searchQuery
        .flatMapLatest { query -> repository.observeArchives(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setThemeMode(mode: AppThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelection(uuid: String) {
        val current = _selectedUuids.value.toMutableSet()
        if (!current.add(uuid)) current.remove(uuid)
        _selectedUuids.value = current
    }

    fun clearSelection() {
        _selectedUuids.value = emptySet()
    }

    fun submitUrlForArchive(url: String) {
        if (url.isBlank() || _isCapturing.value) return
        viewModelScope.launch {
            _isCapturing.value = true
            repository.captureAndIndexUrl(url)
            _isCapturing.value = false
        }
    }

    fun exportPagesAsPdf(context: Context, pages: List<ArchivedPage>, preferGoogleDrive: Boolean) {
        if (pages.isEmpty()) return
        viewModelScope.launch {
            ExportAndShareHelper.exportAndSharePagesAsPdf(context, pages, preferGoogleDrive)
            clearSelection()
        }
    }

    fun deleteArchive(page: ArchivedPage) {
        viewModelScope.launch {
            repository.deleteArchive(page)
            val current = _selectedUuids.value.toMutableSet()
            if (current.remove(page.uuid)) _selectedUuids.value = current
        }
    }
}
