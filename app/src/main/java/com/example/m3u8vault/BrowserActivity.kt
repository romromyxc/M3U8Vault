package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs

class BrowserActivity : AppCompatActivity() {

    private data class BrowserTab(
        val id: Long = System.nanoTime(),
        val webView: WebView,
        var lastUrl: String = "",
        val streams: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    )

    private data class StreamOption(
        val label: String,
        val url: String
    )

    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var pageProgress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnReload: Button
    private lateinit var btnTabs: Button
    private lateinit var btnFloatingCatcher: Button

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var isPageLoading = false
    private var adShieldEnabled = true
    private var desktopMode = false
    private var searxngBaseUrl = "https://4get.ca"
    private var isInspecting = false
    private val inspectExecutor = Executors.newSingleThreadExecutor()

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
        btnReload = findViewById(R.id.btnReload)
        btnTabs = findViewById(R.id.btnTabs)
        btnFloatingCatcher = findViewById(R.id.btnFloatingCatcher)

        onBackPressedDispatcher.addCallback(this, backCallback)
        setupListeners()
        setupFloatingCatcher()
        openNewTab(searxngBaseUrl)
    }

    private fun setupListeners() {
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
        btnTabs.setOnClickListener { showTabSwitcher() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showNavigationDrawerMenu() }
        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultPlayerActivity::class.java))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingCatcher() {
        var dX = 0f
        var dY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false

        btnFloatingCatcher.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 12f || abs(event.rawY - downY) > 12f) {
                        moved = true
                        val maxX = (webContainer.width - v.width).coerceAtLeast(0).toFloat()
                        val maxY = (webContainer.height - v.height).coerceAtLeast(0).toFloat()
                        v.x = (event.rawX + dX).coerceIn(0f, maxX)
                        v.y = (event.rawY + dY).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openSmartDownloadPicker()
                    true
                }
                else -> false
            }
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)

    private fun safeCookies(url: String): String {
        if (!url.startsWith("http")) return ""
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
    }

    private fun resetStreamsIfUrlChanged(tab: BrowserTab, newUrl: String?) {
        val clean = newUrl?.substringBefore("#")?.trim().orEmpty()
        if (!clean.startsWith("http") || tab.lastUrl == clean) return
        tab.lastUrl = clean
        synchronized(tab.streams) { tab.streams.clear() }
        if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
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
                    .setTitle(link.take(50))
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
                if (BrowserHelper.isHlsStream(url)) {
                    synchronized(tab.streams) {
                        tab.streams.remove(url)
                        tab.streams.add(url)
                    }
                    if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
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
                    synchronized(tab.streams) {
                        tab.streams.remove(url)
                        tab.streams.add(url)
                    }
                    if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
                }
                if (BrowserHelper.isAdUrl(url, adShieldEnabled)) return BrowserHelper.emptyResponse()
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                resetStreamsIfUrlChanged(tab, url)
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
                resetStreamsIfUrlChanged(tab, url)
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
        btnTabs.text = "❐ ${tabs.size}"
        updateBadge()
        syncNav()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        removed.webView.stopLoading()
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            openNewTab(searxngBaseUrl)
        } else {
            val nextIdx = when {
                activeTabIndex > index -> activeTabIndex - 1
                activeTabIndex >= tabs.size -> tabs.size - 1
                else -> activeTabIndex
            }
            switchToTab(nextIdx)
        }
    }

    private fun syncNav() {
        val canBack = currentTab()?.webView?.canGoBack() == true
        btnBack.isEnabled = canBack
        backCallback.isEnabled = canBack || tabs.size > 1
    }

    private fun updateBadge() {
        val count = currentTab()?.let { t -> synchronized(t.streams) { t.streams.size } } ?: 0
        btnFloatingCatcher.text = "⬇ $count"
        btnFloatingCatcher.setTextColor(Color.parseColor(if (count > 0) "#00FF9C" else "#00E5FF"))
    }

    private fun loadInCurrentTab(input: String) {
        if (input.isBlank()) return
        val tab = currentTab() ?: return
        synchronized(tab.streams) { tab.streams.clear() }
        tab.lastUrl = ""
        updateBadge()
        tab.webView.loadUrl(BrowserHelper.resolveInput(input, searxngBaseUrl))
    }

    private fun showTabSwitcher() {
        val listView = ListView(this).apply {
            divider = null
            setPadding(18, 12, 18, 8)
        }
        var dialog: AlertDialog? = null

        val adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(p: Int) = tabs[p]
            override fun getItemId(p: Int) = tabs[p].id
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(this@BrowserActivity)
                    .inflate(R.layout.item_tab_row, parent, false)
                val t = tabs[pos]
                val isActive = pos == activeTabIndex
                val title = t.webView.title?.take(34)?.ifBlank { null }
                    ?: t.webView.url?.take(34)
                    ?: "New Tab"
                val cnt = synchronized(t.streams) { t.streams.size }

                val tvTitle = row.findViewById<TextView>(R.id.tvTabTitle)
                tvTitle.text = if (isActive) "● $title" else title
                tvTitle.setTextColor(Color.parseColor(if (isActive) "#00E5FF" else "#F0F4FF"))
                row.findViewById<TextView>(R.id.tvTabStreams).text = "$cnt stream(s) caught"

                row.findViewById<View>(R.id.tabRowInfo).setOnClickListener {
                    switchToTab(pos)
                    dialog?.dismiss()
                }
                row.findViewById<Button>(R.id.btnKillTab).setOnClickListener {
                    closeTab(pos)
                    dialog?.setTitle("Open Windows (${tabs.size})")
                    notifyDataSetChanged()
                }
                return row
            }
        }
        listView.adapter = adapter

        dialog = AlertDialog.Builder(this)
            .setTitle("Open Windows (${tabs.size})")
            .setView(listView)
            .setPositiveButton("+ New Tab") { _, _ -> openNewTab(searxngBaseUrl) }
            .setNeutralButton("Close All") { _, _ ->
                tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
                tabs.clear()
                openNewTab(searxngBaseUrl)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showNavigationDrawerMenu() {
        val wv = currentTab()?.webView
        val items = arrayOf(
            if (wv?.canGoForward() == true) "▶ Forward" else "▶ Forward (Disabled)",
            "⌂ 4get Home",
            "+ New Window / Tab",
            if (adShieldEnabled) "🛡 Ad & Redirect Shield: ON" else "🛡 Ad & Redirect Shield: OFF",
            if (desktopMode) "🖥 Desktop Site: ON" else "🖥 Desktop Site: OFF",
            "🔍 Change Search Engine",
            "🔗 Paste .m3u8 URL Manually",
            "🧹 Clear Detected Streams"
        )

        AlertDialog.Builder(this)
            .setTitle("Navigation & Tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> wv?.takeIf { it.canGoForward() }?.goForward()
                    1 -> loadInCurrentTab(searxngBaseUrl)
                    2 -> openNewTab(searxngBaseUrl)
                    3 -> {
                        adShieldEnabled = !adShieldEnabled
                        Toast.makeText(this, if (adShieldEnabled) "Shield ON" else "Shield OFF", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        desktopMode = !desktopMode
                        wv?.settings?.userAgentString = if (desktopMode) BrowserHelper.DESKTOP_UA else BrowserHelper.MOBILE_UA
                        wv?.reload()
                    }
                    5 -> AlertDialog.Builder(this)
                        .setTitle("Select Search Engine")
                        .setItems(BrowserHelper.SEARXNG_INSTANCES) { _, i ->
                            searxngBaseUrl = BrowserHelper.SEARXNG_INSTANCES[i]
                            loadInCurrentTab(searxngBaseUrl)
                        }.show()
                    6 -> showManualPasteDialog()
                    7 -> {
                        currentTab()?.let { t -> synchronized(t.streams) { t.streams.clear() } }
                        updateBadge()
                        Toast.makeText(this, "Stream list cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun showManualPasteDialog() {
        val tab = currentTab() ?: return
        val pageUrl = tab.webView.url.orEmpty()
        val ua = runCatching { tab.webView.settings.userAgentString }.getOrDefault(BrowserHelper.MOBILE_UA)
        val cookies = safeCookies(pageUrl)
        val defaultTitle = tab.webView.title?.take(40)?.ifBlank { "HLS_Video" } ?: "HLS_Video"

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 10)
        }
        val etTitle = EditText(this).apply { setText(defaultTitle); hint = "Video title" }
        val etUrl = EditText(this).apply { hint = "https://.../playlist.m3u8" }
        box.addView(etTitle)
        box.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("Download Direct M3U8 URL")
            .setView(box)
            .setPositiveButton("Queue") { _, _ ->
                val url = etUrl.text.toString().trim()
                val name = etTitle.text.toString().trim().ifBlank { defaultTitle }
                if (url.startsWith("http")) {
                    runCatching {
                        VaultEngine.enqueueM3u8Download(this, name, url, StreamHeaders(pageUrl, ua, cookies))
                        Toast.makeText(this, "Added to Downloading", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, "Queue error: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSmartDownloadPicker() {
        if (isInspecting) return
        val tab = currentTab() ?: return
        val rawUrls = synchronized(tab.streams) { tab.streams.toList().reversed() }
        if (rawUrls.isEmpty()) {
            Toast.makeText(this, "No M3U8 streams detected yet. Play the video first.", Toast.LENGTH_SHORT).show()
            return
        }

        val pageUrl = tab.webView.url.orEmpty()
        val ua = runCatching { tab.webView.settings.userAgentString }.getOrDefault(BrowserHelper.MOBILE_UA)
        val cookies = safeCookies(pageUrl)
        val defaultTitle = tab.webView.title?.take(40)?.ifBlank { "HLS_Video" } ?: "HLS_Video"

        isInspecting = true
        btnFloatingCatcher.text = "⏳"

        inspectExecutor.execute {
            val options = mutableListOf<StreamOption>()
            val seen = HashSet<String>()

            for (rawUrl in rawUrls.take(8)) {
                val parsed = inspectM3u8(rawUrl, pageUrl, ua, cookies)
                for (opt in parsed) {
                    if (seen.add(opt.url)) options.add(opt)
                }
            }

            if (options.isEmpty()) {
                rawUrls.forEachIndexed { idx, u ->
                    options.add(StreamOption("Stream #${idx + 1} • ${u.takeLast(38)}", u))
                }
            }

            runOnUiThread {
                isInspecting = false
                updateBadge()

                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(36, 16, 36, 8)
                }
                val etName = EditText(this).apply {
                    setText(defaultTitle)
                    hint = "Save video as..."
                }
                box.addView(etName)

                val labels = options.map { it.label }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Stream Quality (${options.size})")
                    .setView(box)
                    .setItems(labels) { _, which ->
                        val picked = options[which]
                        val name = etName.text.toString().trim().ifBlank { defaultTitle }
                        runCatching {
                            VaultEngine.enqueueM3u8Download(
                                this,
                                name,
                                picked.url,
                                StreamHeaders(pageUrl, ua, cookies)
                            )
                            Toast.makeText(this, "Downloading: $name", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun inspectM3u8(m3u8Url: String, referer: String, ua: String, cookies: String): List<StreamOption> {
        val results = mutableListOf<StreamOption>()
        try {
            val conn = (URL(m3u8Url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", ua)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                var pendingQuality = "Auto"
                for (line in lines) {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = Regex("RESOLUTION=\\d+x(\\d+)").find(line)
                        val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                        val height = resMatch?.groupValues?.getOrNull(1)
                        val kbps = bwMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.div(1000)
                        pendingQuality = when {
                            height != null && kbps != null -> "${height}p (${kbps} kbps)"
                            height != null -> "${height}p"
                            kbps != null -> "${kbps} kbps"
                            else -> "Variant"
                        }
                    } else if (!line.startsWith("#")) {
                        val resolved = runCatching { URL(URL(m3u8Url), line).toString() }.getOrDefault(line)
                        results.add(StreamOption("🎬 $pendingQuality", resolved))
                        pendingQuality = "Auto"
                    }
                }
            } else {
                var totalSeconds = 0.0
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        val sec = line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                        totalSeconds += sec
                    }
                }
                val mins = (totalSeconds / 60).toInt()
                val secs = (totalSeconds % 60).toInt()
                val tag = if (totalSeconds in 0.1..15.0) "⚠ Short Clip (${secs}s)" else "🎬 Direct HLS (${mins}m ${secs}s)"
                results.add(StreamOption(tag, m3u8Url))
            }
        } catch (_: Exception) {
            results.add(StreamOption("🎬 Direct Stream • ${m3u8Url.takeLast(34)}", m3u8Url))
        }
        return results
    }

    override fun onDestroy() {
        inspectExecutor.shutdownNow()
        tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
