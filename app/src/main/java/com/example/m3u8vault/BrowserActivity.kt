package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Collections

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var btnDownloadBadge: Button

    private var searxngBaseUrl = "https://searx.be"
    private val detectedStreams = Collections.synchronizedSet(LinkedHashSet<String>())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        btnDownloadBadge = findViewById(R.id.btnDownloadBadge)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onM3u8Detected(url: String) {
                if (url.contains(".m3u8", ignoreCase = true)) {
                    recordStream(url)
                }
            }
        }, "VaultSniffer")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString().orEmpty()
                val acceptHeader = request?.requestHeaders?.get("Accept").orEmpty()

                if (reqUrl.contains(".m3u8", ignoreCase = true) ||
                    acceptHeader.contains("mpegurl", ignoreCase = true)
                ) {
                    recordStream(reqUrl)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                urlBar.setText(url)
                view?.evaluateJavascript(
                    """
                    (function() {
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
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                navigateInput(urlBar.text.toString().trim())
                true
            } else false
        }

        btnDownloadBadge.setOnClickListener { showMultiSelectDownloadDialog() }

        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultPlayerActivity::class.java))
        }

        webView.loadUrl(searxngBaseUrl)
    }

    private fun navigateInput(input: String) {
        if (input.isBlank()) return
        detectedStreams.clear()
        updateBadge()

        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "$searxngBaseUrl/search?q=${Uri.encode(input)}&safesearch=0"
        }
        webView.loadUrl(targetUrl)
    }

    private fun recordStream(m3u8Url: String) {
        if (detectedStreams.add(m3u8Url)) {
            runOnUiThread { updateBadge() }
        }
    }

    private fun updateBadge() {
        btnDownloadBadge.text = "Streams (${detectedStreams.size})"
        btnDownloadBadge.isEnabled = detectedStreams.isNotEmpty()
    }

    private fun showMultiSelectDownloadDialog() {
        val urls = detectedStreams.toTypedArray()
        val checked = BooleanArray(urls.size) { true }
        val pageTitle = webView.title?.take(40)?.ifBlank { "Video" } ?: "Video"
        val currentPageUrl = webView.url.orEmpty()
        val ua = webView.settings.userAgentString
        val cookies = CookieManager.getInstance().getCookie(currentPageUrl).orEmpty()

        AlertDialog.Builder(this)
            .setTitle("Queue M3U8 Downloads (${urls.size} found)")
            .setMultiChoiceItems(urls, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Download Selected") { _, _ ->
                var queued = 0
                urls.forEachIndexed { idx, streamUrl ->
                    if (checked[idx]) {
                        VaultEngine.enqueueM3u8Download(
                            context = this,
                            title = "${pageTitle}_${idx + 1}",
                            m3u8Url = streamUrl,
                            headers = StreamHeaders(
                                referer = currentPageUrl,
                                userAgent = ua,
                                cookie = cookies
                            )
                        )
                        queued++
                    }
                }
                Toast.makeText(this, "Queued $queued stream(s) to Private Vault", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}