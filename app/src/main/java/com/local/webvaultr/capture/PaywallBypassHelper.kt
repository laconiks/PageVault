package com.local.webvaultr.capture

import android.webkit.WebView
import com.local.webvaultr.util.DiagnosticLogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                if (body.length > 500) {
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
     * even when paragraph 1 is plain English and paragraph 2 switches to ROT-1 mid-sentence.
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

        // Split by sentence/line boundaries so mixed paragraphs (like NYDN Paragraph 2) are decoded accurately
        return input.lines().joinToString("\n") { line ->
            val words = Regex("[a-zA-Z]+").findAll(line).map { it.value.lowercase() }.toList()
            val rotCount = words.count { it in rot1Markers }
            if (rotCount == 0) {
                line
            } else {
                val plainCount = words.count { it in plainMarkers }
                if (plainCount == 0 || rotCount > plainCount * 2) {
                    // Entire line is ROT-1
                    shiftRot1(line)
                } else {
                    // Mixed line (e.g. "Robinson, nearly blind from diabetes, was standing on uif gjfme. Qmbzfst...")
                    // Find the first ROT-1 marker word and decode from there to the end of the line
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
     * Configures WebView settings prior to loadUrl().
     * Uses standard Chrome Mobile User-Agent with Google Referer header to bypass bot blocks.
     * @param spoofCrawler If true, sets standard headers with Google Referer.
     * @param disableJs If true, disables JavaScript to stop client-side paywall truncation scripts.
     */
    fun configureWebViewForCapture(
        webView: WebView,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false
    ) {
        DiagnosticLogHelper.log(TAG, "Configuring WebView settings (jsEnabled=${!disableJs}, spoofCrawler=$spoofCrawler, UA=ChromeMobile)")
        webView.settings.apply {
            javaScriptEnabled = !disableJs
            domStorageEnabled = true
            userAgentString = CHROME_MOBILE_USER_AGENT
        }
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /**
     * Injects surgical DOM unmasking, text-aware gate removal, sticky bar removal,
     * and Next.js (__NEXT_DATA__) + JSON-LD articleBody recovery script before MHTML/PDF capture.
     */
    fun unlockAndSanitizeDom(webView: WebView, onComplete: () -> Unit) {
        if (!webView.settings.javaScriptEnabled) {
            onComplete()
            return
        }

        val unlockScript = """
            (function() {
                // 1. Remove ONLY true subscriber/piano/regwall modal overlays (never touch nav or article containers)
                var paywallSelectors = [
                    '[class*="paywall-"]', '[id*="paywall"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                    '[class*="piano-"]', '[id*="piano-"]', '[class*="regwall"]', '[data-qa*="paywall"]',
                    '[class*="subscription-wall"]', '[class*="meter-wall"]', '[id*="gateway-content"]',
                    // Substack modals & subscription popups
                    '[class*="substack-modal"]', 'div.modal-wrapper', 'div.modal-table',
                    '[class*="subscribe-modal"]', '[class*="paywall-banner"]',
                    '[class*="post-preview-overlay"]', '[class*="paywall-boundary"]',
                    '[class*="modal-overlay"]', '[class*="reader-popover"]',
                    '[class*="subscriber-only-banner"]', '[class*="subscribe-widget"]',
                    '[class*="pencraft"][role="dialog"]', 'div[class*="gate-container"]',
                    // NBC News floating video players and modals
                    '[class*="floating-video"]', '[class*="sticky-player"]', '[class*="mini-player"]'
                ];
                paywallSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                            el.remove();
                        }
                    });
                });

                // 1b. Politico & general mid-article ad-slot, interrupt, and advertisement cleanup
                var adSelectors = [
                    '.story-interrupt', '.ad-slot', '[class*="story-interrupt"]', '[class*="ad-slot"]',
                    '[id*="story-interrupt"]', '[id*="ad-slot"]', '[data-ad-slot]', '[class*="story-ad"]',
                    '[class*="ad-container"]', '[class*="ad_container"]', '[class*="ad-wrapper"]',
                    '[data-ad-type]', '[class*="ad--slot"]', '[class*="advertisement-slot"]',
                    'aside.ad', 'div.ad', 'div[id*="google_ads"]', 'div[id*="dfp-ad"]'
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

                // 3b. Auto-dismiss or click Substack expand/dismiss buttons if present
                document.querySelectorAll('button, a, div[role="button"]').forEach(function(btn) {
                    var t = (btn.innerText || '').trim().toLowerCase();
                    if (t === 'expand post' || t === 'expand' || t === 'continue reading' || t === 'no thanks' || t === 'read first') {
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

                // 6. Boston Globe / WaPo Arc XP Fusion, NBC News (__NEXT_DATA__) & JSON-LD Hydration
                try {
                    var recoveredParas = [];

                    // 6a. Arc XP Fusion (Boston Globe, Washington Post, etc.)
                    try {
                        var fusionObj = null;
                        if (window.Fusion && window.Fusion.globalContent) {
                            fusionObj = window.Fusion.globalContent;
                        } else {
                            var fusionScript = document.getElementById('fusion-metadata') || document.querySelector('script[id*="fusion"]');
                            if (fusionScript && fusionScript.textContent) {
                                var fMatch = fusionScript.textContent.match(/Fusion\.globalContent\s*=\s*(\{.*?\});/);
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

                    var articleEl = document.querySelector('article') || document.querySelector('main') || document.querySelector('[role="main"]') || document.body;
                    var visibleLen = (articleEl.innerText || '').trim().length;
                    var recoveredText = recoveredParas.join('\n\n').trim();

                    var candidateParas = [];
                    if (recoveredText.length >= visibleLen + 300 && recoveredParas.length > 0) {
                        candidateParas = recoveredParas;
                    } else if (longestLdBody.length >= visibleLen + 300) {
                        candidateParas = longestLdBody.split(/\n+/).map(function(s) { return s.trim(); }).filter(function(s) { return s.length > 0; });
                    }

                    if (candidateParas.length > 0 && !document.getElementById('webvaultr-hydrated-content') && !document.getElementById('pagevault-hydrated-content')) {
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

                // 7. NY Daily News / Tribune ROT-1 In-DOM Unscrambler
                try {
                    var rot1Markers = ['uif', 'boe', 'xbt', 'gspn', 'pwfs', 'up', 'jo', 'gps', 'xjui', 'tbje'];
                    function shiftRot1Char(c) {
                        var code = c.charCodeAt(0);
                        if (code >= 98 && code <= 122) return String.fromCharCode(code - 1);
                        if (code === 97) return 'z';
                        if (code >= 66 && code <= 90) return String.fromCharCode(code - 1);
                        if (code === 65) return 'Z';
                        return c;
                    }
                    function shiftRot1Str(str) {
                        var res = '';
                        for (var i = 0; i < str.length; i++) res += shiftRot1Char(str[i]);
                        return res;
                    }
                    function decodeRot1IfMatched(text) {
                        if (!text || text.length < 20) return text;
                        var words = text.toLowerCase().match(/[a-z]+/g) || [];
                        var rotMatches = 0;
                        for (var i = 0; i < words.length; i++) {
                            if (rot1Markers.indexOf(words[i]) !== -1) rotMatches++;
                        }
                        if (rotMatches >= 2) {
                            var plainMatches = 0;
                            var plains = ['the', 'and', 'was', 'from', 'with', 'said', 'that', 'this'];
                            for (var j = 0; j < words.length; j++) {
                                if (plains.indexOf(words[j]) !== -1) plainMatches++;
                            }
                            if (plainMatches === 0 || rotMatches > plainMatches * 2) {
                                return shiftRot1Str(text);
                            } else {
                                var m = text.match(/\b(uif|boe|xbt|gspn|pwfs|Qmbzfst|Spcjotpo|Npshbo|boopvodfnfou)\b/i);
                                if (m && m.index !== undefined) {
                                    return text.substring(0, m.index) + shiftRot1Str(text.substring(m.index));
                                }
                            }
                        }
                        return text;
                    }
                    var textNodes = document.querySelectorAll('p, div.article-body p, article p, [class*="content"] p');
                    textNodes.forEach(function(pNode) {
                        if (pNode.children.length === 0) {
                            var orig = pNode.textContent || '';
                            var decoded = decodeRot1IfMatched(orig);
                            if (decoded !== orig) pNode.textContent = decoded;
                        } else {
                            pNode.childNodes.forEach(function(child) {
                                if (child.nodeType === 3) {
                                    var orig = child.nodeValue || '';
                                    var decoded = decodeRot1IfMatched(orig);
                                    if (decoded !== orig) child.nodeValue = decoded;
                                }
                            });
                        }
                    });
                } catch(rote) {}

                return Math.max(
                    document.body ? document.body.scrollHeight : 0,
                    document.documentElement ? document.documentElement.scrollHeight : 0
                );
            })();
        """.trimIndent()

        webView.evaluateJavascript(unlockScript) {
            DiagnosticLogHelper.log(TAG, "DOM sanitization and unlock script finished")
            // Small delay (150ms) to allow DOM layout reflow before saveWebArchive / PDF canvas draw
            webView.postDelayed({ onComplete() }, 150L)
        }
    }
}
