package com.local.pocketarchive.capture

import android.webkit.WebSettings
import android.webkit.WebView

object PaywallBypassHelper {

    const val GOOGLEBOT_USER_AGENT =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    val SPOOFED_HEADERS = mapOf(
        "Referer" to "https://www.google.com/",
        "X-Forwarded-For" to "66.249.66.1"
    )

    /**
     * Configures WebView settings prior to loadUrl().
     * @param spoofCrawler If true, sets User-Agent to Googlebot.
     * @param disableJs If true, disables JavaScript to stop client-side paywall truncation scripts.
     */
    fun configureWebViewForCapture(
        webView: WebView,
        spoofCrawler: Boolean = true,
        disableJs: Boolean = false
    ) {
        webView.settings.apply {
            javaScriptEnabled = !disableJs
            domStorageEnabled = true
            if (spoofCrawler) {
                userAgentString = GOOGLEBOT_USER_AGENT
            } else {
                userAgentString = WebSettings.getDefaultUserAgent(webView.context)
            }
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
            // Small delay (150ms) to allow DOM layout reflow before saveWebArchive / PDF canvas draw
            webView.postDelayed({ onComplete() }, 150L)
        }
    }
}
