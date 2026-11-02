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
        if (clean.length < 500) return true
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
     * Injects DOM unmasking and JSON-LD articleBody recovery script before MHTML/PDF capture.
     */
    fun unlockAndSanitizeDom(webView: WebView, onComplete: () -> Unit) {
        if (!webView.settings.javaScriptEnabled) {
            onComplete()
            return
        }

        val unlockScript = """
            (function() {
                try {
                    // 1. Remove known paywall modals, banners, and scroll-lock backdrops
                    const paywallSelectors = [
                        '[class*="paywall"]', '[id*="paywall"]',
                        '[class*="gate"]', '[id*="gate"]',
                        '[class*="barrier"]', '[class*="piano"]',
                        '[class*="tp-modal"]', '[class*="tp-backdrop"]',
                        '[class*="subscription-wall"]', '[class*="reg-wall"]'
                    ];
                    document.querySelectorAll(paywallSelectors.join(',')).forEach(el => el.remove());

                    // 2. Strip CSS height truncation and linear-gradient fade masks
                    document.querySelectorAll('*').forEach(el => {
                        const style = window.getComputedStyle(el);
                        if (style.overflow === 'hidden' || style.maxHeight !== 'none' || 
                            style.webkitMaskImage !== 'none' || style.maskImage !== 'none') {
                            el.style.setProperty('overflow', 'visible', 'important');
                            el.style.setProperty('max-height', 'none', 'important');
                            el.style.setProperty('height', 'auto', 'important');
                            el.style.setProperty('-webkit-mask-image', 'none', 'important');
                            el.style.setProperty('mask-image', 'none', 'important');
                        }
                    });
                    document.body.style.setProperty('overflow', 'auto', 'important');
                    document.documentElement.style.setProperty('overflow', 'auto', 'important');

                    // 3. Hydrate from JSON-LD articleBody if visible article text is gated/truncated
                    const scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    scripts.forEach(s => {
                        try {
                            const raw = JSON.parse(s.innerText);
                            const items = Array.isArray(raw) ? raw : (raw['@graph'] ? raw['@graph'] : [raw]);
                            items.forEach(item => {
                                if (item && item.articleBody && typeof item.articleBody === 'string' && item.articleBody.length > 600) {
                                    const container = document.querySelector('article') || 
                                                      document.querySelector('[class*="article-body"]') || 
                                                      document.querySelector('main') || 
                                                      document.body;
                                    if (container && container.innerText.length < item.articleBody.length * 0.65) {
                                        const existingRecovered = document.getElementById('pagevault-jsonld-recovered');
                                        if (!existingRecovered) {
                                            const wrapper = document.createElement('div');
                                            wrapper.id = 'pagevault-jsonld-recovered';
                                            wrapper.style.cssText = 'padding:16px; margin-top:16px; border-top:2px solid #ccc; font-family:sans-serif; line-height:1.6;';
                                            const paragraphs = item.articleBody
                                                .split(/\n+/)
                                                .filter(p => p.trim().length > 0)
                                                .map(p => '<p style="margin-bottom:14px;">' + p.trim() + '</p>')
                                                .join('');
                                            wrapper.innerHTML = paragraphs;
                                            container.appendChild(wrapper);
                                        }
                                    }
                                }
                            });
                        } catch (innerErr) {}
                    });
                } catch (e) {}
                return "UNLOCKED";
            })();
        """.trimIndent()

        webView.evaluateJavascript(unlockScript) {
            DiagnosticLogHelper.log(TAG, "DOM sanitization and unlock script finished")
            // Small delay (150ms) to allow DOM layout reflow before saveWebArchive / PDF canvas draw
            webView.postDelayed({ onComplete() }, 150L)
        }
    }
}
