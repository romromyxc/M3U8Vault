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
        "https://searx.be",
        "https://search.sapti.me",
        "https://paulgo.io",
        "https://searx.tiekoetter.com"
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

    fun isAdUrl(url: String, shieldEnabled: Boolean): Boolean {
        if (!shieldEnabled) return false
        val lower = url.lowercase()
        return blockedKeywords.any { lower.contains(it) }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun resolveInput(input: String, searxngBase: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(".") && !input.contains(" ") -> "https://$input"
        else -> "$searxngBase/search?q=${Uri.encode(input)}&safesearch=0"
    }

    val HOOK_JS = """
        (function() {
            if (window.__vaultHooked) return;
            window.__vaultHooked = true;
            const origFetch = window.fetch;
            window.fetch = function(...args) {
                const target = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                if (target && target.includes('.m3u8')) VaultSniffer.onM3u8Detected(target);
                return origFetch.apply(this, args);
            };
            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                if (typeof url === 'string' && url.includes('.m3u8')) VaultSniffer.onM3u8Detected(url);
                return origOpen.apply(this, arguments);
            };
            window.open = function() { return null; };
        })();
    """.trimIndent()
}
// END OF FILE
