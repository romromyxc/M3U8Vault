package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.util.Collections

class BrowserActivity : AppCompatActivity() {

    private data class BrowserTab(
        val id: Long = System.nanoTime(),
        val webView: WebView,
        val streams: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    )

    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var pageProgress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnReload: Button
    private lateinit var btnTabs: Button
    private lateinit var btnShield: Button
    private lateinit var btnDownloadBadge: Button

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var isPageLoading = false
    private var adShieldEnabled = true
    private var desktopMode = false
    private var searxngBaseUrl = "https://searx.be"

    private val blockedKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "adsterra.com",
        "clickadu.com", "hilltopads.net", "pushame.com", "onclkds.com",
        "11bet", "1xbet", "betway", "bet365", "parimatch", "melbet",
        "mostbet", "dafabet", "fairplay", "lotus365", "casino", "teenpatti",
        "rummycircle", "adnxs.com", "taboola.com", "outbrain.com", "mgid.com"
    )

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val current = currentTab() ?: return
            if (current.webView.canGoBack()) {
                current.webView.goBack()
            } else if (tabs.size > 1) {
                closeTab(activeTabIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webContainer = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        pageProgress = findViewById(R.id.pageProgress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnReload = findViewById(R.id.btnReload)
        btnTabs = findViewById(R.id.btnTabs)
        btnShield = findViewById(R.id.btnShield)
        btnDownloadBadge = findViewById(R.id.btnDownloadBadge)

        onBackPressedDispatcher.addCallback(this, backCallback)

        setupToolbarListeners()
        openNewTab(searxngBaseUrl)
    }

    private fun setupToolbarListeners() {
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            loadInputInCurrentTab(searxngBaseUrl)
        }

        findViewById<Button>(R.id.btnClearUrl).setOnClickListener {
            urlBar.setText("")
            urlBar.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
        }

        btnReload.setOnClickListener {
            val wv = currentTab()?.webView ?: return@setOnClickListener
            if (isPageLoading) wv.stopLoading() else wv.reload()
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadInputInCurrentTab(urlBar.text.toString().trim())
                urlBar.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else false
        }

        btnBack.setOnClickListener {
            currentTab()?.webView?.let { if (it.canGoBack()) it.goBack() }
        }

        btnForward.setOnClickListener {
            currentTab()?.webView?.let { if (it.canGoForward()) it.goForward() }
        }

        btnTabs.setOnClickListener { showTabSwitcherDialog() }

        btnShield.setOnClickListener {
            adShieldEnabled = !adShieldEnabled
            btnShield.text = if (adShieldEnabled) "Shield: ON" else "Shield: OFF"
            Toast.makeText(
                this,
                if (adShieldEnabled) "Ad & Popup Redirect Shield Enabled" else "Ad Shield Paused",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { showBrowserMenuDialog() }

        btnDownloadBadge.setOnClickListener { showMultiSelectDownloadDialog() }

        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultPlayerActivity::class.java))
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)

    private fun isAdOrRedirectUrl(url: String): Boolean {
        if (!adShieldEnabled) return false
        val lower = url.lowercase()
        return blockedKeywords.any { lower.contains(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openNewTab(initialUrl: String? = searxngBaseUrl): BrowserTab {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent
            }
        }

        val tab = BrowserTab(webView = wv)

        // Long-press links to open in a new tab or copy URL
        wv.setOnLongClickListener {
            val result = wv.hitTestResult
            val linkUrl = result.extra
            if (!linkUrl.isNullOrBlank() &&
                (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                AlertDialog.Builder(this)
                    .setTitle(linkUrl.take(60))
                    .setItems(arrayOf("Open in New Tab", "Copy Link Address")) { _, which ->
                        when (which) {
                            0 -> openNewTab(linkUrl)
                            1 -> {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("URL", linkUrl))
                                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
                true
            } else false
        }

        // JS bridge to catch fetch()/XHR .m3u8 streams before MSE converts them to blob:
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onM3u8Detected(url: String) {
                if (url.contains(".m3u8", ignoreCase = true) && tab.streams.add(url)) {
                    if (currentTab()?.id == tab.id) {
                        runOnUiThread { updateStreamBadge() }
                    }
                }
            }
        }, "VaultSniffer")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                if (targetUrl.isBlank()) return false

                // Block non-http schemes like intent://, market://, tg:// used by ad redirects
                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    return true
                }

                // Block ad / betting redirect domains from hijacking the active page
                if (isAdOrRedirectUrl(targetUrl)) {
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "Blocked ad redirect", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString().orEmpty()
                val acceptHeader = request?.requestHeaders?.get("Accept").orEmpty()

                if (reqUrl.contains(".m3u8", ignoreCase = true) ||
                    acceptHeader.contains("mpegurl", ignoreCase = true)
                ) {
                    if (tab.streams.add(reqUrl) && currentTab()?.id == tab.id) {
                        runOnUiThread { updateStreamBadge() }
                    }
                }

                // Drop network requests to known ad/tracker servers
                if (isAdOrRedirectUrl(reqUrl)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (currentTab()?.id == tab.id) {
                    isPageLoading = true
                    btnReload.text = "✕"
                    pageProgress.visibility = View.VISIBLE
                    if (!url.isNullOrBlank()) urlBar.setText(url)
                    syncNavigationState()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (currentTab()?.id == tab.id) {
                    isPageLoading = false
                    btnReload.text = "↻"
                    pageProgress.visibility = View.GONE
                    if (!url.isNullOrBlank()) urlBar.setText(url)
                    syncNavigationState()
                }

                // Hook fetch/XHR for M3U8 detection & neutralize popup window.open ad hijacks
                view?.evaluateJavascript(
                    """
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
                        window.open = function(url) {
                            if (url && typeof url === 'string' && url.startsWith('http')) {
                                console.log('Blocked popup: ' + url);
                            }
                            return null;
                        };
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (currentTab()?.id == tab.id) {
                    syncNavigationState()
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (currentTab()?.id == tab.id) {
                    pageProgress.progress = newProgress
                    pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            // Catch target="_blank" / new-window requests safely instead of hijacking the tab
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitUrl = view?.hitTestResult?.extra
                if (!hitUrl.isNullOrBlank() && isUserGesture && !isAdOrRedirectUrl(hitUrl)) {
                    openNewTab(hitUrl)
                } else if (adShieldEnabled) {
                    Toast.makeText(this@BrowserActivity, "Blocked popup window", Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }

        tabs.add(tab)
        switchToTab(tabs.size - 1)
        if (!initialUrl.isNullOrBlank()) {
            wv.loadUrl(initialUrl)
        }
        return tab
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        val tab = tabs[index]

        webContainer.removeAllViews()
        webContainer.addView(tab.webView)

        urlBar.setText(tab.webView.url ?: searxngBaseUrl)
        btnTabs.text = "Tabs (${tabs.size})"
        updateStreamBadge()
        syncNavigationState()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        removed.webView.stopLoading()
        removed.webView.destroy()

        if (tabs.isEmpty()) {
            openNewTab(searxngBaseUrl)
        } else {
            val nextIndex = index.coerceAtMost(tabs.size - 1)
            switchToTab(nextIndex)
        }
    }

    private fun syncNavigationState() {
        val wv = currentTab()?.webView
        val canBack = wv?.canGoBack() == true
        val canFwd = wv?.canGoForward() == true
        btnBack.isEnabled = canBack
        btnForward.isEnabled = canFwd
        backCallback.isEnabled = canBack || tabs.size > 1
    }

    private fun updateStreamBadge() {
        val count = currentTab()?.streams?.size ?: 0
        btnDownloadBadge.text = "Streams ($count)"
        btnDownloadBadge.isEnabled = count > 0
    }

    private fun loadInputInCurrentTab(input: String) {
        if (input.isBlank()) return
        val tab = currentTab() ?: return
        tab.streams.clear()
        updateStreamBadge()

        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "$searxngBaseUrl/search?q=${Uri.encode(input)}&safesearch=0"
        }
        tab.webView.loadUrl(targetUrl)
    }

    private fun showTabSwitcherDialog() {
        val tabTitles = tabs.mapIndexed { idx, tab ->
            val prefix = if (idx == activeTabIndex) "● " else "   "
            val title = tab.webView.title?.take(34)?.ifBlank { null }
                ?: tab.webView.url?.take(34)
                ?: "New Tab"
            "$prefix$title (${tab.streams.size} streams)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Open Tabs (${tabs.size})")
            .setItems(tabTitles) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton("+ New Tab") { _, _ ->
                openNewTab(searxngBaseUrl)
            }
            .setNeutralButton("Close Current Tab") { _, _ ->
                closeTab(activeTabIndex)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showBrowserMenuDialog() {
        val options = arrayOf(
            "+ Open New Tab",
            if (desktopMode) "✓ Desktop Site (Tap for Mobile)" else "Desktop Site",
            "Change SearXNG Instance",
            "Clear Detected Streams on Tab"
        )

        AlertDialog.Builder(this)
            .setTitle("Browser Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openNewTab(searxngBaseUrl)
                    1 -> {
                        desktopMode = !desktopMode
                        currentTab()?.webView?.apply {
                            settings.userAgentString =
                                if (desktopMode) desktopUserAgent else mobileUserAgent
                            reload()
                        }
                        Toast.makeText(
                            this,
                            if (desktopMode) "Desktop Site Enabled" else "Mobile Site Enabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> showSearxngInstanceDialog()
                    3 -> {
                        currentTab()?.streams?.clear()
                        updateStreamBadge()
                    }
                }
            }
            .show()
    }

    private fun showSearxngInstanceDialog() {
        val instances = arrayOf(
            "https://searx.be",
            "https://search.sapti.me",
            "https://paulgo.io",
            "https://searx.tiekoetter.com"
        )
        AlertDialog.Builder(this)
            .setTitle("Select SearXNG Search Engine")
            .setItems(instances) { _, which ->
                searxngBaseUrl = instances[which]
                loadInputInCurrentTab(searxngBaseUrl)
            }
            .show()
    }

    private fun showMultiSelectDownloadDialog() {
        val tab = currentTab() ?: return
        val urls = tab.streams.toTypedArray()
        val checked = BooleanArray(urls.size) { true }
        val pageTitle = tab.webView.title?.take(40)?.ifBlank { "Video" } ?: "Video"
        val currentPageUrl = tab.webView.url.orEmpty()
        val ua = tab.webView.settings.userAgentString
        val cookies = CookieManager.getInstance().getCookie(currentPageUrl).orEmpty()

        AlertDialog.Builder(this)
            .setTitle("Queue M3U8 Downloads (${urls.size} found)")
            .setMultiChoiceItems(urls, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Download Selected") { _, _ ->
                var queued =