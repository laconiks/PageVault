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
        generateBatchPdf(listOf(page), "PageVault_${page.uuid.take(8)}_v${page.versionNumber}.pdf")

    suspend fun generateBatchPdf(
        pages: List<ArchivedPage>,
        fileName: String = "PageVault_Batch_${System.currentTimeMillis()}.pdf"
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null
        val pdfDocument = PdfDocument()

        val pageWidth = 612 // Standard Letter Width (8.5in * 72pt)
        val pageHeight = 792 // Standard Letter Height (11in * 72pt)
        val margin = 36
        val contentWidth = pageWidth - (margin * 2) // 540pt
        val bottomLimit = pageHeight - margin // 756pt

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f
            isFakeBoldText = true
            color = android.graphics.Color.BLACK
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f
            color = android.graphics.Color.DKGRAY
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            isFakeBoldText = true
            color = android.graphics.Color.rgb(0, 102, 153)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f
            color = android.graphics.Color.BLACK
        }
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        var globalPdfPageNumber = 1

        fun wrapTextToLines(text: String, paint: Paint, maxWidthPt: Float): List<String> {
            val result = mutableListOf<String>()
            text.split("\n").forEach { paragraph ->
                if (paragraph.trim().isEmpty()) {
                    result.add("")
                    return@forEach
                }
                val words = paragraph.trim().split(Regex("\\s+"))
                var currentLine = StringBuilder()
                for (word in words) {
                    if (word.isEmpty()) continue
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (paint.measureText(testLine) <= maxWidthPt) {
                        currentLine = StringBuilder(testLine)
                    } else {
                        if (currentLine.isNotEmpty()) result.add(currentLine.toString())
                        // Handle very long unbroken URLs
                        if (paint.measureText(word) > maxWidthPt) {
                            word.chunked(65).forEach { chunk -> result.add(chunk) }
                            currentLine = StringBuilder()
                        } else {
                            currentLine = StringBuilder(word)
                        }
                    }
                }
                if (currentLine.isNotEmpty()) result.add(currentLine.toString())
            }
            return result
        }

        try {
            for (page in pages) {
                var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, globalPdfPageNumber++).create()
                var pdfPage = pdfDocument.startPage(pageInfo)
                var canvas: Canvas = pdfPage.canvas
                var yPos = margin + 14f

                fun startNewPdfPage() {
                    pdfDocument.finishPage(pdfPage)
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, globalPdfPageNumber++).create()
                    pdfPage = pdfDocument.startPage(pageInfo)
                    canvas = pdfPage.canvas
                    yPos = margin + 14f
                }

                // 1. Draw Wrapped Title
                val titleLines = wrapTextToLines(page.title.ifBlank { "Archived Webpage" }, titlePaint, contentWidth.toFloat())
                for (line in titleLines) {
                    if (yPos > bottomLimit) startNewPdfPage()
                    canvas.drawText(line, margin.toFloat(), yPos, titlePaint)
                    yPos += 19f
                }
                yPos += 4f

                // 2. Draw Metadata (URL, Timestamp, Capture Duration, Model)
                val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US)
                    .format(Date(page.timestampEpochMillis))
                val durationStr = String.format(Locale.US, "%.1fs", page.captureDurationMs / 1000.0)

                wrapTextToLines("Source URL: ${page.originalUrl}", metaPaint, contentWidth.toFloat()).forEach { line ->
                    if (yPos > bottomLimit) startNewPdfPage()
                    canvas.drawText(line, margin.toFloat(), yPos, metaPaint)
                    yPos += 13f
                }
                if (yPos > bottomLimit) startNewPdfPage()
                canvas.drawText(
                    "Captured: $dateStr  •  Duration: $durationStr  |  Version: v${page.versionNumber}  •  AI: ${page.aiModelUsed}",
                    margin.toFloat(),
                    yPos,
                    metaPaint
                )
                yPos += 18f

                // 3. Draw Version Diff if present
                if (page.changeDiffSummary.isNotBlank()) {
                    if (yPos > bottomLimit) startNewPdfPage()
                    canvas.drawText("Version Comparison / Diff:", margin.toFloat(), yPos, sectionPaint)
                    yPos += 14f
                    wrapTextToLines(page.changeDiffSummary, bodyPaint, contentWidth.toFloat()).forEach { line ->
                        if (yPos > bottomLimit) startNewPdfPage()
                        canvas.drawText(line, margin.toFloat(), yPos, bodyPaint)
                        yPos += 14f
                    }
                    yPos += 8f
                }

                // 4. Draw On-Device AI Summary
                val summaryText = page.aiSummary.ifBlank { page.extractedTextSummary.take(500) }
                if (summaryText.isNotBlank()) {
                    if (yPos > bottomLimit) startNewPdfPage()
                    canvas.drawText("On-Device Summary:", margin.toFloat(), yPos, sectionPaint)
                    yPos += 14f
                    wrapTextToLines(summaryText, bodyPaint, contentWidth.toFloat()).forEach { line ->
                        if (yPos > bottomLimit) startNewPdfPage()
                        canvas.drawText(line, margin.toFloat(), yPos, bodyPaint)
                        yPos += 14f
                    }
                    yPos += 12f
                }

                // 5. Slice Full-Height Screenshot Proportionally Across Multiple PDF Pages (NEVER SQUISH)
                page.screenshotFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                            val scale = contentWidth.toFloat() / bitmap.width.toFloat()
                            var srcY = 0

                            // If very little room left on Page 1, start screenshot cleanly on Page 2
                            if (bottomLimit - yPos < 220f) {
                                startNewPdfPage()
                            }

                            while (srcY < bitmap.height) {
                                val availHeightPt = (bottomLimit - yPos).coerceAtLeast(100f)
                                // How many source bitmap vertical pixels fit in availHeightPt at 1:1 aspect ratio?
                                val maxSrcSliceHeightPx = (availHeightPt / scale).toInt().coerceAtLeast(1)
                                val actualSrcSlicePx = minOf(maxSrcSliceHeightPx, bitmap.height - srcY)
                                val destSliceHeightPt = (actualSrcSlicePx * scale).toInt()

                                val srcRect = Rect(0, srcY, bitmap.width, srcY + actualSrcSlicePx)
                                val dstRect = Rect(
                                    margin,
                                    yPos.toInt(),
                                    margin + contentWidth,
                                    yPos.toInt() + destSliceHeightPt
                                )

                                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)

                                srcY += actualSrcSlicePx
                                yPos += destSliceHeightPt + 12f

                                if (srcY < bitmap.height) {
                                    startNewPdfPage()
                                }
                            }
                            bitmap.recycle()
                        }
                    }
                }

                // 6. Paginate Full Extracted Article Text (So PDF also has clean readable text pages)
                if (page.extractedTextSummary.length > 300) {
                    if (bottomLimit - yPos < 140f) {
                        startNewPdfPage()
                    } else {
                        yPos += 10f
                    }
                    canvas.drawText("Extracted Article Body Text:", margin.toFloat(), yPos, sectionPaint)
                    yPos += 15f
                    val bodyLines = wrapTextToLines(page.extractedTextSummary, bodyPaint, contentWidth.toFloat())
                    for (line in bodyLines) {
                        if (line.isEmpty()) {
                            yPos += 8f
                            continue
                        }
                        if (yPos > bottomLimit) startNewPdfPage()
                        canvas.drawText(line, margin.toFloat(), yPos, bodyPaint)
                        yPos += 14f
                    }
                }

                pdfDocument.finishPage(pdfPage)
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, fileName)
            FileOutputStream(outFile).use { pdfDocument.writeTo(it) }
            DiagnosticLogHelper.log("Export", "Generated multi-page PDF (${globalPdfPageNumber - 1} pages): ${outFile.name}")
            outFile
        } catch (e: Exception) {
            DiagnosticLogHelper.log("ExportError", "PDF export failed: ${e.message}")
            e.printStackTrace()
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
                    if (page.ocrText.isNotBlank() && page.extractedTextSummary.trim().length < 300) {
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

    @Suppress("unused")
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
