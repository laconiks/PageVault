package com.local.pocketarchive.capture

import android.webkit.WebView
import com.local.pocketarchive.util.DiagnosticLogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object PaywallBypassHelper {

    private const val TAG = "PaywallBypass"

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
        "complete captcha"
    )

    /**
     * Determines if extracted DOM text represents a hard paywall or CAPTCHA blocking screen.
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
            DiagnosticLogHelper.log(TAG, "Fetching unblocked reader fallback via r.jina.ai for $targetUrl")
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
    }

    /**
     * Injects surgical DOM unmasking and JSON-LD articleBody recovery script before MHTML/PDF capture.
     * Removes paywalls and unlocks article truncation WITHOUT opening hidden mobile navigation drawers.
     */
    fun unlockAndSanitizeDom(webView: WebView, onComplete: () -> Unit) {
        if (!webView.settings.javaScriptEnabled) {
            onComplete()
            return
        }

        val unlockScript = """
            (function() {
                // 1. Remove ONLY true paywall/piano/regwall modal overlays (never touch nav or article containers)
                var paywallSelectors = [
                    '[class*="paywall-"]', '[id*="paywall"]', '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                    '[class*="piano-"]', '[id*="piano-"]', '[class*="regwall"]', '[data-qa*="paywall"]',
                    '[class*="subscription-wall"]', '[class*="meter-wall"]', '[id*="gateway-content"]'
                ];
                paywallSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        if (el.tagName !== 'BODY' && el.tagName !== 'HTML' && el.tagName !== 'MAIN' && el.tagName !== 'ARTICLE') {
                            el.remove();
                        }
                    });
                });

                // 2. Ensure off-canvas mobile drawers (like NBC News blue menu) stay hidden
                var hiddenDrawers = [
                    '[aria-hidden="true"]', '[class*="nav-drawer"]', '[class*="mobile-menu"]',
                    '[class*="menu-drawer"]', '[class*="offcanvas"]', 'nav [class*="drawer"]'
                ];
                hiddenDrawers.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        el.style.setProperty('display', 'none', 'important');
                    });
                });

                // 3. Unlock scroll & truncation ONLY on document and article containers (NEVER on '*')
                var contentContainers = document.querySelectorAll(
                    'html, body, main, article, [role="main"], [class*="article-body"], [class*="story-body"], [class*="article-content"], [class*="paywall-content"]'
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

                // 4. Hydrate truncated article paragraphs from JSON-LD articleBody if article text is gated
                try {
                    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    var longestBody = '';
                    scripts.forEach(function(s) {
                        var data = JSON.parse(s.textContent || '{}');
                        var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                        items.forEach(function(item) {
                            if (item && typeof item.articleBody === 'string' && item.articleBody.length > longestBody.length) {
                                longestBody = item.articleBody;
                            }
                        });
                    });
                    var articleEl = document.querySelector('article') || document.querySelector('main') || document.body;
                    var currentVisibleLen = (articleEl.innerText || '').trim().length;
                    if (longestBody.length > 700 && currentVisibleLen < longestBody.length * 0.65 && !document.getElementById('pagevault-jsonld-hydrated')) {
                        var container = document.createElement('div');
                        container.id = 'pagevault-jsonld-hydrated';
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
