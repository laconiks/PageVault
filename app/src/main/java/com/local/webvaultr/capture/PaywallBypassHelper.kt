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
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (body.length > 400) {
                    DiagnosticLogHelper.log(TAG, "Successfully fetched clean reader text (${body.length} chars)")
                    body
                } else {
                    DiagnosticLogHelper.log(TAG, "Jina reader text too short (${body.length} chars)")
                    null
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
     * so both the MHTML archive and full-height screenshot display the complete formatted article.
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
                    var html = '<div style="max-width: 720px; margin: 0 auto; padding: 24px 16px; font-family: -apple-system, BlinkMacSystemFont, Roboto, Georgia, serif; line-height: 1.65; color: #111; background: #fff;">';
                    if (t) html += '<h1 style="font-size: 26px; line-height: 1.25; margin-bottom: 20px; font-weight: 700;">' + t + '</h1>';
                    paras.forEach(function(p) {
                        html += '<p style="font-size: 17px; margin-bottom: 16px;">' + p + '</p>';
                    });
                    html += '</div>';
                    document.body.innerHTML = html;
                    document.body.style.background = '#ffffff';
                    document.documentElement.style.background = '#ffffff';
                    window.scrollTo(0, 0);
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Freezes CSS animations, eager-loads lazy images, removes blur filters,
     * materializes CSSOM stylesheet rules, and stabilizes the DOM before saving MHTML.
     */
    fun freezeDomBeforeMhtmlSave(webView: WebView, onComplete: () -> Unit) {
        val js = """
            (function() {
                try {
                    // 1. Remove animation & blur filters
                    var s = document.createElement('style');
                    s.textContent = '* { animation: none !important; transition: none !important; } [style*="blur"], .blur, [class*="blur"] { filter: none !important; -webkit-filter: none !important; }';
                    (document.head || document.documentElement).appendChild(s);

                    // 2. Unscramble ROT-1 text nodes in-DOM if detected
                    try {
                        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        while(node = walker.nextNode()) {
                            var val = node.nodeValue;
                            if (val && val.length > 25 && /^[a-zA-Z\s.,'"!?]+$/.test(val)) {
                                var sample = val.trim().split(/\s+/).slice(0, 5);
                                var rotHits = 0;
                                for (var si = 0; si < sample.length; si++) {
                                    var w = sample[si].toLowerCase();
                                    if (w === "uif" || w === "boe" || w === "up" || w === "jo" || w === "pg") rotHits++;
                                }
                                if (rotHits >= 2) {
                                    var decoded = '';
                                    for (var ci = 0; ci < val.length; ci++) {
                                        var code = val.charCodeAt(ci);
                                        if (code >= 66 && code <= 90) decoded += String.fromCharCode(code - 1);
                                        else if (code === 65) decoded += 'Z';
                                        else if (code >= 98 && code <= 122) decoded += String.fromCharCode(code - 1);
                                        else if (code === 97) decoded += 'z';
                                        else decoded += val.charAt(ci);
                                    }
                                    node.nodeValue = decoded;
                                }
                            }
                        }
                    } catch(re) {}

                    // 3. Eager-load lazy images
                    document.querySelectorAll('img').forEach(function(img) {
                        var dSrc = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') || img.getAttribute('data-srcset');
                        if (dSrc && !img.src) img.src = dSrc;
                        var dSrcset = img.getAttribute('data-srcset');
                        if (dSrcset && !img.srcset) img.srcset = dSrcset;
                        img.removeAttribute('loading');
                    });
                    document.querySelectorAll('noscript').forEach(function(ns) {
                        var text = ns.textContent || '';
                        if (text.indexOf('<img') !== -1) {
                            var div = document.createElement('div');
                            div.innerHTML = text;
                            var innerImg = div.querySelector('img');
                            if (innerImg && innerImg.src && ns.parentNode) {
                                ns.parentNode.insertBefore(innerImg, ns);
                            }
                        }
                    });

                    // 4. Materialize CSSOM dynamic style rules into style tags
                    try {
                        for (var i = 0; i < document.styleSheets.length; i++) {
                            var sheet = document.styleSheets[i];
                            if (!sheet.href && sheet.cssRules && sheet.cssRules.length > 0) {
                                var styleEl = document.createElement('style');
                                var cssText = '';
                                for (var j = 0; j < sheet.cssRules.length; j++) {
                                    cssText += sheet.cssRules[j].cssText + '\n';
                                }
                                styleEl.textContent = cssText;
                                document.head.appendChild(styleEl);
                            }
                        }
                    } catch(csse) {}

                    // 5. Unlock scroll & height constraints
                    document.documentElement.style.overflow = 'visible';
                    document.documentElement.style.maxHeight = 'none';
                    document.body.style.overflow = 'visible';
                    document.body.style.maxHeight = 'none';
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) {
            webView.postDelayed({ onComplete() }, 100L)
        }
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
     * Injects surgical DOM unmasking, text-aware gate removal, sticky bar removal,
     * horizontal width constraints (fixing CNN clipping), auto-clicking AP News/Substack buttons,
     * and Next.js (__NEXT_DATA__) + JSON-LD articleBody recovery script before MHTML/PDF capture.
     */
    fun unlockAndSanitizeDom(webView: WebView, onComplete: () -> Unit) {
        if (!webView.settings.javaScriptEnabled) {
            onComplete()
            return
        }

        val unlockScript = """
            (function() {
                // 0. Constrain horizontal width so CNN and wide layouts never clip in PDF / full-page screenshots
                try {
                    var widthStyle = document.createElement('style');
                    widthStyle.textContent = 'html, body { max-width: 100vw !important; overflow-x: hidden !important; }';
                    (document.head || document.documentElement).appendChild(widthStyle);
                } catch(we) {}

                // 1. Remove ONLY true subscriber/piano/regwall modal overlays, cookie banners & floating players
                var paywallSelectors = [
                    '[class*="paywall-"]', '[id*="paywall"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                    '[class*="piano-"]', '[id*="piano-"]', '[class*="regwall"]', '[data-qa*="paywall"]',
                    '[class*="subscription-wall"]', '[class*="meter-wall"]', '[id*="gateway-content"]',
                    // Offer gates & subscription overlays
                    '[class*="offer-gate"]', '[class*="paywall-offer"]', '[class*="intro-offer"]',
                    '[id*="offer-gate"]', '[class*="subscription-offer"]', '[class*="offer-container"]',
                    // Cookie banners & consent overlays
                    '[class*="cookie"]', '[id*="cookie"]', '[class*="consent"]', '[id*="consent"]',
                    '[class*="onetrust"]', '[id*="onetrust"]', '[class*="usercentrics"]', '[class*="qc-cmp"]',
                    '[id*="sp_message"]', '[class*="cmp-modal"]', '#onetrust-consent-sdk',
                    // Substack modals & subscription popups
                    '[class*="substack-modal"]', 'div.modal-wrapper', 'div.modal-table',
                    '[class*="subscribe-modal"]', '[class*="paywall-banner"]',
                    '[class*="post-preview-overlay"]', '[class*="paywall-boundary"]',
                    '[class*="modal-overlay"]', '[class*="reader-popover"]',
                    '[class*="subscriber-only-banner"]', '[class*="subscribe-widget"]',
                    '[class*="pencraft"][role="dialog"]', 'div[class*="gate-container"]',
                    // Connatix and floating video players
                    '[class*="connatix"]', '[id*="connatix"]', 'div[class*="cnx-"]',
                    '[class*="vdo_inline"]', '[class*="jwplayer"]', '[class*="floating-player"]',
                    '[class*="persistent-player"]', '[class*="floating-video"]', '[class*="sticky-player"]', '[class*="mini-player"]'
                ];
                paywallSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                            el.remove();
                        }
                    });
                });

                // 1b. Politico & general mid-article ad-slot, interrupt, spinner, and advertisement cleanup
                var adSelectors = [
                    '.story-interrupt', '.ad-slot', '[class*="story-interrupt"]', '[class*="ad-slot"]',
                    '[id*="story-interrupt"]', '[id*="ad-slot"]', '[data-ad-slot]', '[class*="story-ad"]',
                    '[class*="ad-container"]', '[class*="ad_container"]', '[class*="ad-wrapper"]',
                    '[data-ad-type]', '[class*="ad--slot"]', '[class*="advertisement-slot"]',
                    'aside.ad', 'div.ad', 'div[id*="google_ads"]', 'div[id*="dfp-ad"]',
                    '[class*="spinner"]', '[class*="loader"]', '[id*="spinner"]', '[id*="loader"]',
                    '[class*="interstitial"]', '[id*="interstitial"]'
                ];
                adSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                            el.remove();
                        }
                    });
                });
                // Remove elements containing solely "Advertisement" or "Loading" / "Loading..."
                document.querySelectorAll('div, p, span, aside, section').forEach(function(el) {
                    if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                    var t = (el.innerText || '').trim();
                    if (t === 'Advertisement' || t === 'ADVERTISEMENT' || t === 'Loading' || t === 'Loading...') {
                        if (el.children.length <= 2 && el.querySelectorAll('p').length <= 1) {
                            el.remove();
                        }
                    }
                });

                // 2. Ensure off-canvas mobile drawers (like NBC News blue menu) and state dropdowns stay hidden
                var hiddenDrawers = [
                    '[aria-hidden="true"]', '[class*="nav-drawer"]', '[class*="mobile-menu"]',
                    '[class*="menu-drawer"]', '[class*="offcanvas"]', 'nav [class*="drawer"]',
                    'header ul', 'nav ul', '[class*="state-list"]', '[class*="navigation__list"]',
                    '[class*="quick-links"]', '[class*="global-nav__menu"]', '[class*="hamburger-menu"]',
                    '[class*="msg-drawer"]', '[class*="drawer-container"]', 'header [role="dialog"]',
                    '[class*="slide-out"]', '[class*="nav-panel"]'
                ];
                hiddenDrawers.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        el.style.setProperty('display', 'none', 'important');
                    });
                });

                // 3. Text-Aware Gate Removal (The Verge, WaPo, Substack, etc.)
                var gatePhrases = [
                    "continue reading",
                    "with a verge subscription",
                    "two ways to read this article",
                    "terms of use/your privacy rights",
                    "go beyond the headline",
                    "subscribe to the verge",
                    "read in the substack app",
                    "expand post",
                    "sign up to keep reading",
                    "subscribe to keep reading",
                    "get the substack app",
                    "read in app",
                    "free preview",
                    "this post is for paid subscribers",
                    "upgrade to paid",
                    "already a subscriber? sign in",
                    "let's be inbox friends",
                    "open in app",
                    "open in the substack app",
                    "continue in app"
                ];
                var potentialGates = document.querySelectorAll('div, section, aside, form, dialog, [role="dialog"], [role="alertdialog"]');
                potentialGates.forEach(function(el) {
                    if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                    if (el.closest('article') && (el.innerText || '').length > 1200) return;
                    var text = (el.innerText || '').toLowerCase();
                    if (text.length > 0 && text.length < 1500) {
                        for (var i = 0; i < gatePhrases.length; i++) {
                            if (text.indexOf(gatePhrases[i]) !== -1) {
                                el.remove();
                                break;
                            }
                        }
                    }
                });

                // 3b. Auto-dismiss or click AP News / Substack expand/dismiss buttons
                document.querySelectorAll('button, a, div[role="button"]').forEach(function(btn) {
                    var t = (btn.innerText || '').trim().toLowerCase();
                    if (t === 'expand post' || t === 'expand' || t === 'continue reading' || t === 'read more' || t === 'view more' || t === 'no thanks' || t === 'read first' || t === 'close x' || t === 'close') {
                        try { btn.click(); } catch(e) {}
                    }
                });

                // 4. Bottom-Anchored Sticky Bar & Backdrop Dimmer Removal
                document.querySelectorAll('*').forEach(function(el) {
                    if (el.tagName === 'BODY' || el.tagName === 'HTML' || el.tagName === 'MAIN' || el.tagName === 'ARTICLE') return;
                    var style = window.getComputedStyle(el);
                    var pos = style.position;
                    if (pos === 'fixed' || pos === 'sticky') {
                        var rect = el.getBoundingClientRect();
                        if (rect.top > window.innerHeight * 0.40 && rect.height < window.innerHeight * 0.85) {
                            el.remove();
                            return;
                        }
                        var bg = style.backgroundColor;
                        if (bg && (bg.indexOf('rgba') !== -1 || bg.indexOf('hsla') !== -1)) {
                            if (rect.width >= window.innerWidth * 0.8 && rect.height >= window.innerHeight * 0.8) {
                                el.remove();
                                return;
                            }
                        }
                    } else {
                        var bg = style.backgroundColor;
                        if (bg && (bg.indexOf('rgba') !== -1 || bg.indexOf('hsla') !== -1)) {
                            var z = parseInt(style.zIndex, 10);
                            if (z > 100) {
                                var rect = el.getBoundingClientRect();
                                if (rect.width >= window.innerWidth * 0.8 && rect.height >= window.innerHeight * 0.8) {
                                    el.remove();
                                    return;
                                }
                            }
                        }
                    }
                });

                // 5. Unlock scroll & truncation ONLY on document and article containers (NEVER on '*')
                var contentContainers = document.querySelectorAll(
                    'html, body, main, article, [role="main"], [class*="article-body"], [class*="story-body"], [class*="article-content"], [class*="paywall-content"], [class*="post-content"], [class*="substack-post"], [class*="post-body"], [class*="body-markup"], [class*="single-post"], [class*="article-body__content"]'
                );
                contentContainers.forEach(function(el) {
                    el.style.setProperty('overflow', 'visible', 'important');
                    el.style.setProperty('overflow-y', 'visible', 'important');
                    el.style.setProperty('max-height', 'none', 'important');
                    el.style.setProperty('height', 'auto', 'important');
                    el.style.setProperty('-webkit-mask-image', 'none', 'important');
                    el.style.setProperty('mask-image', 'none', 'important');
                    el.style.setProperty('position', 'static', 'important');
                });

                // 6. Arc XP Fusion, Next.js (__NEXT_DATA__) & JSON-LD Structured DOM Hydration
                // Always hydrate whenever recovered structured story text exceeds non-modal paragraph text by 250+ chars
                try {
                    var articleEl = document.querySelector('article') || document.querySelector('main') || document.querySelector('[role="main"]') || document.body;
                    var visibleLen = (articleEl ? (articleEl.innerText || '') : '').trim().length;

                    var recoveredParas = [];

                    // 6a. Arc XP Fusion (e.g. Boston Globe, Washington Post)
                    try {
                        var fusionObj = null;
                        if (window.Fusion && window.Fusion.globalContent) {
                            fusionObj = window.Fusion.globalContent;
                        } else {
                            var fusionScript = document.getElementById('fusion-metadata') || document.querySelector('script[id*="fusion"]');
                            if (fusionScript && fusionScript.textContent) {
                                var fMatch = fusionScript.textContent.match(/Fusion\.globalContent\s*=\\s*(\{.*?\}\);/);
                                if (fMatch) fusionObj = JSON.parse(fMatch[1]);
                            }
                        }
                        if (fusionObj) {
                            function extractFusion(obj) {
                                if (!obj || typeof obj !== 'object') return;
                                if (Array.isArray(obj.content_elements)) {
                                    obj.content_elements.forEach(function(item) {
                                        if (!item) return;
                                        var raw = item.content || item.text || '';
                                        if (typeof raw === 'string' && raw.trim().length > 0) {
                                            var clean = raw.replace(/<[^>]*>/g, '').trim();
                                            if (clean.length > 0) recoveredParas.push(clean);
                                        }
                                    });
                                }
                                for (var fk in obj) {
                                    if (obj.hasOwnProperty(fk) && typeof obj[fk] === 'object' && fk !== 'content_elements') {
                                        extractFusion(obj[fk]);
                                    }
                                }
                            }
                            extractFusion(fusionObj);
                        }
                    } catch(fe) {}

                    // 6b. Next.js (__NEXT_DATA__)
                    var nextDataEl = document.getElementById('__NEXT_DATA__');
                    if (nextDataEl && nextDataEl.textContent) {
                        try {
                            var nextData = JSON.parse(nextDataEl.textContent);
                            function findContentElements(obj) {
                                if (!obj || typeof obj !== 'object') return;
                                if (Array.isArray(obj)) {
                                    for (var i = 0; i < obj.length; i++) findContentElements(obj[i]);
                                } else {
                                    if (Array.isArray(obj.content_elements)) {
                                        obj.content_elements.forEach(function(item) {
                                            var raw = (item && (item.content || item.text)) || '';
                                            if (typeof raw === 'string' && raw.trim().length > 0) {
                                                var clean = raw.replace(/<[^>]*>/g, '').trim();
                                                if (clean.length > 0) recoveredParas.push(clean);
                                            }
                                        });
                                    }
                                    if (Array.isArray(obj.body)) {
                                        obj.body.forEach(function(item) {
                                            var raw = (item && (item.content || item.text || (item.children && item.children[0] && item.children[0].text))) || '';
                                            if (typeof raw === 'string' && raw.trim().length > 0) {
                                                var clean = raw.replace(/<[^>]*>/g, '').trim();
                                                if (clean.length > 0) recoveredParas.push(clean);
                                            }
                                        });
                                    }
                                    for (var k in obj) {
                                        if (obj.hasOwnProperty(k) && typeof obj[k] === 'object') {
                                            findContentElements(obj[k]);
                                        }
                                    }
                                }
                            }
                            findContentElements(nextData);
                        } catch (ne) {}
                    }

                    // 6c. JSON-LD articleBody
                    var longestLdBody = '';
                    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    scripts.forEach(function(s) {
                        try {
                            var data = JSON.parse(s.textContent || '{}');
                            var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                            items.forEach(function(item) {
                                if (item && typeof item.articleBody === 'string' && item.articleBody.length > longestLdBody.length) {
                                    longestLdBody = item.articleBody;
                                }
                            });
                        } catch (je) {}
                    });

                    var recoveredText = recoveredParas.join('\n\n').trim();
                    var candidateParas = [];
                    if (recoveredText.length >= visibleLen + 250 && recoveredParas.length > 0) {
                        candidateParas = recoveredParas;
                    } else if (longestLdBody.length >= visibleLen + 250) {
                        candidateParas = longestLdBody.split(/\n+/).map(function(s) { return s.trim(); }).filter(function(s) { return s.length > 0; });
                    }

                    if (candidateParas.length > 0 && !document.getElementById('webvaultr-hydrated-content')) {
                        var container = document.createElement('div');
                        container.id = 'webvaultr-hydrated-content';
                        container.style.cssText = 'padding: 16px 0; font-family: Georgia, -apple-system, sans-serif; font-size: 18px; line-height: 1.65; color: #111;';
                        candidateParas.forEach(function(para) {
                            var p = document.createElement('p');
                            p.style.cssText = 'margin-bottom: 14px;';
                            p.textContent = para;
                            container.appendChild(p);
                        });
                        articleEl.appendChild(container);
                    }
                } catch (e) {}
                            articleEl.appendChild(container);
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
            webView.postDelayed({ onComplete() }, 150L)
        }
    }
}
