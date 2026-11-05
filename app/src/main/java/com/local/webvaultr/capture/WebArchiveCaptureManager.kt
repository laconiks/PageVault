package com.local.webvaultr.capture

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
import com.local.webvaultr.util.DiagnosticLogHelper
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
    val thumbnailFile: File,
    val keywords: List<String> = emptyList()
)

class WebArchiveCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "WebArchiveCapture"
        private const val VIEWPORT_WIDTH_PX = 1080
        private const val DEFAULT_VIEWPORT_HEIGHT_PX = 2400
        private const val MAX_CAPTURE_HEIGHT_PX = 11500
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
                    var metaOgTitle = document.querySelector('meta[property="og:title"]') || document.querySelector('meta[name="twitter:title"]');
                    var h1Title = document.querySelector('h1');
                    var rawTitle = (document.title || '').trim();
                    var resolvedPageTitle = (rawTitle && rawTitle.toLowerCase().indexOf('just a moment') === -1)
                        ? rawTitle
                        : ((metaOgTitle && metaOgTitle.content) || (h1Title && (h1Title.innerText || h1Title.textContent)) || rawTitle);

                    // Extract publisher meta keywords and JSON-LD keywords
                    var metaKeywords = [];
                    document.querySelectorAll('meta[property="article:tag"], meta[name="keywords"], meta[name="news_keywords"]').forEach(function(m) {
                        var c = m.getAttribute('content');
                        if (c) {
                            c.split(',').forEach(function(k) {
                                var trimmed = k.trim();
                                if (trimmed.length > 1 && trimmed.length < 35 && metaKeywords.indexOf(trimmed) === -1) {
                                    metaKeywords.push(trimmed);
                                }
                            });
                        }
                    });
                    try {
                        document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s) {
                            var d = JSON.parse(s.textContent || '{}');
                            var items = Array.isArray(d) ? d : (d['@graph'] ? d['@graph'] : [d]);
                            items.forEach(function(item) {
                                if (item && item.keywords) {
                                    var kwList = Array.isArray(item.keywords) ? item.keywords : item.keywords.split(',');
                                    kwList.forEach(function(k) {
                                        if (typeof k === 'string') {
                                            var trimmed = k.trim();
                                            if (trimmed.length > 1 && trimmed.length < 35 && metaKeywords.indexOf(trimmed) === -1) {
                                                metaKeywords.push(trimmed);
                                            }
                                        }
                                    });
                                }
                            });
                        });
                    } catch(e) {}

                    var root = document.querySelector('article') ||
                               document.querySelector('[role="main"]') ||
                               document.querySelector('[class*="article-body__content"]') ||
                               document.querySelector('[class*="article-body"]') ||
                               document.querySelector('[class*="post-content"]') ||
                               document.querySelector('main') ||
                               document.body;
                    var clone = root.cloneNode(true);

                    // 1. Strip non-article website UI elements from the text clone
                    var junkSelectors = [
                        'nav', 'header', 'footer', 'aside', 'script', 'style', 'noscript', 'svg', 'form', 'button',
                        '[role="navigation"]', '[role="banner"]', '[role="complementary"]',
                        '[class*="nav"]', '[class*="menu"]', '[class*="header"]', '[class*="footer"]',
                        '[class*="sidebar"]', '[class*="comment"]', '[id*="comment"]',
                        '[class*="related"]', '[class*="recommended"]', '[class*="newsletter"]',
                        '[class*="promo"]', '[class*="ad-"]', '[class*="advert"]', '[class*="social"]',
                        '[class*="share"]', '[class*="byline"]', '[class*="author-bio"]',
                        '[class*="video-player"]', '[class*="featured-video"]', '[class*="duet--recirculation"]',
                        '[class*="embedded-video"]', '[class*="inline-video"]',
                        '.story-interrupt', '.ad-slot', '[class*="story-interrupt"]', '[class*="ad-slot"]',
                        'figcaption', 'figure'
                    ];
                    junkSelectors.forEach(function(sel) {
                        clone.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                    });

                    // 2. Noise phrases injected inline by publishers
                    var noiseLinePatterns = [
                        /^skip to main content/i,
                        /^the homepage$/i,
                        /^subscribe$/i,
                        /^featured videos from/i,
                        /^add .* on google/i,
                        /^add .* as a preferred source/i,
                        /^follow topics and authors/i,
                        /^\d+\s+comments/i,
                        /^image:\s+/i,
                        /^photo:\s+/i,
                        /^advertisement$/i,
                        /is a senior correspondent and author of/i
                    ];

                    function isNoise(text) {
                        var t = text.trim();
                        if (t.length < 25) return true;
                        for (var i = 0; i < noiseLinePatterns.length; i++) {
                            if (noiseLinePatterns[i].test(t)) return true;
                        }
                        return false;
                    }

                    // 3. Walk only semantic text blocks and preserve Markdown structure
                    var blocks = clone.querySelectorAll('p, h2, h3, blockquote');
                    var paragraphs = [];
                    var seen = {};

                    blocks.forEach(function(b) {
                        if (b.tagName === 'P' && b.closest('blockquote')) return;
                        var txt = (b.innerText || b.textContent || '').replace(/\s+/g, ' ').trim();
                        if (!isNoise(txt) && !seen[txt]) {
                            seen[txt] = true;
                            if (b.tagName === 'H2' || b.tagName === 'H3') {
                                paragraphs.push('#### ' + txt);
                            } else if (b.tagName === 'BLOCKQUOTE') {
                                paragraphs.push('> ' + txt);
                            } else {
                                paragraphs.push(txt);
                            }
                        }
                    });

                    var articleMarkdown = '';
                    if (paragraphs.length >= 2) {
                        articleMarkdown = paragraphs.join('\n\n');
                    } else {
                        articleMarkdown = (clone.innerText || '')
                            .split(/\n+/)
                            .map(function(l) { return l.trim(); })
                            .filter(function(l) { return !isNoise(l); })
                            .join('\n\n');
                    }

                    if (!articleMarkdown || articleMarkdown.length < 100) {
                        articleMarkdown = (document.body && document.body.innerText) ? document.body.innerText.trim() : '';
                    }

                    var payload = JSON.stringify({
                        title: resolvedPageTitle || '',
                        siteName: metaSite ? metaSite.content : '',
                        author: metaAuthor ? metaAuthor.content : '',
                        scrollHeight: Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0),
                        bodyText: articleMarkdown.substring(0, 75000),
                        keywords: metaKeywords.slice(0, 10)
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
            onStageUpdate?.invoke("Step 1/4: Connecting & archiving page...")
            DiagnosticLogHelper.log(TAG, "Starting capture for $targetUrl [uuid=$uuid, spoofCrawler=$spoofCrawler, disableJs=$disableJs]")
            val archiveDir = File(context.filesDir, "archives/$uuid").apply { mkdirs() }
            val mhtmlFile = File(archiveDir, "snapshot_$uuid.mht")
            val fullScreenshotFile = File(archiveDir, "snapshot_$uuid.webp")
            val thumbnailFile = File(archiveDir, "thumb_$uuid.webp")

            withContext(Dispatchers.Main) {
                runCatching { WebView.enableSlowWholeDocumentDraw() }
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
                        DiagnosticLogHelper.log(TAG, "Scroll-priming lazy elements and unmasking DOM")
                        for (offset in listOf(1500, 3500, 6000, 8500, 0)) {
                            webView.evaluateJavascript("window.scrollTo(0, $offset);", null)
                            delay(100)
                        }

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
                    var extractedText = PaywallBypassHelper.decodeRot1IfObfuscated(parsed.optString("bodyText").ifBlank { resolvedTitle })

                    val keywordsArr = parsed.optJSONArray("keywords")
                    val extractedKeywords = buildList {
                        if (keywordsArr != null) {
                            for (i in 0 until keywordsArr.length()) {
                                val k = keywordsArr.optString(i).trim()
                                if (k.isNotBlank() && !contains(k)) add(k)
                            }
                        }
                    }

                    // Measure true document height in physical device pixels
                    val density = context.resources.displayMetrics.density
                    val rawScrollHeight = parsed.optInt("scrollHeight", 0)
                    val calculatedHeight = if (rawScrollHeight > 0) {
                        (rawScrollHeight * density).toInt()
                    } else {
                        DEFAULT_VIEWPORT_HEIGHT_PX
                    }
                    var measuredHeight = calculatedHeight.coerceIn(DEFAULT_VIEWPORT_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)

                    DiagnosticLogHelper.log(TAG, "DOM extracted: title='$resolvedTitle', rawScrollHeight=$rawScrollHeight, density=$density -> measuredHeight=$measuredHeight, keywords=${extractedKeywords.size}")

                    // Fallback DOM Injection: If extracted text is short (< 500 chars) or restricted,
                    // fetch reader fallback and replace WebView body with clean reader view BEFORE MHTML & screenshot!
                    if (extractedText.trim().length < 500 || PaywallBypassHelper.isPaywallOrCaptchaBlocked(extractedText)) {
                        DiagnosticLogHelper.log(TAG, "Short extracted DOM text (${extractedText.trim().length} chars). Fetching reader fallback text for $targetUrl")
                        val cleanFallback = withContext(Dispatchers.IO) {
                            PaywallBypassHelper.fetchReaderFallbackText(targetUrl)
                        }
                        if (!cleanFallback.isNullOrBlank() && cleanFallback.length > extractedText.length) {
                            DiagnosticLogHelper.log(TAG, "Full fallback text retrieved (${cleanFallback.length} chars). Injecting clean reader view into live WebView DOM...")
                            extractedText = PaywallBypassHelper.decodeRot1IfObfuscated(cleanFallback)
                            PaywallBypassHelper.injectCleanReaderFallbackIntoWebView(webView, extractedText, resolvedTitle)
                            delay(250)

                            val newScrollHeightStr = withTimeoutOrNull(2000L) {
                                evaluateJsSuspend(webView, "Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0)")
                            }
                            val newScrollHeight = newScrollHeightStr?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                            if (newScrollHeight > 0) {
                                val newCalculated = (newScrollHeight * density).toInt()
                                if (newCalculated > measuredHeight) {
                                    measuredHeight = newCalculated.coerceIn(DEFAULT_VIEWPORT_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)
                                    DiagnosticLogHelper.log(TAG, "Recalculated measuredHeight after fallback injection: $measuredHeight")
                                }
                            }
                        }
                    }

                    onStageUpdate?.invoke("Step 2/4: Locking page layout & capturing preview image...")
                    // Freeze DOM before saving MHTML
                    withTimeoutOrNull(1000L) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            PaywallBypassHelper.freezeDomBeforeMhtmlSave(webView) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }

                    // Save MHTML archive with a 6s safety timeout from the true visual WebView
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

                    // Capture screenshot + thumbnail from true visual WebView
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

                    val wordCount = extractedText.split(Regex("\\s+")).count { it.isNotBlank() }

                    RawCaptureResult(
                        uuid = uuid,
                        resolvedTitle = resolvedTitle,
                        siteName = siteName,
                        author = author,
                        extractedDomText = extractedText,
                        wordCount = wordCount,
                        mhtmlFile = mhtmlFile,
                        fullScreenshotFile = fullScreenshotFile,
                        thumbnailFile = thumbnailFile,
                        keywords = extractedKeywords
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
                        thumbnailFile = thumbnailFile,
                        keywords = emptyList()
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
            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, startedUrl, favicon)
                PaywallBypassHelper.injectStealthAntiDetection(view ?: webView)
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                val currentTitle = (view ?: webView).title.orEmpty()
                if (currentTitle.contains("Just a moment", ignoreCase = true) || currentTitle.contains("Checking your browser", ignoreCase = true)) {
                    DiagnosticLogHelper.log(TAG, "onPageFinished fired with '$currentTitle' placeholder; waiting for hydration")
                    return
                }
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
