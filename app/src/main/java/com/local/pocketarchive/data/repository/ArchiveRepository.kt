package com.local.pocketarchive.data.repository

import com.local.pocketarchive.ai.LocalAiEdgeProcessor
import com.local.pocketarchive.capture.WebArchiveCaptureManager
import com.local.pocketarchive.data.local.ArchiveDao
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.data.local.PageMetadata
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

    suspend fun captureAndIndexUrl(rawUrl: String): Result<ArchivedPage> = runCatching {
        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl.trim()
        } else {
            "https://${rawUrl.trim()}"
        }
        val uuid = UUID.randomUUID().toString()
        val initialRowId = archiveDao.insert(
            ArchivedPage(
                uuid = uuid,
                originalUrl = normalizedUrl,
                title = normalizedUrl,
                mhtmlFilePath = null,
                screenshotFilePath = null,
                thumbnailFilePath = null,
                status = CaptureStatus.CAPTURING_DOM
            )
        )
        val initialEntity = archiveDao.getByUuid(uuid)!!.copy(rowId = initialRowId)

        val rawCapture = captureManager.captureUrl(uuid, normalizedUrl).getOrElse { error ->
            val failed = initialEntity.copy(
                status = CaptureStatus.FAILED,
                errorMessage = error.localizedMessage ?: "Capture timed out"
            )
            archiveDao.update(failed)
            throw error
        }

        val summarizingEntity = initialEntity.copy(
            title = rawCapture.resolvedTitle,
            mhtmlFilePath = rawCapture.mhtmlFile.absolutePath,
            screenshotFilePath = rawCapture.fullScreenshotFile.absolutePath,
            thumbnailFilePath = rawCapture.thumbnailFile.absolutePath,
            status = CaptureStatus.SUMMARIZING_AI,
            metadata = PageMetadata(
                siteName = rawCapture.siteName,
                author = rawCapture.author,
                wordCount = rawCapture.wordCount,
                httpStatusCode = 200,
                extractedTextSnippet = rawCapture.extractedDomText.take(2000)
            )
        )
        archiveDao.update(summarizingEntity)

        val aiResult = aiProcessor.enrichCapture(
            title = rawCapture.resolvedTitle,
            extractedDomText = rawCapture.extractedDomText,
            screenshotFile = rawCapture.fullScreenshotFile
        )

        val completedEntity = summarizingEntity.copy(
            summary = aiResult.summary,
            tagsCsv = aiResult.tagsCsv,
            ocrText = aiResult.ocrText,
            status = CaptureStatus.COMPLETED
        )
        archiveDao.update(completedEntity)
        completedEntity
    }

    suspend fun deleteArchive(page: ArchivedPage) {
        page.mhtmlFilePath?.let { File(it).parentFile?.deleteRecursively() }
        archiveDao.deleteByUuid(page.uuid)
    }
}
