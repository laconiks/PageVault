package com.local.pocketarchive.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.min

data class RawCaptureResult(
    val uuid: String,
    val resolvedTitle: String,
    val siteName: String?,
    val author: String?,
    val extractedDomText: String,
    val wordCount: Int,
    val mhtmlFile: File,
    val fullScreenshotFile: File,
    val thumbnailFile: File
)

class WebArchiveCaptureManager(private val context: Context) {

    companion object {
        private const val VIEWPORT_WIDTH_PX = 1080
        private const val DEFAULT_VIEWPORT_HEIGHT_PX = 2400
        private const val MAX_CAPTURE_HEIGHT_PX = 12000
        private const val THUMBNAIL_HEIGHT_PX = 720
        private const val CAPTURE_TIMEOUT_MS = 60_000L

        private val DOM_STABILIZATION_JS = """
            (function() {
                try {
                    document.querySelectorAll('img').forEach(function(img) {
                        img.setAttribute('loading', 'eager');
                        var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original');
                        if (dataSrc && (!img.src || img.src.indexOf('data:') === 0)) {
                            img.src = dataSrc;
                        }
                    });
                    var metaSite = document.querySelector('meta[property="og:site_name"]');
                    var metaAuthor = document.querySelector('meta[name="author"]') || document.querySelector('meta[property="article:author"]');
                    var rawText = (document.body && document.body.innerText) ? document.body.innerText.replace(/\s+/g, ' ').trim() : '';
                    var payload = JSON.stringify({
                        title: document.title || '',
                        siteName: metaSite ? metaSite.content : '',
                        author: metaAuthor ? metaAuthor.content : '',
                        scrollHeight: Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0),
                        bodyText: rawText.substring(0, 15000)
                    });
                    return btoa(unescape(encodeURIComponent(payload)));
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureUrl(uuid: String, targetUrl: String): Result<RawCaptureResult> = runCatching {
        withTimeout(CAPTURE_TIMEOUT_MS) {
            val archiveDir = File(context.filesDir, "archives/$uuid").apply { mkdirs() }
            val mhtmlFile = File(archiveDir, "snapshot_$uuid.mht")
            val fullScreenshotFile = File(archiveDir, "snapshot_$uuid.webp")
            val thumbnailFile = File(archiveDir, "thumb_$uuid.webp")

            withContext(Dispatchers.Main) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY)
                val initHeightSpec = View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY)

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 15; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    measure(widthSpec, initHeightSpec)
                    layout(0, 0, VIEWPORT_WIDTH_PX, DEFAULT_VIEWPORT_HEIGHT_PX)
                }

                try {
                    // Wait up to 18s for page load (news sites with ad trackers may never fire 100% onPageFinished)
                    withTimeoutOrNull(18_000L) {
                        awaitPageLoaded(webView, targetUrl)
                    }
                    delay(800)

                    for (offset in 0..3000 step 1000) {
                        webView.evaluateJavascript("window.scrollTo(0, $offset);", null)
                        delay(150)
                    }
                    webView.evaluateJavascript("window.scrollTo(0, 0);", null)
                    delay(350)

                    val base64Payload = evaluateJsSuspend(webView, DOM_STABILIZATION_JS)
                    val decodedJson = runCatching {
                        val cleanB64 = base64Payload.trim().removeSurrounding("\"")
                        if (cleanB64.isNotBlank()) {
                            String(Base64.decode(cleanB64, Base64.DEFAULT), Charsets.UTF_8)
                        } else "{}"
                    }.getOrDefault("{}")

                    val parsed = runCatching { JSONObject(decodedJson) }.getOrDefault(JSONObject())
                    val resolvedTitle = parsed.optString("title").ifBlank { webView.title ?: targetUrl }
                    val siteName = parsed.optString("siteName").takeIf { it.isNotBlank() }
                    val author = parsed.optString("author").takeIf { it.isNotBlank() }
                    val extractedText = parsed.optString("bodyText").ifBlank { resolvedTitle }
                    val wordCount = extractedText.split(Regex("\\s+")).count { it.isNotBlank() }
                    val measuredHeight = parsed.optInt("scrollHeight", DEFAULT_VIEWPORT_HEIGHT_PX)
                        .coerceIn(DEFAULT_VIEWPORT_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)

                    // Save MHTML archive with a 10s safety timeout
                    withTimeoutOrNull(10_000L) {
                        saveWebArchiveSuspend(webView, mhtmlFile.absolutePath)
                    }
                    if (!mhtmlFile.exists()) {
                        mhtmlFile.writeText("<html><head><title>$resolvedTitle</title></head><body><p>$extractedText</p></body></html>")
                    }

                    val finalHeightSpec = View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
                    webView.measure(widthSpec, finalHeightSpec)
                    webView.layout(0, 0, VIEWPORT_WIDTH_PX, measuredHeight)
                    delay(200)

                    val fullBitmap = Bitmap.createBitmap(
                        VIEWPORT_WIDTH_PX,
                        measuredHeight,
                        Bitmap.Config.RGB_565
                    )
                    val canvas = Canvas(fullBitmap)
                    webView.draw(canvas)

                    withContext(Dispatchers.IO) {
                        compressBitmapToWebp(fullBitmap, fullScreenshotFile, 80)
                        val thumbHeight = min(THUMBNAIL_HEIGHT_PX, fullBitmap.height)
                        val thumbBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, VIEWPORT_WIDTH_PX, thumbHeight)
                        compressBitmapToWebp(thumbBitmap, thumbnailFile, 75)
                        if (thumbBitmap != fullBitmap) thumbBitmap.recycle()
                        fullBitmap.recycle()
                    }

                    RawCaptureResult(
                        uuid = uuid,
                        resolvedTitle = resolvedTitle,
                        siteName = siteName,
                        author = author,
                        extractedDomText = extractedText,
                        wordCount = wordCount,
                        mhtmlFile = mhtmlFile,
                        fullScreenshotFile = fullScreenshotFile,
                        thumbnailFile = thumbnailFile
                    )
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
    }

    private suspend fun awaitPageLoaded(webView: WebView, url: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            var resumed = false
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 90 && !resumed && cont.isActive) {
                        resumed = true
                        cont.resume(Unit)
                    }
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(Unit)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && !resumed && cont.isActive) {
                        resumed = true
                        cont.resume(Unit)
                    }
                }
            }
            webView.loadUrl(url)
        }

    private suspend fun evaluateJsSuspend(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { rawResult ->
                if (cont.isActive) cont.resume(rawResult ?: "")
            }
        }

    private suspend fun saveWebArchiveSuspend(webView: WebView, filePath: String): String =
        suspendCancellableCoroutine { cont ->
            webView.saveWebArchive(filePath, false) { savedPath ->
                if (cont.isActive) {
                    cont.resume(savedPath ?: filePath)
                }
            }
        }

    private fun compressBitmapToWebp(bitmap: Bitmap, destination: File, quality: Int) {
        FileOutputStream(destination).use { out ->
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, quality, out)
        }
    }
}
