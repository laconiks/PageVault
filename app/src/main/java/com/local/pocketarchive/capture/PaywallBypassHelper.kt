package com.local.pocketarchive.capture

import android.webkit.WebView
import com.local.pocketarchive.util.DiagnosticLogHelper
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

                // 6. Next.js (__NEXT_DATA__) + JSON-LD Hydrator
                try {
                    var longestBody = '';
                    var nextJsParas = [];

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
                                            if (item && typeof item.content === 'string' && item.content.trim().length > 0) {
                                                var clean = item.content.replace(/<[^>]*>/g, '').trim();
                                                if (clean.length > 0) nextJsParas.push(clean);
                                            } else if (item && typeof item.text === 'string' && item.text.trim().length > 0) {
                                                var clean = item.text.replace(/<[^>]*>/g, '').trim();
                                                if (clean.length > 0) nextJsParas.push(clean);
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

                    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    scripts.forEach(function(s) {
                        try {
                            var data = JSON.parse(s.textContent || '{}');
                            var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                            items.forEach(function(item) {
                                if (item && typeof item.articleBody === 'string' && item.articleBody.length > longestBody.length) {
                                    longestBody = item.articleBody;
                                }
                            });
                        } catch (je) {}
                    });

                    var articleEl = document.querySelector('article') || document.querySelector('main') || document.body;
                    var currentVisibleLen = (articleEl.innerText || '').trim().length;

                    if (nextJsParas.length > 3 && currentVisibleLen < nextJsParas.join(' ').length * 0.65 && !document.getElementById('webvaultr-hydrated-content') && !document.getElementById('pagevault-hydrated-content')) {
                        var container = document.createElement('div');
                        container.id = 'webvaultr-hydrated-content';
                        container.style.cssText = 'padding: 16px 0; font-family: sans-serif; font-size: 18px; line-height: 1.65; color: #111;';
                        nextJsParas.forEach(function(para) {
                            var p = document.createElement('p');
                            p.style.cssText = 'margin-bottom: 14px;';
                            p.textContent = para;
                            container.appendChild(p);
                        });
                        articleEl.appendChild(container);
                    } else if (longestBody.length > 700 && currentVisibleLen < longestBody.length * 0.65 && !document.getElementById('webvaultr-hydrated-content') && !document.getElementById('pagevault-hydrated-content')) {
                        var container = document.createElement('div');
                        container.id = 'webvaultr-hydrated-content';
                        container.style.cssText = 'padding: 16px 0; font-family: Georgia, serif; font-size: 18px; line-height: 1.65; color: #111;';
                        longestBody.split(/\n+/).forEach(function(para) {
                            if (para.trim().length > 0) {
                                var p = document.createElement('p');
                                p.style.cssText = 'margin-bottom: 14px;';
                                p.textContent = para.trim();
                                container.appendChild(p);
                            }
                        });
                        articleEl.appendChild(container);
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
            // Small delay (150ms) to allow DOM layout reflow before saveWebArchive / PDF canvas draw
            webView.postDelayed({ onComplete() }, 150L)
        }
    }
}
