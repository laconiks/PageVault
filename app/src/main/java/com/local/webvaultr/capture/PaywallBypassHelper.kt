package com.local.webvaultr.capture

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.local.webvaultr.util.DiagnosticLogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PaywallBypassHelper {

    private const val TAG = "WebArchiveSanitizer"

    const val CHROME_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    const val GOOGLEBOT_USER_AGENT =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    val SPOOFED_HEADERS = mapOf(
        "Referer" to "https://www.google.com/"
    )

    private val PAYWALL_BLOCK_PATTERNS = listOf(
        "are you a robot",
        "subscribe to continue",
        "go beyond the headline",
        "two ways to read",
        "verify you are human",
        "access to this page has been denied",
        "subscription required",
        "to continue reading",
        "sign in to continue",
        "exclusive for subscribers",
        "please enable js",
        "complete captcha",
        "read in the substack app",
        "this post is for paid subscribers",
        "upgrade to paid",
        "open in the substack app",
        "let's be inbox friends"
    )

    /**
     * Resolves the device's real native Chrome Mobile User-Agent with WebView markers ('; wv' and 'Version/X.X')
     * stripped, matching an authentic Pixel browser for TLS and bot detection bypass (NYT, Bizjournals, NBC).
     */
    fun getAuthenticChromeUserAgent(context: Context): String {
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        return if (!defaultUa.isNullOrBlank()) {
            defaultUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        } else {
            CHROME_MOBILE_USER_AGENT
        }
    }

    /**
     * Injects stealth anti-bot bypass properties at onPageStarted (navigator.webdriver = false, window.chrome).
     */
    fun injectStealthAntiDetection(webView: WebView) {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                    window.chrome = window.chrome || { runtime: {} };
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Determines if extracted DOM text represents a hard paywall, subscription barrier, or CAPTCHA blocking screen.
     */
    fun isPaywallOrCaptchaBlocked(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.length < 400) return true
        return PAYWALL_BLOCK_PATTERNS.any { pattern -> clean.contains(pattern) }
    }

    /**
     * Detects if a paragraph consists of character-reversed obfuscated words
     * (e.g. "saw elbarenluv rotciv... ot eht").
     */
    fun isReversedWordParagraph(paragraph: String): Boolean {
        val words = Regex("\\b[a-zA-Z]{2,}\\b").findAll(paragraph).map { it.value.lowercase() }.toList()
        if (words.size < 6) return false
        // Common English stopwords spelled backwards ("the"->"eht", "and"->"dna", "from"->"morf", "his"->"sih", "but"->"tub", "with"->"htiw", "they"->"yeht", "were"->"erew", "that"->"taht")
        val reversedStopwords = setOf("eht", "dna", "morf", "sih", "tub", "htiw", "yeht", "erew", "taht", "rof", "tnebmucni", "citarcomed", "nacilbuper")
        val hits = words.count { it in reversedStopwords }
        return hits >= 3 || (hits.toFloat() / words.size.toFloat()) >= 0.12f
    }

    /**
     * Filters out reversed-word obfuscated paragraphs from text corpus.
     */
    fun stripReversedWordGibberish(input: String): String {
        if (input.isBlank()) return input
        return input.split(Regex("\n{2,}"))
            .filterNot { isReversedWordParagraph(it) }
            .joinToString("\n\n")
            .trim()
    }

    /**
     * Fetches unblocked clean article text via Jina Reader fallback (r.jina.ai) for server-enforced paywalls.
     */
    suspend fun fetchCleanReaderText(targetUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            DiagnosticLogHelper.log(TAG, "Fetching clean reader fallback via r.jina.ai for $targetUrl")
            val cleanTarget = if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                "https://$targetUrl"
            } else {
                targetUrl
            }
            val readerUrl = "https://r.jina.ai/$cleanTarget"
            val connection = (URL(readerUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", CHROME_MOBILE_USER_AGENT)
                setRequestProperty("Accept", "text/plain, text/markdown")
                instanceFollowRedirects = true
            }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val lowerRaw = raw.lowercase()
                if (lowerRaw.contains("performing security verification") ||
                    lowerRaw.contains("warning: this page maybe requiring captcha") ||
                    lowerRaw.contains("verify you are human") ||
                    lowerRaw.contains("select a city") ||
                    lowerRaw.contains("access denied") ||
                    lowerRaw.contains("attention required! | cloudflare") ||
                    lowerRaw.contains("just a moment...")
                ) {
                    DiagnosticLogHelper.log(TAG, "Reader fallback contained challenge/captcha - rejecting")
                    null
                } else {
                    val cleanedLines = raw.lines()
                        .filterNot { line ->
                            val trimmed = line.trim()
                            val lower = trimmed.lowercase()
                            trimmed.startsWith("Title:", ignoreCase = true) ||
                                trimmed.startsWith("URL Source:", ignoreCase = true) ||
                                trimmed.startsWith("Published Time:", ignoreCase = true) ||
                                trimmed.startsWith("Markdown Content:", ignoreCase = true) ||
                                trimmed.startsWith("* [") ||
                                trimmed.startsWith("- [") ||
                                trimmed.startsWith("+ [") ||
                                lower.startsWith("[skip to") ||
                                lower.contains("bgmenu_") ||
                                lower.contains("select a city") ||
                                (trimmed.startsWith("[") && trimmed.endsWith(")"))
                        }
                        .map { line ->
                            line.replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1").trim()
                        }
                        .filter { line ->
                            if (line.isBlank()) return@filter false
                            if (line.startsWith("#")) return@filter true
                            if (line.length >= 45) {
                                val lastChar = line.last()
                                lastChar == '.' || lastChar == '"' || lastChar == '”' || lastChar == '?' || lastChar == '!' || line.contains(". ")
                            } else {
                                false
                            }
                        }
                        .joinToString("\n\n")
                        .trim()

                    val filteredText = stripReversedWordGibberish(cleanedLines)
                    if (filteredText.length > 400) {
                        DiagnosticLogHelper.log(TAG, "Successfully fetched clean reader text (${filteredText.length} chars)")
                        filteredText
                    } else {
                        DiagnosticLogHelper.log(TAG, "Reader text too short after filtering (${filteredText.length} chars)")
                        null
                    }
                }
            } else {
                DiagnosticLogHelper.log(TAG, "Jina reader fallback returned HTTP $responseCode")
                null
            }
        } catch (e: Exception) {
            DiagnosticLogHelper.log(TAG, "Jina reader fallback failed: ${e.message}")
            null
        }
    }

    /**
     * Alias for fetchCleanReaderText for fallback reader extraction.
     */
    suspend fun fetchReaderFallbackText(targetUrl: String): String? = fetchCleanReaderText(targetUrl)

    /**
     * Unscrambles NY Daily News / Tribune Publishing ROT-1 Caesar cipher text ("uif gjfme" -> "the field")
     * in Kotlin once, avoiding duplicate double-shifts.
     */
    fun decodeRot1IfObfuscated(input: String): String {
        if (input.isBlank()) return input
        val rot1Markers = setOf(
            "uif", "boe", "xbt", "gspn", "pwfs", "up", "jo", "gps", "xjui",
            "tbje", "sfqmjfe", "mbufs", "ejfe", "cfhbo", "mfbwf", "cfgpsf",
            "xbmlfe", "xijtqfsfe", "uibol", "zpv", "xfmdpnf", "xfflt", "cpui", "ufbnt"
        )
        val plainMarkers = setOf(
            "the", "and", "was", "from", "over", "with", "that", "this", "have", "were", "said", "before"
        )

        fun shiftRot1(s: String): String = buildString(s.length) {
            for (ch in s) {
                append(
                    when (ch) {
                        in 'b'..'z' -> ch - 1
                        'a' -> 'z'
                        in 'B'..'Z' -> ch - 1
                        'A' -> 'Z'
                        else -> ch
                    }
                )
            }
        }

        return input.lines().joinToString("\n") { line ->
            val words = Regex("[a-zA-Z]+").findAll(line).map { it.value.lowercase() }.toList()
            val rotCount = words.count { it in rot1Markers }
            if (rotCount == 0) {
                line
            } else {
                val plainCount = words.count { it in plainMarkers }
                if (plainCount == 0 || rotCount > plainCount * 2) {
                    shiftRot1(line)
                } else {
                    val firstRotMatch = Regex("\\b(uif|boe|xbt|gspn|pwfs|Qmbzfst|Spcjotpo|Npshbo|boopvodfnfou)\\b", RegexOption.IGNORE_CASE)
                        .find(line)
                    if (firstRotMatch != null) {
                        val splitIdx = firstRotMatch.range.first
                        line.substring(0, splitIdx) + shiftRot1(line.substring(splitIdx))
                    } else {
                        line
                    }
                }
            }
        }
    }

    /**
     * Injects clean formatted reader HTML into the WebView body when extracted DOM text is paywalled or blank (< 500 chars),
     * so both the MHTML archive and full-height screenshot display the complete formatted article without wiping masthead/headers.
     */
    fun injectCleanReaderFallbackIntoWebView(webView: WebView, cleanText: String, title: String) {
        val escapedTitle = JSONObject.quote(title)
        val escapedBody = JSONObject.quote(cleanText)
        val js = """
            (function() {
                try {
                    var t = $escapedTitle;
                    var b = $escapedBody;
                    var paras = b.split(/\n+/).map(function(p) { return p.trim(); }).filter(function(p) { return p.length > 0; });
                    var target = document.querySelector('article') ||
                                 document.querySelector('main') ||
                                 document.querySelector('#article-body') ||
                                 document.querySelector('.story-text') ||
                                 document.querySelector('[role="main"]');

                    var container = document.createElement('div');
                    container.id = 'webvaultr-fallback-body';
                    container.style.cssText = 'max-width: 760px; margin: 20px auto; padding: 16px; font-family: -apple-system, BlinkMacSystemFont, Roboto, Georgia, serif; font-size: 18px; line-height: 1.65; color: #111;';

                    paras.forEach(function(p) {
                        var pEl = document.createElement('p');
                        pEl.style.cssText = 'margin-bottom: 16px;';
                        pEl.textContent = p;
                        container.appendChild(pEl);
                    });

                    if (target) {
                        target.appendChild(container);
                    } else {
                        var h1 = document.querySelector('h1');
                        if (h1 && h1.parentNode) {
                            h1.parentNode.insertBefore(container, h1.nextSibling);
                        } else {
                            document.body.appendChild(container);
                        }
                    }
                    window.scrollTo(0, 0);
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Ultra-lightweight DOM freeze before MHTML save to ensure native saveWebArchive completes rapidly without renderer lockup.
     */
    @Suppress("UNUSED_PARAMETER")
    fun freezeDomBeforeMhtmlSave(webView: WebView, onComplete: () -> Unit) {
        onComplete()
    }

    /**
     * Configures WebView settings prior to loadUrl().
     * Uses device native authentic Chrome Mobile User-Agent with Google Referer header to bypass bot blocks.
     */
    fun configureWebViewForCapture(
        webView: WebView,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false
    ) {
        val ua = getAuthenticChromeUserAgent(webView.context)
        DiagnosticLogHelper.log(TAG, "Configuring WebView settings (jsEnabled=${!disableJs}, spoofCrawler=$spoofCrawler, UA=$ua)")
        webView.settings.apply {
            javaScriptEnabled = !disableJs
            domStorageEnabled = true
            userAgentString = ua
        }
        runCatching {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /**
     * Injects surgical DOM unmasking, text-aware gate removal, horizontal width constraints,
     * and structured content hydration using zero-reflow CSS and bounded checks before capture.
     */
    fun unlockAndSanitizeDom(webView: WebView, onComplete: () -> Unit) {
        if (!webView.settings.javaScriptEnabled) {
            onComplete()
            return
        }

        val unlockScript = """
            (function() {
                try {
                    // 1. Fast zero-reflow CSS rule injection (<style id="webvaultr-fit-style">)
                    if (!document.getElementById('webvaultr-fit-style')) {
                        var styleEl = document.createElement('style');
                        styleEl.id = 'webvaultr-fit-style';
                        styleEl.textContent = 'html, body { max-width: 100vw !important; overflow-x: hidden !important; overflow-y: visible !important; max-height: none !important; height: auto !important; position: static !important; } ' +
                            'article, main, [role="main"], [class*="article-body"], [class*="story-body"], [class*="post-content"] { overflow: visible !important; max-height: none !important; height: auto !important; -webkit-mask-image: none !important; mask-image: none !important; } ' +
                            '[style*="blur"], .blur, [class*="blur"] { filter: none !important; -webkit-filter: none !important; } ' +
                            '.story-interrupt, .ad-slot, [class*="story-interrupt"], [class*="ad-slot"], [id*="story-interrupt"], [id*="ad-slot"], [data-ad-slot], [class*="story-ad"], [class*="ad-container"], [class*="ad-wrapper"], aside.ad, div.ad, div[id*="google_ads"], div[id*="dfp-ad"], [class*="tp-backdrop"], [class*="tp-modal"], [class*="piano-root"], [class*="paywall-overlay"], [class*="regwall"], #onetrust-consent-sdk, [class*="sp_message"], [class*="cmp-modal"] { display: none !important; }';
                        (document.head || document.documentElement).appendChild(styleEl);
                    }

                    // 2. Remove blocking paywall/cookie modal overlays
                    var modalSelectors = [
                        '[class*="paywall-"]', '[id*="paywall"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                        '[class*="piano-"]', '[id*="piano-"]', '[class*="regwall"]', '[data-qa*="paywall"]',
                        '[class*="subscription-wall"]', '[class*="meter-wall"]', '[id*="gateway-content"]',
                        '[class*="offer-gate"]', '[class*="paywall-offer"]', '[id*="offer-gate"]',
                        '[class*="onetrust"]', '[id*="onetrust"]', '[class*="usercentrics"]', '[class*="qc-cmp"]',
                        '#onetrust-consent-sdk', '[class*="substack-modal"]', 'div.modal-wrapper',
                        '[class*="connatix"]', '[id*="connatix"]', 'div[class*="cnx-"]'
                    ];
                    modalSelectors.forEach(function(sel) {
                        document.querySelectorAll(sel).forEach(function(el) {
                            if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                                el.remove();
                            }
                        });
                    });

                    // 2b. Bounded check (txt.length < 950 and p.length < 5) for offer gates, subscription overlays, cookie drawers
                    document.querySelectorAll('div, section, aside, dialog').forEach(function(el) {
                        if (el === document.body || el === document.documentElement || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                        if (el.querySelector('article, main')) return;
                        var txt = (el.innerText || '').trim();
                        if (txt.length > 0 && txt.length < 950 && el.querySelectorAll('p').length < 5) {
                            var lower = txt.toLowerCase();
                            if (lower.indexOf('manage cookie preferences') !== -1 ||
                                lower.indexOf('cookie banner') !== -1 ||
                                lower.indexOf('claim offer') !== -1 ||
                                lower.indexOf('two ways to read this article') !== -1 ||
                                lower.indexOf('subscribe to continue') !== -1 ||
                                lower.indexOf('already a subscriber? sign in') !== -1 ||
                                lower.indexOf('advertisement') === 0) {
                                el.remove();
                            }
                        }
                    });

                    // 3. Eager-load images inside article, main, figure only
                    var scope = document.querySelector('article') || document.querySelector('main') || document.body;
                    scope.querySelectorAll('img').forEach(function(img) {
                        var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original');
                        if (dataSrc && (!img.src || img.src.indexOf('data:') === 0)) {
                            img.src = dataSrc;
                        }
                        img.removeAttribute('loading');
                    });

                    // 4. Single-pass TreeWalker ROT-1 unscrambler with data-wv-decoded marker
                    try {
                        var articleRoot = document.querySelector('article') || document.querySelector('main') || document.body;
                        if (!articleRoot.getAttribute('data-wv-decoded')) {
                            var walker = document.createTreeWalker(articleRoot, NodeFilter.SHOW_TEXT, null, false);
                            var textNode;
                            while (textNode = walker.nextNode()) {
                                var val = textNode.nodeValue;
                                if (val && val.length > 30 && /^[a-zA-Z\s.,'"!?\-]+$/.test(val)) {
                                    var sample = val.trim().split(/\s+/).slice(0, 6);
                                    var rotHits = 0;
                                    for (var i = 0; i < sample.length; i++) {
                                        var w = sample[i].toLowerCase();
                                        if (w === "uif" || w === "boe" || w === "up" || w === "jo" || w === "pg" || w === "xbt") {
                                            rotHits++;
                                        }
                                    }
                                    if (rotHits >= 2) {
                                        var decoded = '';
                                        for (var j = 0; j < val.length; j++) {
                                            var code = val.charCodeAt(j);
                                            if (code >= 66 && code <= 90) decoded += String.fromCharCode(code - 1);
                                            else if (code === 65) decoded += 'Z';
                                            else if (code >= 98 && code <= 122) decoded += String.fromCharCode(code - 1);
                                            else if (code === 97) decoded += 'z';
                                            else decoded += val.charAt(j);
                                        }
                                        textNode.nodeValue = decoded;
                                    }
                                }
                            }
                            articleRoot.setAttribute('data-wv-decoded', 'true');
                        }
                    } catch(re) {}

                    // 5. Strip reversed-word paragraphs in DOM
                    var revStopRegex = /\b(eht|dna|morf|sih|htiw|yeht|erew|taht|tnebmucni|citarcomed|nacilbuper)\b/gi;
                    document.querySelectorAll('p, li').forEach(function(p) {
                        var txt = (p.innerText || '').trim();
                        var matches = txt.match(revStopRegex);
                        if (matches && matches.length >= 3) {
                            p.remove();
                        }
                    });

                    // 6. Hydrate JSON-LD / Fusion paragraphs into #webvaultr-hydrated-body ONLY if story container has < 4 paragraphs
                    var storyContainer = document.querySelector('article') || document.querySelector('main') || document.querySelector('[role="main"]');
                    var existingParaCount = storyContainer ? storyContainer.querySelectorAll('p').length : document.querySelectorAll('p').length;
                    if (existingParaCount < 4 && !document.getElementById('webvaultr-hydrated-body')) {
                        var recoveredParas = [];

                        // 6a. JSON-LD articleBody
                        document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s) {
                            try {
                                var data = JSON.parse(s.textContent || '{}');
                                var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                                items.forEach(function(item) {
                                    if (item && typeof item.articleBody === 'string' && item.articleBody.length > 200) {
                                        var split = item.articleBody.split(/\n+/).map(function(p) { return p.trim(); }).filter(function(p) { return p.length > 0; });
                                        if (split.length > recoveredParas.length) recoveredParas = split;
                                    }
                                });
                            } catch(e) {}
                        });

                        // 6b. Fusion globalContent
                        if (recoveredParas.length === 0 && window.Fusion && window.Fusion.globalContent) {
                            try {
                                var elements = window.Fusion.globalContent.content_elements;
                                if (Array.isArray(elements)) {
                                    elements.forEach(function(el) {
                                        var t = (el && (el.content || el.text)) || '';
                                        if (typeof t === 'string' && t.trim().length > 0) {
                                            recoveredParas.push(t.replace(/<[^>]*>/g, '').trim());
                                        }
                                    });
                                }
                            } catch(e) {}
                        }

                        if (recoveredParas.length >= 4) {
                            var hydratedDiv = document.createElement('div');
                            hydratedDiv.id = 'webvaultr-hydrated-body';
                            hydratedDiv.style.cssText = 'padding: 16px 0; font-family: Georgia, -apple-system, serif; font-size: 18px; line-height: 1.65; color: #111;';
                            recoveredParas.forEach(function(pText) {
                                var pEl = document.createElement('p');
                                pEl.style.cssText = 'margin-bottom: 16px;';
                                pEl.textContent = pText;
                                hydratedDiv.appendChild(pEl);
                            });
                            var targetMount = storyContainer || document.body;
                            targetMount.appendChild(hydratedDiv);
                        }
                    }
                } catch (e) {}

                return Math.max(
                    document.body ? document.body.scrollHeight : 0,
                    document.documentElement ? document.documentElement.scrollHeight : 0
                );
            })();
        """.trimIndent()

        webView.evaluateJavascript(unlockScript) {
            DiagnosticLogHelper.log(TAG, "DOM sanitization and unlock script finished")
            onComplete()
        }
    }
}

