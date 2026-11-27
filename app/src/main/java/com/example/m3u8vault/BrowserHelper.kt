package com.example.m3u8vault

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object BrowserHelper {
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val SEARXNG_INSTANCES = arrayOf(
        "https://4get.ca",
        "https://4get.zzls.xyz",
        "https://4get.sijh.net",
        "https://4get.edmateo.site"
    )

    private val blockedKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "adsterra.com",
        "clickadu.com", "hilltopads.net", "pushame.com", "onclkds.com",
        "11bet", "1xbet", "betway", "bet365", "parimatch", "melbet",
        "mostbet", "dafabet", "fairplay", "lotus365", "casino", "teenpatti",
        "rummycircle", "adnxs.com", "taboola.com", "outbrain.com", "mgid.com"
    )

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun isSameSite(hostA: String, hostB: String): Boolean {
        if (hostA.isBlank() || hostB.isBlank()) return true
        return hostA == hostB || hostA.endsWith("." + hostB) || hostB.endsWith("." + hostA)
    }

    fun isAdUrl(url: String, shieldEnabled: Boolean): Boolean {
        if (!shieldEnabled) return false
        val lower = url.lowercase()
        return blockedKeywords.any { lower.contains(it) }
    }

    fun shouldBlockRequest(
        url: String,
        shieldEnabled: Boolean,
        currentPageUrl: String? = null,
        blockThirdPartyScripts: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        if (!shieldEnabled) return false

        val acceptHeader = headers["Accept"] ?: headers["accept"] ?: ""
        if (isHlsStream(url, acceptHeader)) return false

        if (isAdUrl(url, shieldEnabled)) return true

        if (blockThirdPartyScripts) {
            val reqHost = extractHost(url)
            val pageHost = extractHost(currentPageUrl)
            if (reqHost.isNotBlank() && pageHost.isNotBlank() && !isSameSite(reqHost, pageHost)) {
                val lowerUrl = url.lowercase().substringBefore("?")
                val secFetchDest = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"] ?: "").lowercase()
                val isScriptOrFrame = secFetchDest in setOf("script", "iframe") ||
                    acceptHeader.lowercase().contains("javascript") ||
                    lowerUrl.endsWith(".js")
                if (isScriptOrFrame) return true
            }
        }

        return false
    }

    fun isHlsStream(url: String, acceptHeader: String = ""): Boolean {
        val u = url.lowercase()
        if (u.endsWith(".ts") || u.endsWith(".m4s") || u.endsWith(".jpg") || u.endsWith(".png") || u.endsWith(".css") || u.endsWith(".js")) {
            return false
        }
        return u.contains(".m3u8") ||
                acceptHeader.lowercase().contains("mpegurl") ||
                u.contains("chunklist") ||
                u.contains("master.txt") ||
                u.contains("/hls/") && u.contains("manifest")
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun resolveInput(input: String, searxngBase: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(".") && !input.contains(" ") -> "https://$input"
        else -> "$searxngBase/web?s=${Uri.encode(input)}&nsfw=yes"
    }

    val HOOK_JS = """
        (function() {
            if (window.__vaultHooked) return;
            window.__vaultHooked = true;

            let lastTapTime = 0;
            const tapGraceMs = 1500;
            const markTap = () => { lastTapTime = Date.now(); };
            window.addEventListener('pointerdown', markTap, true);
            window.addEventListener('touchstart', markTap, { capture: true, passive: true });
            window.addEventListener('click', markTap, true);

            const hasRecentTap = () => (Date.now() - lastTapTime) <= tapGraceMs;

            const isOffOrigin = (rawUrl) => {
                try {
                    const resolved = new URL(rawUrl, location.href);
                    if (resolved.protocol !== 'http:' && resolved.protocol !== 'https:') return false;
                    const a = location.hostname.replace(/^www\./, '');
                    const b = resolved.hostname.replace(/^www\./, '');
                    return a !== b && !b.endsWith('.' + a) && !a.endsWith('.' + b);
                } catch (e) {
                    return false;
                }
            };

            const checkUrl = (u) => {
                if (typeof u === 'string' && (u.includes('.m3u8') || u.includes('chunklist') || u.includes('master.txt'))) {
                    try {
                        VaultSniffer.onM3u8Detected(new URL(u, location.href).href);
                    } catch (e) {}
                }
            };

            const origFetch = window.fetch;
            window.fetch = function(...args) {
                const target = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                checkUrl(target);
                return origFetch.apply(this, args);
            };

            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                checkUrl(url);
                return origOpen.apply(this, arguments);
            };

            const origAssign = Location.prototype.assign;
            const origReplace = Location.prototype.replace;
            Location.prototype.assign = function(url) {
                if (!hasRecentTap() && isOffOrigin(url)) return;
                return origAssign.call(this, url);
            };
            Location.prototype.replace = function(url) {
                if (!hasRecentTap() && isOffOrigin(url)) return;
                return origReplace.call(this, url);
            };

            const origPushState = History.prototype.pushState;
            const origReplaceState = History.prototype.replaceState;
            History.prototype.pushState = function(state, title, url) {
                if (url && !hasRecentTap() && isOffOrigin(url)) return;
                return origPushState.apply(this, arguments);
            };
            History.prototype.replaceState = function(state, title, url) {
                if (url && !hasRecentTap() && isOffOrigin(url)) return;
                return origReplaceState.apply(this, arguments);
            };

            window.open = function(url) {
                if (typeof url === 'string' && url.trim().length > 0 && !url.startsWith('javascript:')) {
                    try {
                        const resolved = new URL(url, location.href).href;
                        if (hasRecentTap() || !isOffOrigin(resolved)) {
                            VaultSniffer.onWindowOpen(resolved);
                        }
                    } catch (e) {}
                }
                return null;
            };
        })();
    """.trimIndent()
}
// END OF FILE
