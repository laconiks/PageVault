package com.local.webvaultr.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import java.util.UUID

enum class CaptureStatus { PENDING, COMPLETED, FAILED }

@Entity(tableName = "archived_pages")
data class ArchivedPage(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val originalUrl: String,
    val title: String,
    val siteName: String = "",
    val author: String = "",
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val mhtmlFilePath: String? = null,
    val screenshotFilePath: String? = null,
    val extractedTextSummary: String = "",
    val aiSummary: String = "",
    val aiTags: List<String> = emptyList(),
    val ocrText: String = "",
    val captureDurationMs: Long = 0L,
    val parentUuid: String? = null,
    val versionNumber: Int = 1,
    val changeDiffSummary: String = "",
    val aiModelUsed: String = "Local Heuristic / LiteRT",
    val status: CaptureStatus = CaptureStatus.PENDING
)

@Fts4(contentEntity = ArchivedPage::class)
@Entity(tableName = "archived_pages_fts")
data class ArchivedPageFts(
    val title: String,
    val siteName: String,
    val author: String,
    val extractedTextSummary: String,
    val aiSummary: String,
    val ocrText: String,
    val changeDiffSummary: String
)

@Entity(tableName = "rss_subscriptions")
data class RssSubscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val keywordsCsv: String,
    val lastSyncMillis: Long = 0L
)
