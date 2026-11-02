package com.local.pocketarchive.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.local.pocketarchive.util.DiagnosticLogHelper
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
        private const val TAG = "WebArchiveCapture"
        private const val VIEWPORT_WIDTH_PX = 1080
        private const val DEFAULT_VIEWPORT_HEIGHT_PX = 2400
        private const val MAX_CAPTURE_HEIGHT_PX = 6000
        private const val THUMBNAIL_HEIGHT_PX = 720
        private const val CAPTURE_TIMEOUT_MS = 60_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 12_000L

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
    suspend fun captureUrl(
        uuid: String,
        targetUrl: String,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false,
        onStageUpdate: ((String) -> Unit)? = null
    ): Result<RawCaptureResult> = runCatching {
        withTimeout(CAPTURE_TIMEOUT_MS) {
            onStageUpdate?.invoke("Step 1/4: Connecting & bypassing paywall...")
            DiagnosticLogHelper.log(TAG, "Starting capture for $targetUrl [uuid=$uuid, spoofCrawler=$spoofCrawler, disableJs=$disableJs]")
            val archiveDir = File(context.filesDir, "archives/$uuid").apply { mkdirs() }
            val mhtmlFile = File(archiveDir, "snapshot_$uuid.mht")
            val fullScreenshotFile = File(archiveDir, "snapshot_$uuid.webp")
            val thumbnailFile = File(archiveDir, "thumb_$uuid.webp")

            withContext(Dispatchers.Main) {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY)
                val initHeightSpec = View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY)

                val webView = WebView(context).apply {
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    setBackgroundColor(Color.WHITE)
                    measure(widthSpec, initHeightSpec)
                    layout(0, 0, VIEWPORT_WIDTH_PX, DEFAULT_VIEWPORT_HEIGHT_PX)
                }

                PaywallBypassHelper.configureWebViewForCapture(
                    webView = webView,
                    spoofCrawler = spoofCrawler,
                    disableJs = disableJs
                )

                try {
                    // 12-second DOM-hydration fallback: ad scripts/trackers never hang capture
                    DiagnosticLogHelper.log(TAG, "Awaiting page readiness (max ${PAGE_LOAD_TIMEOUT_MS / 1000}s)...")
                    val loadSuccess = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                        awaitPageLoaded(webView, targetUrl, spoofCrawler)
                        true
                    }
                    if (loadSuccess == null) {
                        DiagnosticLogHelper.log(TAG, "12s load timeout reached; proceeding with DOM hydration and capture fallback")
                    } else {
                        DiagnosticLogHelper.log(TAG, "Page load completed within 12s limit")
                    }

                    delay(500)

                    if (!disableJs) {
                        DiagnosticLogHelper.log(TAG, "Simulating user scroll and unmasking DOM")
                        for (offset in 0..2000 step 1000) {
                            webView.evaluateJavascript("window.scrollTo(0, $offset);", null)
                            delay(100)
                        }
                        webView.evaluateJavascript("window.scrollTo(0, 0);", null)
                        delay(200)

                        withTimeoutOrNull(3000L) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                PaywallBypassHelper.unlockAndSanitizeDom(webView) {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        }
                    }

                    DiagnosticLogHelper.log(TAG, "Extracting DOM stabilization payload")
                    val base64Payload = withTimeoutOrNull(4000L) {
                        evaluateJsSuspend(webView, DOM_STABILIZATION_JS)
                    } ?: ""

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
                    var extractedText = parsed.optString("bodyText").ifBlank { resolvedTitle }
                    var measuredHeight = parsed.optInt("scrollHeight", DEFAULT_VIEWPORT_HEIGHT_PX)
                        .coerceIn(DEFAULT_VIEWPORT_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)

                    // Check for hard paywall / CAPTCHA blocks
                    if (PaywallBypassHelper.isPaywallOrCaptchaBlocked(extractedText)) {
                        DiagnosticLogHelper.log(TAG, "Paywall / CAPTCHA block detected. Attempting Jina clean reader extraction...")
                        val cleanReaderText = withContext(Dispatchers.IO) {
                            PaywallBypassHelper.fetchCleanReaderText(targetUrl)
                        }
                        if (!cleanReaderText.isNullOrBlank() && cleanReaderText.length > extractedText.length) {
                            DiagnosticLogHelper.log(TAG, "Clean reader text extracted (${cleanReaderText.length} chars). Overriding blocked text and injecting into DOM.")
                            extractedText = cleanReaderText
                            val escapedReaderText = JSONObject.quote(cleanReaderText)
                            val injectJs = """
                                (function() {
                                    var container = document.createElement('div');
                                    container.id = 'pagevault-clean-reader-container';
                                    container.style.cssText = 'position:relative; z-index:999999; background:#ffffff; color:#1a1a1a; padding:24px; font-family:sans-serif; font-size:18px; line-height:1.6; max-width:800px; margin:0 auto;';
                                    container.innerHTML = '<h1 style="font-size:26px; margin-bottom:16px;">' + (document.title || '') + '</h1><div style="white-space:pre-wrap;">' + $escapedReaderText + '</div>';
                                    if (document.body) {
                                        document.body.innerHTML = '';
                                        document.body.appendChild(container);
                                    }
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(injectJs, null)
                            delay(200)

                            val newHeightStr = withTimeoutOrNull(2000L) {
                                evaluateJsSuspend(webView, "Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0)")
                            }
                            val newHeight = newHeightStr?.trim()?.removeSurrounding("\"")?.toIntOrNull()
                            if (newHeight != null && newHeight > 0) {
                                measuredHeight = newHeight.coerceIn(DEFAULT_VIEWPORT_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)
                            }
                        }
                    }

                    val wordCount = extractedText.split(Regex("\\s+")).count { it.isNotBlank() }

                    DiagnosticLogHelper.log(TAG, "DOM extracted: title='$resolvedTitle', words=$wordCount, measuredHeight=$measuredHeight")

                    onStageUpdate?.invoke("Step 2/4: Locking page layout & capturing preview image...")
                    // Save MHTML archive with a 6s safety timeout
                    DiagnosticLogHelper.log(TAG, "Saving MHTML web archive...")
                    withTimeoutOrNull(6_000L) {
                        saveWebArchiveSuspend(webView, mhtmlFile.absolutePath)
                    }
                    if (!mhtmlFile.exists() || mhtmlFile.length() == 0L) {
                        DiagnosticLogHelper.log(TAG, "MHTML missing/empty, generating synthetic HTML archive")
                        mhtmlFile.writeText("<html><head><title>$resolvedTitle</title></head><body><h1>$resolvedTitle</h1><p>$extractedText</p></body></html>")
                    } else {
                        DiagnosticLogHelper.log(TAG, "MHTML archive saved successfully (${mhtmlFile.length()} bytes)")
                    }

                    // ALWAYS capture screenshot + thumbnail even if page loaded slowly
                    DiagnosticLogHelper.log(TAG, "Capturing WebView screenshot ($VIEWPORT_WIDTH_PX x $measuredHeight)...")
                    val finalHeightSpec = View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
                    webView.measure(widthSpec, finalHeightSpec)
                    webView.layout(0, 0, VIEWPORT_WIDTH_PX, measuredHeight)
                    delay(250)

                    val fullBitmap = runCatching {
                        val bmp = Bitmap.createBitmap(
                            VIEWPORT_WIDTH_PX,
                            measuredHeight,
                            Bitmap.Config.RGB_565
                        )
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        webView.draw(canvas)
                        bmp
                    }.getOrElse { err ->
                        DiagnosticLogHelper.log(TAG, "Bitmap allocation failed ($err), falling back to default viewport height")
                        val fallbackBmp = Bitmap.createBitmap(VIEWPORT_WIDTH_PX, DEFAULT_VIEWPORT_HEIGHT_PX, Bitmap.Config.RGB_565)
                        val canvas = Canvas(fallbackBmp)
                        canvas.drawColor(Color.WHITE)
                        webView.draw(canvas)
                        fallbackBmp
                    }

                    withContext(Dispatchers.IO) {
                        compressBitmapToWebp(fullBitmap, fullScreenshotFile, 80)
                        val thumbHeight = min(THUMBNAIL_HEIGHT_PX, fullBitmap.height)
                        val thumbBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, VIEWPORT_WIDTH_PX, thumbHeight)
                        compressBitmapToWebp(thumbBitmap, thumbnailFile, 75)
                        if (thumbBitmap != fullBitmap) thumbBitmap.recycle()
                        fullBitmap.recycle()
                    }

                    DiagnosticLogHelper.log(TAG, "Screenshot and thumbnail saved successfully")

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
                } catch (t: Throwable) {
                    DiagnosticLogHelper.log(TAG, "Capture encounter exception: ${t.localizedMessage}. Performing fallback.")
                    if (!mhtmlFile.exists() || mhtmlFile.length() == 0L) {
                        mhtmlFile.writeText("<html><head><title>$targetUrl</title></head><body><h1>$targetUrl</h1><p>Capture fallback generated.</p></body></html>")
                    }
                    if (!fullScreenshotFile.exists() || fullScreenshotFile.length() == 0L) {
                        val fallbackBmp = Bitmap.createBitmap(VIEWPORT_WIDTH_PX, DEFAULT_VIEWPORT_HEIGHT_PX, Bitmap.Config.RGB_565)
                        val canvas = Canvas(fallbackBmp)
                        canvas.drawColor(Color.WHITE)
                        runCatching { webView.draw(canvas) }
                        withContext(Dispatchers.IO) {
                            compressBitmapToWebp(fallbackBmp, fullScreenshotFile, 80)
                            val thumbHeight = min(THUMBNAIL_HEIGHT_PX, fallbackBmp.height)
                            val thumbBitmap = Bitmap.createBitmap(fallbackBmp, 0, 0, VIEWPORT_WIDTH_PX, thumbHeight)
                            compressBitmapToWebp(thumbBitmap, thumbnailFile, 75)
                            if (thumbBitmap != fallbackBmp) thumbBitmap.recycle()
                            fallbackBmp.recycle()
                        }
                    }
                    RawCaptureResult(
                        uuid = uuid,
                        resolvedTitle = webView.title?.ifBlank { targetUrl } ?: targetUrl,
                        siteName = null,
                        author = null,
                        extractedDomText = webView.title ?: targetUrl,
                        wordCount = 1,
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

    private suspend fun awaitPageLoaded(
        webView: WebView,
        url: String,
        spoofCrawler: Boolean = true
    ) = suspendCancellableCoroutine<Unit> { cont ->
        var resumed = false

        fun resumeSafely(reason: String) {
            if (!resumed && cont.isActive) {
                resumed = true
                DiagnosticLogHelper.log(TAG, "Page load trigger reached: $reason for $url")
                cont.resume(Unit)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                DiagnosticLogHelper.log(TAG, "onProgressChanged: $newProgress%")
                if (newProgress >= 75) {
                    val targetView = view ?: webView
                    targetView.evaluateJavascript("(document.body && document.body.innerText) ? document.body.innerText.trim().length : 0") { lenStr ->
                        val textLen = lenStr?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                        if (textLen >= 800 || newProgress >= 98) {
                            PaywallBypassHelper.unlockAndSanitizeDom(targetView) {
                                resumeSafely("onProgressChanged >= 75 (progress=$newProgress%, textLen=$textLen)")
                            }
                        }
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                DiagnosticLogHelper.log(TAG, "onPageFinished: $finishedUrl")
                val targetView = view ?: webView
                PaywallBypassHelper.unlockAndSanitizeDom(targetView) {
                    resumeSafely("onPageFinished")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    DiagnosticLogHelper.log(TAG, "onReceivedError (main frame): ${error?.description}")
                    resumeSafely("onReceivedError: ${error?.description}")
                }
            }
        }

        if (spoofCrawler) {
            DiagnosticLogHelper.log(TAG, "Loading URL with Referer header: $url")
            webView.loadUrl(url, PaywallBypassHelper.SPOOFED_HEADERS)
        } else {
            DiagnosticLogHelper.log(TAG, "Loading URL: $url")
            webView.loadUrl(url)
        }
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
