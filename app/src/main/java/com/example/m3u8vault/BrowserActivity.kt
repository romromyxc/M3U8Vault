package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Bitmap
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

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val current = currentTab() ?: return
            if (current.webView.canGoBack()) current.webView.goBack()
            else if (tabs.size > 1) closeTab(activeTabIndex)
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
        setupListeners()
        openNewTab(searxngBaseUrl)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnHome).setOnClickListener { loadInCurrentTab(searxngBaseUrl) }

        findViewById<Button>(R.id.btnClearUrl).setOnClickListener {
            urlBar.setText("")
            urlBar.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
        }

        btnReload.setOnClickListener {
            currentTab()?.webView?.let { if (isPageLoading) it.stopLoading() else it.reload() }
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadInCurrentTab(urlBar.text.toString().trim())
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else false
        }

        btnBack.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoForward() }?.goForward() }
        btnTabs.setOnClickListener { showTabSwitcher() }

        btnShield.setOnClickListener {
            adShieldEnabled = !adShieldEnabled
            btnShield.text = if (adShieldEnabled) "Shield: ON" else "Shield: OFF"
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenuDialog() }
        btnDownloadBadge.setOnClickListener { showDownloadDialog() }
        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultPlayerActivity::class.java))
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)

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
                userAgentString = if (desktopMode) BrowserHelper.DESKTOP_UA else BrowserHelper.MOBILE_UA
            }
        }

        val tab = BrowserTab(webView = wv)

        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val link = hit.extra
            if (!link.isNullOrBlank() && (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                AlertDialog.Builder(this)
                    .setTitle(link.take(55))
                    .setItems(arrayOf("Open in New Tab", "Copy Link")) { _, which ->
                        if (which == 0) openNewTab(link)
                        else {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("URL", link))
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
                true
            } else false
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onM3u8Detected(url: String) {
                if (BrowserHelper.isHlsStream(url) && tab.streams.add(url) && currentTab()?.id == tab.id) {
                    runOnUiThread { updateBadge() }
                }
            }
        }, "VaultSniffer")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.isBlank()) return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                if (BrowserHelper.isAdUrl(url, adShieldEnabled)) {
                    runOnUiThread { Toast.makeText(this@BrowserActivity, "Blocked ad redirect", Toast.LENGTH_SHORT).show() }
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                val accept = request?.requestHeaders?.get("Accept").orEmpty()
                if (BrowserHelper.isHlsStream(url, accept)) {
                    if (tab.streams.add(url) && currentTab()?.id == tab.id) {
                        runOnUiThread { updateBadge() }
                    }
                }
                if (BrowserHelper.isAdUrl(url, adShieldEnabled)) return BrowserHelper.emptyResponse()
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (currentTab()?.id == tab.id) {
                    isPageLoading = true
                    btnReload.text = "✕"
                    pageProgress.visibility = View.VISIBLE
                    if (!url.isNullOrBlank()) urlBar.setText(url)
                    syncNav()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (currentTab()?.id == tab.id) {
                    isPageLoading = false
                    btnReload.text = "↻"
                    pageProgress.visibility = View.GONE
                    if (!url.isNullOrBlank()) urlBar.setText(url)
                    syncNav()
                }
                view?.evaluateJavascript(BrowserHelper.HOOK_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (currentTab()?.id == tab.id) syncNav()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (currentTab()?.id == tab.id) {
                    pageProgress.progress = newProgress
                    pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val hitUrl = view?.hitTestResult?.extra
                if (!hitUrl.isNullOrBlank() && isUserGesture && !BrowserHelper.isAdUrl(hitUrl, adShieldEnabled)) {
                    openNewTab(hitUrl)
                } else if (adShieldEnabled) {
                    Toast.makeText(this@BrowserActivity, "Blocked popup", Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }

        tabs.add(tab)
        switchToTab(tabs.size - 1)
        if (!initialUrl.isNullOrBlank()) wv.loadUrl(initialUrl)
        updateBadge()
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
        updateBadge()
        syncNav()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        removed.webView.stopLoading()
        removed.webView.destroy()
        if (tabs.isEmpty()) openNewTab(searxngBaseUrl)
        else switchToTab(index.coerceAtMost(tabs.size - 1))
    }

    private fun syncNav() {
        val wv = currentTab()?.webView
        val canBack = wv?.canGoBack() == true
        btnBack.isEnabled = canBack
        btnForward.isEnabled = wv?.canGoForward() == true
        backCallback.isEnabled = canBack || tabs.size > 1
    }

    private fun updateBadge() {
        val count = currentTab()?.streams?.size ?: 0
        btnDownloadBadge.text = "Download ($count)"
        btnDownloadBadge.isEnabled = true
    }

    private fun loadInCurrentTab(input: String) {
        if (input.isBlank()) return
        val tab = currentTab() ?: return
        tab.streams.clear()
        updateBadge()
        tab.webView.loadUrl(BrowserHelper.resolveInput(input, searxngBaseUrl))
    }

    private fun showTabSwitcher() {
        val titles = tabs.mapIndexed { i, t ->
            val mark = if (i == activeTabIndex) "● " else "   "
            val name = t.webView.title?.take(32)?.ifBlank { null } ?: t.webView.url?.take(32) ?: "New Tab"
            "$mark$name (${t.streams.size})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(titles) { _, which -> switchToTab(which) }
            .setPositiveButton("+ New Tab") { _, _ -> openNewTab(searxngBaseUrl) }
            .setNeutralButton("Close Tab") { _, _ -> closeTab(activeTabIndex) }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showMenuDialog() {
        val items = arrayOf(
            "+ Open New Tab",
            if (desktopMode) "✓ Desktop Site" else "Desktop Site",
            "Change SearXNG Engine",
            "Clear Tab Streams"
        )
        AlertDialog.Builder(this)
            .setTitle("Browser Options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openNewTab(searxngBaseUrl)
                    1 -> {
                        desktopMode = !desktopMode
                        currentTab()?.webView?.apply {
                            settings.userAgentString = if (desktopMode) BrowserHelper.DESKTOP_UA else BrowserHelper.MOBILE_UA
                            reload()
                        }
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("SearXNG Instance")
                        .setItems(BrowserHelper.SEARXNG_INSTANCES) { _, i ->
                            searxngBaseUrl = BrowserHelper.SEARXNG_INSTANCES[i]
                            loadInCurrentTab(searxngBaseUrl)
                        }.show()
                    3 -> {
                        currentTab()?.streams?.clear()
                        updateBadge()
                    }
                }
            }.show()
    }

    private fun showDownloadDialog() {
        val tab = currentTab() ?: return
        val urls = tab.streams.toTypedArray()
        val title = tab.webView.title?.take(40)?.ifBlank { "Video" } ?: "Video"
        val pageUrl = tab.webView.url.orEmpty()
        val ua = tab.webView.settings.userAgentString
        val cookies = CookieManager.getInstance().getCookie(pageUrl).orEmpty()

        if (urls.isEmpty()) {
            val input = EditText(this).apply { hint = "Paste .m3u8 URL manually..." }
            AlertDialog.Builder(this)
                .setTitle("No Auto-Detected Streams Yet")
                .setMessage("Press Play on the video first so the player loads the stream, or paste an .m3u8 URL below:")
                .setView(input)
                .setPositiveButton("Download URL") { _, _ ->
                    val manualUrl = input.text.toString().trim()
                    if (manualUrl.startsWith("http")) {
                        VaultEngine.enqueueM3u8Download(this, title, manualUrl, StreamHeaders(pageUrl, ua, cookies))
                        Toast.makeText(this, "Queued to Private Vault", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val checked = BooleanArray(urls.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Download M3U8 (${urls.size})")
            .setMultiChoiceItems(urls, checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("Download") { _, _ ->
                var count = 0
                urls.forEachIndexed { i, url ->
                    if (checked[i]) {
                        VaultEngine.enqueueM3u8Download(this, "${title}_${i + 1}", url, StreamHeaders(pageUrl, ua, cookies))
                        count++
                    }
                }
                Toast.makeText(this, "Queued $count video(s)", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Paste URL") { _, _ ->
                tab.streams.clear()
                showDownloadDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
// END OF FILE
