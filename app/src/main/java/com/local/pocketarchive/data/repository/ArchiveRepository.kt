package com.local.pocketarchive.data.repository

import com.local.pocketarchive.ai.AiEdgeModel
import com.local.pocketarchive.ai.LocalAiEdgeProcessor
import com.local.pocketarchive.capture.WebArchiveCaptureManager
import com.local.pocketarchive.data.local.ArchiveDao
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class ArchiveRepository(
    private val archiveDao: ArchiveDao,
    private val captureManager: WebArchiveCaptureManager,
    private val aiProcessor: LocalAiEdgeProcessor
) {
    fun observeArchives(searchQuery: String): Flow<List<ArchivedPage>> {
        val trimmed = searchQuery.trim()
        return if (trimmed.isBlank()) {
            archiveDao.observeAllArchives()
        } else {
            val sanitizedFts = trimmed.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { "$it*" }
            archiveDao.searchArchivesFts(sanitizedFts)
        }
    }

    suspend fun getArchiveByUuid(uuid: String): ArchivedPage? = archiveDao.getByUuid(uuid)

    suspend fun captureAndIndexUrl(
        rawUrl: String,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false,
        preferredModel: AiEdgeModel = aiProcessor.currentModel,
        onStageUpdate: ((String) -> Unit)? = null
    ): Result<ArchivedPage> = runCatching {
        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl.trim()
        } else {
            "https://${rawUrl.trim()}"
        }

        val previousVersion = archiveDao.getLatestByUrl(normalizedUrl)
        val parentUuid = previousVersion?.parentUuid ?: previousVersion?.uuid
        val versionNumber = if (previousVersion != null) previousVersion.versionNumber + 1 else 1

        val uuid = UUID.randomUUID().toString()
        val startEpochMs = System.currentTimeMillis()

        val initialEntity = ArchivedPage(
            uuid = uuid,
            originalUrl = normalizedUrl,
            title = normalizedUrl,
            parentUuid = parentUuid,
            versionNumber = versionNumber,
            status = CaptureStatus.PENDING
        )
        archiveDao.insert(initialEntity)

        val rawCapture = captureManager.captureUrl(
            uuid = uuid,
            targetUrl = normalizedUrl,
            spoofCrawler = spoofCrawler,
            disableJs = disableJs,
            onStageUpdate = onStageUpdate
        ).getOrElse { error ->
            val failed = initialEntity.copy(
                status = CaptureStatus.FAILED,
                aiSummary = "Capture failed: ${error.localizedMessage ?: "Timeout"}"
            )
            archiveDao.update(failed)
            throw error
        }

        val diffSummary = if (previousVersion != null) {
            aiProcessor.compareArticleVersions(
                oldTitle = previousVersion.title,
                oldText = previousVersion.extractedTextSummary,
                newTitle = rawCapture.resolvedTitle,
                newText = rawCapture.extractedDomText
            )
        } else ""

        onStageUpdate?.invoke("Step 3/4: Extracting article text & OCR...")
        val ocrText = aiProcessor.extractOcrFromScreenshot(rawCapture.fullScreenshotFile.absolutePath)

        onStageUpdate?.invoke("Step 4/4: Writing offline summary...")
        val aiResult = aiProcessor.summarizeAndTag(
            title = rawCapture.resolvedTitle,
            rawText = rawCapture.extractedDomText,
            ocrText = ocrText
        )

        val durationMs = System.currentTimeMillis() - startEpochMs

        val completedEntity = initialEntity.copy(
            title = rawCapture.resolvedTitle,
            siteName = rawCapture.siteName.orEmpty(),
            author = rawCapture.author.orEmpty(),
            mhtmlFilePath = rawCapture.mhtmlFile.absolutePath,
            screenshotFilePath = rawCapture.fullScreenshotFile.absolutePath,
            extractedTextSummary = rawCapture.extractedDomText.take(65000),
            aiSummary = aiResult.summary,
            aiTags = aiResult.tags,
            ocrText = ocrText,
            captureDurationMs = durationMs,
            parentUuid = parentUuid,
            versionNumber = versionNumber,
            changeDiffSummary = diffSummary,
            aiModelUsed = aiResult.modelLabel,
            status = CaptureStatus.COMPLETED
        )
        archiveDao.upsertPage(completedEntity)
        archiveDao.rebuildFts()
        completedEntity
    }

    suspend fun deleteArchive(page: ArchivedPage) {
        page.mhtmlFilePath?.let { File(it).parentFile?.deleteRecursively() }
        archiveDao.deleteByUuid(page.uuid)
    }
}
