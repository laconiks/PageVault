package com.local.pocketarchive.util

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.local.pocketarchive.data.local.ArchivedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportAndShareHelper(private val context: Context) {

    companion object {
        private const val TAG = "ExportAndShare"
    }

    suspend fun generateSinglePagePdf(page: ArchivedPage): File? =
        generateBatchPdf(listOf(page), "PageVault_${page.uuid.take(8)}.pdf")

    suspend fun generateBatchPdf(
        pages: List<ArchivedPage>,
        fileName: String = "PageVault_Batch_${System.currentTimeMillis()}.pdf"
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            DiagnosticLogHelper.log(TAG, "generateBatchPdf called with empty pages list")
            return@withContext null
        }
        val pdfDocument = PdfDocument()
        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 11f }

        try {
            DiagnosticLogHelper.log(TAG, "Generating PDF with ${pages.size} page(s) -> $fileName")
            pages.forEachIndexed { index, page ->
                val pageInfo = PdfDocument.PageInfo.Builder(612, 792, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = pdfPage.canvas

                var yPos = 40f
                canvas.drawText(page.title.take(65), 36f, yPos, titlePaint)
                yPos += 20f
                canvas.drawText("URL: ${page.originalUrl.take(80)}", 36f, yPos, bodyPaint)
                yPos += 18f
                canvas.drawText(
                    "Captured in: ${String.format(Locale.US, "%.1fs", page.captureDurationMs / 1000.0)}",
                    36f,
                    yPos,
                    bodyPaint
                )
                yPos += 24f

                val summary = page.aiSummary.ifBlank { page.extractedTextSummary }
                summary.chunked(85).take(8).forEach { line ->
                    canvas.drawText(line, 36f, yPos, bodyPaint)
                    yPos += 15f
                }

                page.screenshotFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, boundsOptions)
                        val sampleSize = calculateInSampleSize(boundsOptions, 600, 800)
                        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        val bitmap = BitmapFactory.decodeFile(path, decodeOptions)
                        if (bitmap != null) {
                            val top = (yPos + 12f).toInt()
                            val availableHeight = (750 - top).coerceAtLeast(100)
                            val destRect = Rect(36, top, 576, top + availableHeight)
                            canvas.drawBitmap(bitmap, null, destRect, null)
                            bitmap.recycle()
                        }
                    }
                }
                pdfDocument.finishPage(pdfPage)
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, fileName)
            FileOutputStream(outFile).use { pdfDocument.writeTo(it) }
            DiagnosticLogHelper.log(TAG, "PDF generated successfully: ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
        } catch (t: Throwable) {
            DiagnosticLogHelper.log(TAG, "generateBatchPdf failed: ${t.localizedMessage}")
            t.printStackTrace()
            null
        } finally {
            pdfDocument.close()
        }
    }

    suspend fun generateSingleMarkdown(page: ArchivedPage): File? =
        generateBatchMarkdown(listOf(page), "PageVault_${page.uuid.take(8)}_v${page.versionNumber}.md")

    suspend fun generateSinglePageMarkdown(page: ArchivedPage): File? =
        generateSingleMarkdown(page)

    suspend fun generateBatchMarkdown(
        pages: List<ArchivedPage>,
        fileName: String = "PageVault_Corpus_${System.currentTimeMillis()}.md"
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            DiagnosticLogHelper.log(TAG, "generateBatchMarkdown called with empty pages list")
            return@withContext null
        }
        try {
            DiagnosticLogHelper.log(TAG, "Generating Markdown corpus with ${pages.size} page(s) -> $fileName")
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, fileName)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

            val markdownContent = buildString {
                appendLine("# PageVault Structured AI & Word-Mapping Corpus")
                appendLine("- **Exported Articles:** ${pages.size}")
                appendLine("- **Generated On-Device:** ${dateFormat.format(Date())}")
                appendLine("- **Privacy Guarantee:** 100% Local On-Device Extraction (Zero Cloud)")
                appendLine()
                appendLine("---")
                appendLine()

                pages.forEachIndexed { idx, page ->
                    val captureDate = dateFormat.format(Date(page.timestampEpochMillis))
                    appendLine("## [${idx + 1}] ${page.title} (v${page.versionNumber})")
                    appendLine("```yaml")
                    appendLine("uuid: \"${page.uuid}\"")
                    appendLine("parent_uuid: \"${page.parentUuid ?: "root"}\"")
                    appendLine("version: ${page.versionNumber}")
                    appendLine("source_url: \"${page.originalUrl}\"")
                    appendLine("domain: \"${page.siteName}\"")
                    appendLine("author: \"${page.author}\"")
                    appendLine("captured_at: \"$captureDate\"")
                    appendLine("capture_duration_sec: ${String.format(Locale.US, "%.1f", page.captureDurationMs / 1000.0)}")
                    appendLine("on_device_ai_model: \"${page.aiModelUsed}\"")
                    appendLine("tags: [${page.aiTags.joinToString(", ") { "\"$it\"" }}]")
                    appendLine("```")
                    appendLine()
                    if (page.changeDiffSummary.isNotBlank()) {
                        appendLine("### 🔄 Version Diff vs. Previous Pull")
                        appendLine(page.changeDiffSummary)
                        appendLine()
                    }
                    appendLine("### 🧠 On-Device AI Summary")
                    appendLine(page.aiSummary.ifBlank { page.extractedTextSummary })
                    appendLine()
                    appendLine("### 📄 Extracted DOM Text (Corpus Body for Word Mapping)")
                    appendLine(page.extractedTextSummary.ifBlank { "No DOM text extracted." })
                    appendLine()
                    if (page.ocrText.isNotBlank()) {
                        appendLine("### 🔍 Visual Screenshot OCR Transcript")
                        appendLine(page.ocrText)
                        appendLine()
                    }
                    appendLine("---")
                    appendLine()
                }
            }

            outFile.writeText(markdownContent)
            DiagnosticLogHelper.log(TAG, "Markdown corpus generated successfully: ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
        } catch (t: Throwable) {
            DiagnosticLogHelper.log(TAG, "generateBatchMarkdown failed: ${t.localizedMessage}")
            t.printStackTrace()
            null
        }
    }

    fun shareFilesToDriveOrApps(files: List<File>, mimeType: String = "application/pdf") {
        if (files.isEmpty()) {
            DiagnosticLogHelper.log(TAG, "shareFilesToDriveOrApps called with empty files list")
            Toast.makeText(context, "No files to export", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uris = ArrayList(files.map { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            })

            val shareIntent = Intent().apply {
                action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                type = mimeType
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            DiagnosticLogHelper.log(TAG, "Sharing ${files.size} file(s) via chooser ($mimeType): ${files.joinToString { it.name }}")
            Toast.makeText(context, "Export ready: ${files.first().name}", Toast.LENGTH_SHORT).show()

            context.startActivity(Intent.createChooser(shareIntent, "Export from PageVault").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (t: Throwable) {
            DiagnosticLogHelper.log(TAG, "Failed to share export: ${t.localizedMessage}")
            Toast.makeText(context, "Export failed: ${t.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareTroubleshootingLog() {
        val logFile = DiagnosticLogHelper.exportTroubleshootingLog(context)
        if (logFile != null) {
            shareFilesToDriveOrApps(listOf(logFile), "text/plain")
        } else {
            Toast.makeText(context, "Failed to create troubleshooting log", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
