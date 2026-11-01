package com.local.pocketarchive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.local.pocketarchive.ai.AiEdgeModel
import com.local.pocketarchive.ai.EdgeModelOption
import com.local.pocketarchive.ai.LocalAiEdgeProcessor
import com.local.pocketarchive.capture.RssFeedScraperWorker
import com.local.pocketarchive.capture.WebArchiveCaptureManager
import com.local.pocketarchive.data.local.ArchiveDatabase
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.data.local.RssSubscription
import com.local.pocketarchive.data.repository.ArchiveRepository
import com.local.pocketarchive.ui.theme.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ArchiveDatabase.getInstance(application)
    private val dao = database.archiveDao()
    private val captureManager = WebArchiveCaptureManager(application)
    private val aiProcessor = LocalAiEdgeProcessor(application)
    private val repository = ArchiveRepository(dao, captureManager, aiProcessor)
    private val workManager = WorkManager.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _aiModel = MutableStateFlow(aiProcessor.currentModel)
    val aiModel: StateFlow<AiEdgeModel> = _aiModel.asStateFlow()

    private val _isGroupedByWebsite = MutableStateFlow(false)
    val isGroupedByWebsite: StateFlow<Boolean> = _isGroupedByWebsite.asStateFlow()

    private val _uiAlerts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val uiAlerts: SharedFlow<String> = _uiAlerts.asSharedFlow()

    val rssSubscriptions: StateFlow<List<RssSubscription>> = dao.observeRssSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedPages: StateFlow<List<ArchivedPage>> = _searchQuery
        .debounce(200)
        .flatMapLatest { query ->
            repository.observeArchives(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    private val _showEdgeOnboarding = MutableStateFlow(aiProcessor.isFirstLaunchOnboardingNeeded())
    val showEdgeOnboarding: StateFlow<Boolean> = _showEdgeOnboarding.asStateFlow()

    private val _availableModels = MutableStateFlow(aiProcessor.getAvailableEdgeModels())
    val availableModels: StateFlow<List<EdgeModelOption>> = _availableModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow(aiProcessor.getSelectedModelId())
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    fun dismissEdgeOnboarding() {
        aiProcessor.markOnboardingComplete()
        _showEdgeOnboarding.value = false
    }

    fun selectAiEdgeModel(modelId: String, customPath: String? = null) {
        aiProcessor.setSelectedModel(modelId, customPath)
        _selectedModelId.value = modelId
        _availableModels.value = aiProcessor.getAvailableEdgeModels()
        _aiModel.value = aiProcessor.currentModel
        _uiAlerts.tryEmit("Active On-Device AI switched to $modelId")
    }

    fun refreshAvailableModels() {
        _availableModels.value = aiProcessor.getAvailableEdgeModels()
    }

    fun setAiModel(model: AiEdgeModel) {
        aiProcessor.currentModel = model
        _aiModel.value = model
        _selectedModelId.value = aiProcessor.getSelectedModelId()
    }

    fun toggleGroupByWebsite() {
        _isGroupedByWebsite.value = !_isGroupedByWebsite.value
    }

    fun toggleSelection(uuid: String) {
        _selectedUuids.update { current ->
            if (current.contains(uuid)) current - uuid else current + uuid
        }
    }

    fun clearSelection() {
        _selectedUuids.value = emptySet()
    }

    fun captureAndIndexUrl(
        rawUrl: String,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false
    ) {
        val cleanUrl = rawUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        if (cleanUrl.isBlank()) return

        viewModelScope.launch {
            _isCapturing.value = true
            val startTimeMs = System.currentTimeMillis()

            repository.captureAndIndexUrl(cleanUrl, spoofCrawler, disableJs, _aiModel.value)
                .onSuccess {
                    val durationMs = System.currentTimeMillis() - startTimeMs
                    val secondsFormatted = String.format(Locale.US, "%.1fs", durationMs / 1000.0)
                    _uiAlerts.tryEmit("Generated in $secondsFormatted")
                }
                .onFailure { e ->
                    _uiAlerts.tryEmit("Error: ${e.localizedMessage ?: "Capture failed"}")
                }
            _isCapturing.value = false
        }
    }

    fun repullAndCompareArticle(existingPage: ArchivedPage) {
        val rootUuid = existingPage.parentUuid ?: existingPage.uuid
        viewModelScope.launch {
            _isCapturing.value = true
            val startTimeMs = System.currentTimeMillis()
            val nextVersion = dao.getVersionCountForChain(rootUuid) + 1

            val pendingChild = ArchivedPage(
                originalUrl = existingPage.originalUrl,
                title = "Re-Pulling v$nextVersion: ${existingPage.title}",
                parentUuid = rootUuid,
                versionNumber = nextVersion,
                status = CaptureStatus.PENDING
            )
            dao.upsertPage(pendingChild)

            try {
                val snapshot = captureManager.captureUrlSnapshot(existingPage.originalUrl, pendingChild.uuid)
                if (snapshot != null) {
                    val ocrText = snapshot.screenshotPath?.let { path ->
                        aiProcessor.extractOcrFromScreenshot(path)
                    } ?: ""

                    val aiResult = aiProcessor.summarizeAndTag(
                        title = snapshot.pageTitle,
                        rawText = snapshot.extractedDomText,
                        ocrText = ocrText
                    )

                    val diffSummary = aiProcessor.compareArticleVersions(
                        oldTitle = existingPage.title,
                        oldText = existingPage.extractedTextSummary,
                        newTitle = snapshot.pageTitle.ifBlank { existingPage.title },
                        newText = snapshot.extractedDomText
                    )

                    val durationMs = System.currentTimeMillis() - startTimeMs
                    val secondsFormatted = String.format(Locale.US, "%.1fs", durationMs / 1000.0)

                    val completedChild = pendingChild.copy(
                        title = snapshot.pageTitle.ifBlank { existingPage.title },
                        siteName = snapshot.siteName.ifBlank { existingPage.siteName },
                        author = snapshot.author.ifBlank { existingPage.author },
                        mhtmlFilePath = snapshot.mhtmlPath,
                        screenshotFilePath = snapshot.screenshotPath,
                        extractedTextSummary = snapshot.extractedDomText.take(1500),
                        aiSummary = aiResult.summary,
                        aiTags = (aiResult.tags + listOf("v$nextVersion-Diff")).distinct(),
                        ocrText = ocrText,
                        captureDurationMs = durationMs,
                        parentUuid = rootUuid,
                        versionNumber = nextVersion,
                        changeDiffSummary = diffSummary,
                        aiModelUsed = aiResult.modelLabel,
                        status = CaptureStatus.COMPLETED
                    )
                    dao.upsertPage(completedChild)
                    dao.rebuildFts()
                    _uiAlerts.tryEmit("Generated Child v$nextVersion + AI Diff in $secondsFormatted")
                } else {
                    dao.deletePage(pendingChild)
                    _uiAlerts.tryEmit("Re-pull failed for URL")
                }
            } catch (e: Exception) {
                dao.deletePage(pendingChild)
                _uiAlerts.tryEmit("Error comparing article versions")
            } finally {
                _isCapturing.value = false
            }
        }
    }

    fun addRssSubscription(feedUrl: String, keywordsCsv: String) {
        val cleanUrl = feedUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        if (cleanUrl.isBlank()) return

        viewModelScope.launch {
            dao.insertRssSubscription(RssSubscription(feedUrl = cleanUrl, keywordsCsv = keywordsCsv.trim()))
            schedulePeriodicRssSync()
            triggerImmediateRssSync()
            _uiAlerts.tryEmit("RSS Auto-Scraper active & syncing!")
        }
    }

    fun removeRssSubscription(sub: RssSubscription) {
        viewModelScope.launch {
            dao.deleteRssSubscription(sub)
        }
    }

    fun triggerImmediateRssSync() {
        val oneTimeRequest = OneTimeWorkRequestBuilder<RssFeedScraperWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork("PageVaultImmediateRssSync", ExistingWorkPolicy.REPLACE, oneTimeRequest)
        _uiAlerts.tryEmit("Scanning RSS feeds for matching articles...")
    }

    private fun schedulePeriodicRssSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<RssFeedScraperWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "PageVaultPeriodicRssSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun deleteArchive(page: ArchivedPage) {
        viewModelScope.launch {
            repository.deleteArchive(page)
            _selectedUuids.update { it - page.uuid }
        }
    }
}

data class SnapshotResult(
    val pageTitle: String,
    val siteName: String,
    val author: String,
    val extractedDomText: String,
    val mhtmlPath: String,
    val screenshotPath: String?
)

private suspend fun WebArchiveCaptureManager.captureUrlSnapshot(targetUrl: String, uuid: String): SnapshotResult? {
    return captureUrl(uuid = uuid, targetUrl = targetUrl).getOrNull()?.let { raw ->
        SnapshotResult(
            pageTitle = raw.resolvedTitle,
            siteName = raw.siteName.orEmpty(),
            author = raw.author.orEmpty(),
            extractedDomText = raw.extractedDomText,
            mhtmlPath = raw.mhtmlFile.absolutePath,
            screenshotPath = raw.fullScreenshotFile.absolutePath
        )
    }
}
