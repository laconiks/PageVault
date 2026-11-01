package com.local.pocketarchive.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

data class PageMetadata(
    @ColumnInfo(name = "site_name") val siteName: String? = null,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "word_count") val wordCount: Int = 0,
    @ColumnInfo(name = "http_status_code") val httpStatusCode: Int = 200,
    @ColumnInfo(name = "extracted_text_snippet") val extractedTextSnippet: String = ""
)

@Entity(tableName = "archived_pages")
data class ArchivedPage(
    @PrimaryKey
    @ColumnInfo(name = "rowid") val rowId: Long? = null,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "original_url") val originalUrl: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "mhtml_file_path") val mhtmlFilePath: String?,
    @ColumnInfo(name = "screenshot_file_path") val screenshotFilePath: String?,
    @ColumnInfo(name = "thumbnail_file_path") val thumbnailFilePath: String?,
    @ColumnInfo(name = "summary") val summary: String = "",
    @ColumnInfo(name = "tags_csv") val tagsCsv: String = "",
    @ColumnInfo(name = "ocr_text") val ocrText: String = "",
    @ColumnInfo(name = "status") val status: CaptureStatus = CaptureStatus.PENDING,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long = System.currentTimeMillis(),
    @Embedded val metadata: PageMetadata = PageMetadata()
)

@Fts4(contentEntity = ArchivedPage::class)
@Entity(tableName = "archived_pages_fts")
data class ArchivedPageFts(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "tags_csv") val tagsCsv: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
    @ColumnInfo(name = "extracted_text_snippet") val extractedTextSnippet: String
)
