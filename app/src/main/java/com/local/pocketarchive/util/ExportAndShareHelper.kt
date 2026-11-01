package com.local.pocketarchive.util

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.local.pocketarchive.data.local.ArchivedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ExportAndShareHelper {

    suspend fun exportPageToPdf(context: Context, page: ArchivedPage): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
            val safeTitle = page.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(35).ifBlank { page.uuid.take(8) }
            val pdfFile = File(exportDir, "Pixbuchey_${safeTitle}.pdf")

            val pdfDoc = PdfDocument()
            val pageWidth = 612
            val pageHeight = 792

            val pageInfo1 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val pdfPage1 = pdfDoc.startPage(pageInfo1)
            val canvas1: Canvas = pdfPage1.canvas

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(230, 81, 0)
                textSize = 18f
                isFakeBoldText = true
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 12f
            }

            canvas1.drawText("PIXBUCHEY OFFLINE ARCHIVE", 36f, 42f, titlePaint)
            val siteFormatted = DomainColorHelper.formatWebsitePrefixedDescription(page)
            val fullText = buildString {
                appendLine("Title: ${page.title}")
                appendLine("Source: ${DomainColorHelper.extractDisplaySiteName(page)}")
                page.metadata.author?.let { appendLine("Author: $it") }
                appendLine("Original URL: ${page.originalUrl}")
                appendLine("Word Count: ${page.metadata.wordCount} words")
                if (page.tagsCsv.isNotBlank()) appendLine("AI Tags: ${page.tagsCsv}")
                appendLine("\n--- AI SUMMARY & DESCRIPTION ---")
                appendLine(siteFormatted)
                appendLine("\n--- EXTRACTED ARTICLE TEXT ---")
                appendLine(page.metadata.extractedTextSnippet.take(2500))
            }

            canvas1.save()
            canvas1.translate(36f, 65f)
            val staticLayout = StaticLayout.Builder
                .obtain(fullText, 0, fullText.length, bodyPaint, pageWidth - 72)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            staticLayout.draw(canvas1)
            canvas1.restore()
            pdfDoc.finishPage(pdfPage1)

            val screenshotPath = page.screenshotFilePath
            if (screenshotPath != null && File(screenshotPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(screenshotPath)
                if (bitmap != null) {
                    val margin = 24
                    val targetDrawWidth = pageWidth - (margin * 2)
                    val scale = targetDrawWidth.toFloat() / bitmap.width.toFloat()
                    val sliceSourceHeight = ((pageHeight - (margin * 2)) / scale).toInt().coerceAtLeast(400)

                    var yOffset = 0
                    var pageNum = 2
                    while (yOffset < bitmap.height && pageNum <= 12) {
                        val currentSliceHeight = min(sliceSourceHeight, bitmap.height - yOffset)
                        val destHeight = (currentSliceHeight * scale).toInt()
                        val pInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                        val pPage = pdfDoc.startPage(pInfo)
                        val srcRect = Rect(0, yOffset, bitmap.width, yOffset + currentSliceHeight)
                        val dstRect = Rect(margin, margin, margin + targetDrawWidth, margin + destHeight)
                        pPage.canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                        pdfDoc.finishPage(pPage)
                        yOffset += currentSliceHeight
                        pageNum++
                    }
                    bitmap.recycle()
                }
            }

            FileOutputStream(pdfFile).use { out ->
                pdfDoc.writeTo(out)
            }
            pdfDoc.close()
            pdfFile
        }
    }

    suspend fun exportAndSharePagesAsPdf(
        context: Context,
        pages: List<ArchivedPage>,
        preferGoogleDrive: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val uris = ArrayList<Uri>()
            for (page in pages) {
                val pdfFile = exportPageToPdf(context, page).getOrThrow()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdfFile
                )
                uris.add(uri)
            }
            if (uris.isEmpty()) return@runCatching

            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    putExtra(Intent.EXTRA_SUBJECT, "Pixbuchey Archive: ${pages.first().title}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/pdf"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(Intent.EXTRA_SUBJECT, "Pixbuchey Export (${uris.size} Articles)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            if (preferGoogleDrive) {
                shareIntent.setPackage("com.google.android.apps.docs")
                val canResolveDrive = shareIntent.resolveActivity(context.packageManager) != null
                if (!canResolveDrive) {
                    shareIntent.setPackage(null)
                }
            }

            val chooser = Intent.createChooser(
                shareIntent,
                if (preferGoogleDrive) "Save to Google Drive" else "Export PDF Articles"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
