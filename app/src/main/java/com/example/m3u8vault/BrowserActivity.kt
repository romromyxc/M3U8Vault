package com.example.m3u8vault

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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
        val url: String,
        val qualityBadge: String = "",
        val sortHeight: Int = 0
    )

    private lateinit var canvasWrapper: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var pageProgress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnReload: Button
    private lateinit var btnTabs: TextView
    private lateinit var btnFloatingCatcher: Button

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var isPageLoading = false
    private var adShieldEnabled = true
    private var desktopMode = false
    private var autoTranslateEnabled = false
    private var translateTargetLang = "en"
    private var searxngBaseUrl = "https://4get.ca"
    private var isInspecting = false
    private val inspectExecutor = Executors.newSingleThreadExecutor()
    private val translateExecutor = Executors.newFixedThreadPool(2)

    private val supportedLanguages = listOf(
        "English" to "en",
        "Hindi (हिन्दी)" to "hi",
        "Bengali (বাংলা)" to "bn",
        "Nepali (नेपाली)" to "ne",
        "Urdu (اردو)" to "ur",
        "Tamil (தமிழ்)" to "ta",
        "Telugu (తెలుగు)" to "te",
        "Marathi (मराठी)" to "mr",
        "Spanish (Español)" to "es",
        "Arabic (العربية)" to "ar",
        "Russian (Русский)" to "ru",
        "Japanese (日本語)" to "ja"
    )

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val current = currentTab() ?: return
            if (current.webView.canGoBack()) current.webView.goBack()
            else if (tabs.size > 1) closeTab(activeTabIndex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1E1E2E")
        window.navigationBarColor = Color.parseColor("#1E1E2E")
        setContentView(R.layout.activity_browser)

        val prefs = getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
        autoTranslateEnabled = prefs.getBoolean("auto_translate_enabled", false)
        translateTargetLang = prefs.getString("translate_target_lang", "en") ?: "en"

        canvasWrapper = findViewById(R.id.canvasWrapper)
        webContainer = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        pageProgress = findViewById(R.id.pageProgress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnReload = findViewById(R.id.btnReload)
        btnTabs = findViewById(R.id.btnTabs)
        btnFloatingCatcher = findViewById(R.id.btnFloatingCatcher)

        applyCatppuccinMochaShapes()
        onBackPressedDispatcher.addCallback(this, backCallback)
        setupListeners()
        setupFloatingCatcher()
        openNewTab(searxngBaseUrl)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dpInt(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyCatppuccinMochaShapes() {
        findViewById<View>(R.id.searchPillContainer).background = GradientDrawable().apply {
            setColor(Color.parseColor("#313244"))
            cornerRadius = dp(20f)
        }

        btnFloatingCatcher.background = GradientDrawable().apply {
            setColor(Color.parseColor("#A6E3A1"))
            cornerRadius = dp(21f)
        }

        btnTabs.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(5f)
            setStroke(dpInt(1.5f), Color.parseColor("#CDD6F4"))
        }
    }

    private fun syncSmartTopButton() {
        val editingWithText = urlBar.hasFocus() && !urlBar.text.isNullOrEmpty()
        btnReload.text = when {
            editingWithText -> "✕"
            isPageLoading -> "✕"
            else -> "↻"
        }
    }

    private fun setupListeners() {
        urlBar.setOnFocusChangeListener { _, _ -> syncSmartTopButton() }

        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { syncSmartTopButton() }
        })

        btnReload.setOnClickListener {
            if (urlBar.hasFocus() && !urlBar.text.isNullOrEmpty()) {
                urlBar.setText("")
                urlBar.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
                syncSmartTopButton()
            } else {
                currentTab()?.webView?.let { if (isPageLoading) it.stopLoading() else it.reload() }
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadInCurrentTab(urlBar.text.toString().trim())
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(urlBar.windowToken, 0)
                syncSmartTopButton()
                true
            } else false
        }

        btnBack.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoForward() }?.goForward() }

        val tabSlot = findViewById<View>(R.id.tabSlot)
        tabSlot.setOnClickListener { showTabSwitcher() }
        tabSlot.setOnLongClickListener {
            openNewTab(searxngBaseUrl)
            Toast.makeText(this, "New tab opened", Toast.LENGTH_SHORT).show()
            true
        }

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
                        val maxX = (canvasWrapper.width - v.width).coerceAtLeast(0).toFloat()
                        val maxY = (canvasWrapper.height - v.height).coerceAtLeast(0).toFloat()
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

    private fun isNonVideoDocument(fileName: String, mimetype: String?): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimetype?.lowercase().orEmpty()
        val nonVideoExts = listOf(
            ".pdf", ".zip", ".rar", ".7z", ".apk", ".txt", ".doc", ".docx",
            ".xls", ".xlsx", ".jpg", ".jpeg", ".png", ".webp", ".gif",
            ".mp3", ".m4a", ".aac", ".ogg"
        )
        if (nonVideoExts.any { lowerName.endsWith(it) }) return true
        if (lowerMime.startsWith("image/") || lowerMime.startsWith("audio/") || lowerMime.startsWith("text/") || lowerMime == "application/pdf" || lowerMime == "application/zip") {
            return true
        }
        return false
    }

    private fun triggerAutoTranslation(webView: WebView?) {
        if (!autoTranslateEnabled || webView == null) return
        val script = """
            (function() {
                if (window.__vaultTranslating) return;
                window.__vaultTranslating = true;
                window.__vaultTextNodes = [];
                window.__vaultBatches = {};
                const skipTags = new Set(['SCRIPT','STYLE','NOSCRIPT','CODE','PRE','INPUT','TEXTAREA','SELECT','SVG']);
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        if (!node.parentElement || skipTags.has(node.parentElement.tagName)) return NodeFilter.FILTER_REJECT;
                        const txt = node.nodeValue.trim();
                        if (txt.length < 2 || /^[0-9\s\W_]+$/.test(txt)) return NodeFilter.FILTER_REJECT;
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });
                let n;
                while ((n = walker.nextNode())) {
                    if (!n.__vaultOrig) n.__vaultOrig = n.nodeValue;
                    window.__vaultTextNodes.push(n);
                }
                const nodes = window.__vaultTextNodes;
                let batch = [];
                let indices = [];
                let charCount = 0;
                let batchId = 1;
                const flush = () => {
                    if (!batch.length) return;
                    const id = batchId++;
                    window.__vaultBatches[id] = indices;
                    VaultSniffer.requestTranslate(id, JSON.stringify(batch));
                    batch = [];
                    indices = [];
                    charCount = 0;
                };
                for (let i = 0; i < nodes.length && i < 350; i++) {
                    const t = nodes[i].__vaultOrig.trim();
                    if (charCount + t.length > 1400) flush();
                    batch.push(t);
                    indices.push(i);
                    charCount += t.length;
                }
                flush();
                window.__applyVaultTranslation = function(id, translatedJson) {
                    try {
                        const arr = JSON.parse(translatedJson);
                        const idxs = window.__vaultBatches[id] || [];
                        for (let j = 0; j < idxs.length && j < arr.length; j++) {
                            if (arr[j]) window.__vaultTextNodes[idxs[j]].nodeValue = arr[j];
                        }
                    } catch (e) {}
                };
                setTimeout(() => { window.__vaultTranslating = false; }, 1500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun restoreOriginalPageText(webView: WebView?) {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vaultTextNodes) {
                    for (const n of window.__vaultTextNodes) {
                        if (n.__vaultOrig) n.nodeValue = n.__vaultOrig;
                    }
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun translateBatchRemote(texts: List<String>, targetLang: String): List<String> {
        val joined = texts.joinToString("\n|||VAULT_SEP|||\n")
        val encoded = URLEncoder.encode(joined, "UTF-8")
        val apiUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", BrowserHelper.MOBILE_UA)
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONArray(body)
        val sentences = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            sb.append(sentences.getJSONArray(i).optString(0))
        }
        return sb.toString().split(Regex("\\s*\\|\\|\\|VAULT_SEP\\|\\|\\|\\s*"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openNewTab(initialUrl: String? = searxngBaseUrl): BrowserTab {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1E1E2E"))
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

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                return@setDownloadListener
            }
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val cleanDefaultTitle = guessedName.substringBeforeLast(".").ifBlank {
                wv.title?.trim()?.take(40)?.ifBlank { "Vault_Video" } ?: "Vault_Video"
            }
            val referer = wv.url.orEmpty()
            val cookies = safeCookies(url)
            val isDocument = isNonVideoDocument(guessedName, mimetype)

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 20, 36, 8)
            }
            val etFileName = EditText(this).apply {
                setText(if (isDocument) guessedName else cleanDefaultTitle)
                hint = if (isDocument) "File name" else "Video title in Vault"
            }
            box.addView(etFileName)

            AlertDialog.Builder(this)
                .setTitle(if (isDocument) "Download File" else "Save Video to Private Vault")
                .setMessage(url.take(65))
                .setView(box)
                .setPositiveButton(if (isDocument) "Download" else "Save to Vault") { _, _ ->
                    if (isDocument) {
                        val finalName = etFileName.text.toString().trim().ifBlank { guessedName }
                        startPublicFileDownload(url, finalName, userAgent, referer, cookies, mimetype)
                    } else {
                        val finalTitle = etFileName.text.toString().trim().ifBlank { cleanDefaultTitle }
                        val headers = StreamHeaders(referer, userAgent, cookies)
                        runCatching {
                            if (BrowserHelper.isHlsStream(url, mimetype.orEmpty())) {
                                VaultEngine.enqueueM3u8Download(this, finalTitle, url, headers)
                            } else {
                                VaultEngine.enqueueDirectVideoDownload(this, finalTitle, url, mimetype, headers)
                            }
                            Toast.makeText(this, "Downloading to Private Vault: $finalTitle", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, "Vault queue error: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val link = when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Handler(Looper.getMainLooper()).obtainMessage()
                    wv.requestFocusNodeHref(msg)
                    msg.data?.getString("url") ?: hit.extra
                }
                else -> null
            }
            if (!link.isNullOrBlank()) {
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

            @JavascriptInterface
            fun onWindowOpen(url: String) {
                runOnUiThread {
                    if (BrowserHelper.isAdUrl(url, adShieldEnabled)) {
                        Toast.makeText(this@BrowserActivity, "Blocked ad popup", Toast.LENGTH_SHORT).show()
                    } else if (url.startsWith("http://") || url.startsWith("https://")) {
                        openNewTab(url)
                    }
                }
            }

            @JavascriptInterface
            fun requestTranslate(batchId: Int, jsonArrayStr: String) {
                if (!autoTranslateEnabled) return
                val lang = translateTargetLang
                translateExecutor.execute {
                    runCatching {
                        val arr = JSONArray(jsonArrayStr)
                        val texts = (0 until arr.length()).map { arr.getString(it) }
                        val translated = translateBatchRemote(texts, lang)
                        val outArr = JSONArray()
                        translated.forEach { outArr.put(it) }
                        val jsPayload = JSONObject.quote(outArr.toString())
                        runOnUiThread {
                            wv.evaluateJavascript("if(window.__applyVaultTranslation) window.__applyVaultTranslation($batchId, $jsPayload);", null)
                        }
                    }
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
                    syncSmartTopButton()
                    pageProgress.visibility = View.VISIBLE
                    if (!url.isNullOrBlank() && !urlBar.hasFocus()) urlBar.setText(url)
                    syncNav()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (currentTab()?.id == tab.id) {
                    isPageLoading = false
                    syncSmartTopButton()
                    pageProgress.visibility = View.GONE
                    if (!url.isNullOrBlank() && !urlBar.hasFocus()) urlBar.setText(url)
                    syncNav()
                }
                view?.evaluateJavascript(BrowserHelper.HOOK_JS, null)
                if (autoTranslateEnabled) {
                    triggerAutoTranslation(view)
                }
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (!isUserGesture) {
                    if (adShieldEnabled) {
                        Toast.makeText(this@BrowserActivity, "Blocked popup", Toast.LENGTH_SHORT).show()
                    }
                    return false
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    val tempWebView = WebView(this@BrowserActivity)
                    tempWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            req: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = req?.url?.toString().orEmpty()
                            if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
                                if (BrowserHelper.isAdUrl(targetUrl, adShieldEnabled)) {
                                    Toast.makeText(this@BrowserActivity, "Blocked ad redirect", Toast.LENGTH_SHORT).show()
                                } else {
                                    openNewTab(targetUrl)
                                }
                            }
                            tempWebView.destroy()
                            return true
                        }
                    }
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }

                val hrefMsg = Handler(Looper.getMainLooper()).obtainMessage()
                view?.requestFocusNodeHref(hrefMsg)
                val linkUrl = hrefMsg.data?.getString("url").orEmpty()
                if (linkUrl.startsWith("http") && !BrowserHelper.isAdUrl(linkUrl, adShieldEnabled)) {
                    openNewTab(linkUrl)
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

    private fun startPublicFileDownload(
        url: String,
        fileName: String,
        userAgent: String,
        referer: String,
        cookies: String,
        mimetype: String?
    ) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading via M3U8Vault")
                if (!mimetype.isNullOrBlank()) setMimeType(mimetype)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
                if (referer.isNotBlank()) addRequestHeader("Referer", referer)
                if (cookies.isNotBlank()) addRequestHeader("Cookie", cookies)
                runCatching {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }.onFailure {
                    setDestinationInExternalFilesDir(this@BrowserActivity, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading file: $fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        val tab = tabs[index]
        webContainer.removeAllViews()
        webContainer.addView(tab.webView)
        urlBar.setText(tab.webView.url ?: searxngBaseUrl)
        btnTabs.text = "${tabs.size}"
        updateBadge()
        syncNav()
        syncSmartTopButton()
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
        val wv = currentTab()?.webView
        val canBack = wv?.canGoBack() == true
        val canForward = wv?.canGoForward() == true

        btnBack.isEnabled = canBack
        btnBack.setTextColor(Color.parseColor(if (canBack) "#CDD6F4" else "#45475A"))

        btnForward.isEnabled = canForward
        btnForward.setTextColor(Color.parseColor(if (canForward) "#CDD6F4" else "#45475A"))

        backCallback.isEnabled = canBack || tabs.size > 1
    }

    private fun updateBadge() {
        val count = currentTab()?.let { t -> synchronized(t.streams) { t.streams.size } } ?: 0
        if (count > 0 || isInspecting) {
            btnFloatingCatcher.visibility = View.VISIBLE
            btnFloatingCatcher.text = if (isInspecting) "⏳" else "⬇ $count"
        } else {
            btnFloatingCatcher.visibility = View.GONE
        }
    }

    private fun loadInCurrentTab(input: String) {
        if (input.isBlank()) return
        val tab = currentTab() ?: return
        synchronized(tab.streams) { tab.streams.clear() }
        tab.lastUrl = ""
        updateBadge()
        tab.webView.loadUrl(BrowserHelper.resolveInput(input, searxngBaseUrl))
    }

    private fun getSavedBookmarks(): MutableList<Pair<String, String>> {
        val prefs = getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
        val raw = prefs.getString("bookmarks_json", "[]") ?: "[]"
        val list = mutableListOf<Pair<String, String>>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.optString("title").ifBlank { obj.optString("url") }
                val url = obj.optString("url")
                if (url.isNotBlank()) list.add(title to url)
            }
        }
        return list
    }

    private fun saveBookmarksList(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((title, url) in list) {
            arr.put(JSONObject().apply {
                put("title", title)
                put("url", url)
            })
        }
        getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
            .edit()
            .putString("bookmarks_json", arr.toString())
            .apply()
    }

    private fun bookmarkCurrentPage() {
        val wv = currentTab()?.webView ?: return
        val currentUrl = wv.url?.trim().orEmpty()
        if (!currentUrl.startsWith("http")) {
            Toast.makeText(this, "Open a webpage first to bookmark it", Toast.LENGTH_SHORT).show()
            return
        }
        val defaultName = wv.title?.trim()?.take(45)?.ifBlank { currentUrl } ?: currentUrl

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val etName = EditText(this).apply {
            setText(defaultName)
            hint = "Bookmark name"
        }
        box.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("Add Bookmark")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim().ifBlank { defaultName }
                val list = getSavedBookmarks()
                list.removeAll { it.second == currentUrl }
                list.add(0, name to currentUrl)
                saveBookmarksList(list)
                Toast.makeText(this, "Bookmarked: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarksDialog() {
        val list = getSavedBookmarks()
        if (list.isEmpty()) {
            Toast.makeText(this, "No bookmarks saved yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "⭐ ${it.first}\n     ${it.second.take(42)}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Saved Bookmarks (${list.size})")
            .setItems(labels) { _, which ->
                loadInCurrentTab(list[which].second)
            }
            .setNeutralButton("Delete One") { _, _ ->
                val delLabels = list.map { "🗑 ${it.first}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Tap Bookmark to Delete")
                    .setItems(delLabels) { _, idx ->
                        val removed = list.removeAt(idx)
                        saveBookmarksList(list)
                        Toast.makeText(this, "Deleted: ${removed.first}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
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
                tvTitle.setTextColor(Color.parseColor(if (isActive) "#CBA6F7" else "#CDD6F4"))
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
        val langName = supportedLanguages.firstOrNull { it.second == translateTargetLang }?.first ?: translateTargetLang
        val items = arrayOf(
            "⌂ 4get Home",
            "+ New Window / Tab",
            "⭐ Bookmark This Page",
            "🔖 Saved Bookmarks",
            if (autoTranslateEnabled) "🌐 Auto Page Translator: ON ($langName)" else "🌐 Auto Page Translator: OFF",
            "🗣 Translator Language ($langName)",
            if (adShieldEnabled) "🛡 Ad & Redirect Shield: ON" else "🛡 Ad & Redirect Shield: OFF",
            if (desktopMode) "🖥 Desktop Site: ON" else "🖥 Desktop Site: OFF",
            "🔍 Change Search Engine",
            "🔗 Paste .m3u8 / Video URL Manually",
            "🧹 Clear Detected Streams"
        )

        AlertDialog.Builder(this)
            .setTitle("Navigation & Tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> loadInCurrentTab(searxngBaseUrl)
                    1 -> openNewTab(searxngBaseUrl)
                    2 -> bookmarkCurrentPage()
                    3 -> showBookmarksDialog()
                    4 -> {
                        autoTranslateEnabled = !autoTranslateEnabled
                        getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
                            .edit().putBoolean("auto_translate_enabled", autoTranslateEnabled).apply()
                        if (autoTranslateEnabled) {
                            triggerAutoTranslation(wv)
                            Toast.makeText(this, "Auto-Translating to $langName", Toast.LENGTH_SHORT).show()
                        } else {
                            restoreOriginalPageText(wv)
                            Toast.makeText(this, "Auto Page Translator OFF", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> {
                        val langLabels = supportedLanguages.map { it.first }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Translate Pages Into")
                            .setItems(langLabels) { _, idx ->
                                translateTargetLang = supportedLanguages[idx].second
                                autoTranslateEnabled = true
                                getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE).edit()
                                    .putString("translate_target_lang", translateTargetLang)
                                    .putBoolean("auto_translate_enabled", true)
                                    .apply()
                                triggerAutoTranslation(wv)
                                Toast.makeText(this, "Auto-Translating to ${supportedLanguages[idx].first}", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    6 -> {
                        adShieldEnabled = !adShieldEnabled
                        Toast.makeText(this, if (adShieldEnabled) "Shield ON" else "Shield OFF", Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        desktopMode = !desktopMode
                        wv?.settings?.userAgentString = if (desktopMode) BrowserHelper.DESKTOP_UA else BrowserHelper.MOBILE_UA
                        wv?.reload()
                    }
                    8 -> AlertDialog.Builder(this)
                        .setTitle("Select Search Engine")
                        .setItems(BrowserHelper.SEARXNG_INSTANCES) { _, i ->
                            searxngBaseUrl = BrowserHelper.SEARXNG_INSTANCES[i]
                            loadInCurrentTab(searxngBaseUrl)
                        }.show()
                    9 -> showManualPasteDialog()
                    10 -> {
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
        val defaultTitle = tab.webView.title?.take(40)?.ifBlank { "Vault_Video" } ?: "Vault_Video"

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 10)
        }
        val etTitle = EditText(this).apply { setText(defaultTitle); hint = "Video title" }
        val etUrl = EditText(this).apply { hint = "https://.../playlist.m3u8 or .mp4" }
        box.addView(etTitle)
        box.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("Download Direct Video / M3U8 URL")
            .setView(box)
            .setPositiveButton("Queue") { _, _ ->
                val url = etUrl.text.toString().trim()
                val name = etTitle.text.toString().trim().ifBlank { defaultTitle }
                if (url.startsWith("http")) {
                    val headers = StreamHeaders(pageUrl, ua, cookies)
                    runCatching {
                        if (BrowserHelper.isHlsStream(url)) {
                            VaultEngine.enqueueM3u8Download(this, name, url, headers)
                        } else {
                            VaultEngine.enqueueDirectVideoDownload(this, name, url, null, headers)
                        }
                        Toast.makeText(this, "Added to Vault Downloading", Toast.LENGTH_SHORT).show()
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
        updateBadge()

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
                    val q = detectQualityFromUrl(u)
                    val label = if (q != null) "$q • ${u.takeLast(28)}" else "Stream #${idx + 1} • ${u.takeLast(32)}"
                    val h = q?.removeSuffix("p")?.toIntOrNull() ?: 0
                    options.add(StreamOption(label, u, q.orEmpty(), h))
                }
            }

            val sortedOptions = options.sortedByDescending { it.sortHeight }

            runOnUiThread {
                isInspecting = false
                updateBadge()
                showCatppuccinStreamBottomSheet(sortedOptions, defaultTitle, pageUrl, ua, cookies)
            }
        }
    }

    private fun showCatppuccinStreamBottomSheet(
        options: List<StreamOption>,
        defaultTitle: String,
        pageUrl: String,
        ua: String,
        cookies: String
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var selectedIndex = 0
        val radioDots = mutableListOf<View>()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(20f), dpInt(12f), dpInt(20f), dpInt(20f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadii = floatArrayOf(dp(24f), dp(24f), dp(24f), dp(24f), 0f, 0f, 0f, 0f)
            }
        }

        // Mauve Drag Handle
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(42f), dpInt(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpInt(14f)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CBA6F7"))
                cornerRadius = dp(2f)
            }
        }
        sheet.addView(handle)

        // Header
        val tvTitle = TextView(this).apply {
            text = "Select Stream Quality"
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(14f) }
        }
        sheet.addView(tvTitle)

        // Recessed Video Name Input
        val etName = EditText(this).apply {
            setText(defaultTitle)
            hint = "Save video as..."
            setTextColor(Color.parseColor("#CDD6F4"))
            setHintTextColor(Color.parseColor("#6C7086"))
            textSize = 14f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dpInt(14f), dpInt(12f), dpInt(14f), dpInt(12f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dp(12f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(12f) }
        }
        sheet.addView(etName)

        // Quality Rows Container
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(16f) }
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun updateRadioDots() {
            radioDots.forEachIndexed { i, dot ->
                val active = i == selectedIndex
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (active) {
                        setColor(Color.parseColor("#A6E3A1"))
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dpInt(1.5f), Color.parseColor("#6C7086"))
                    }
                }
            }
        }

        options.forEachIndexed { index, opt ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpInt(6f), dpInt(13f), dpInt(6f), dpInt(13f))
            }

            val tvLabel = TextView(this).apply {
                text = opt.label
                setTextColor(Color.parseColor("#CDD6F4"))
                textSize = 15f
            }
            row.addView(tvLabel)

            if (opt.sortHeight >= 720) {
                val badge = TextView(this).apply {
                    text = if (opt.sortHeight >= 1080) "HD" else "HQ"
                    setTextColor(Color.parseColor("#11111B"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpInt(7f), dpInt(2f), dpInt(7f), dpInt(2f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#A6E3A1"))
                        cornerRadius = dp(6f)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dpInt(8f) }
                }
                row.addView(badge)
            }

            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            row.addView(spacer)

            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(18f), dpInt(18f))
            }
            radioDots.add(dot)
            row.addView(dot)

            row.setOnClickListener {
                selectedIndex = index
                updateRadioDots()
            }

            listContainer.addView(row)

            if (index < options.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#45475A"))
                }
                listContainer.addView(divider)
            }
        }
        updateRadioDots()
        scroll.addView(listContainer)
        sheet.addView(scroll)

        // Wide Mint Download Button
        val btnDownload = Button(this).apply {
            text = "Download"
            isAllCaps = false
            setTextColor(Color.parseColor("#11111B"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6E3A1"))
                cornerRadius = dp(24f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpInt(48f)
            )
            setOnClickListener {
                val picked = options[selectedIndex]
                val name = etName.text.toString().trim().ifBlank { defaultTitle }
                runCatching {
                    VaultEngine.enqueueM3u8Download(
                        this@BrowserActivity,
                        name,
                        picked.url,
                        StreamHeaders(pageUrl, ua, cookies)
                    )
                    Toast.makeText(this@BrowserActivity, "Downloading: $name", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@BrowserActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
        }
        sheet.addView(btnDownload)

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun detectQualityFromUrl(url: String): String? {
        val clean = url.lowercase().substringBefore("?").substringBefore("#")
        Regex("(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240)p(?:[^a-z0-9]|$)").find(clean)?.let {
            return "${it.groupValues[1]}p"
        }
        Regex("[/_\\-.](2160|1440|1080|720|540|480|360|240)[/_\\-.]").find(clean)?.let {
            return "${it.groupValues[1]}p"
        }
        return null
    }

    private fun estimateQualityFromKbps(kbps: Long): Pair<String, Int> = when {
        kbps >= 4500 -> "~1080p" to 1080
        kbps >= 2000 -> "~720p" to 720
        kbps >= 900 -> "~480p" to 480
        kbps >= 450 -> "~360p" to 360
        else -> "~240p" to 240
    }

    private fun fetchManifestText(targetUrl: String, referer: String, ua: String, cookies: String): String {
        var currentUrl = targetUrl
        val origin = runCatching {
            val u = URI(referer)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else ""
        }.getOrDefault("")

        repeat(4) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "*/*")
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                if (origin.isNotBlank()) setRequestProperty("Origin", origin)
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (loc.isBlank()) return ""
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return text
            }
        }
        return ""
    }

    private fun inspectM3u8(m3u8Url: String, referer: String, ua: String, cookies: String): List<StreamOption> {
        val results = mutableListOf<StreamOption>()
        try {
            val body = fetchManifestText(m3u8Url, referer, ua, cookies)
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                var pendingTag = ""
                var pendingKbps: Long? = null
                var pendingHeight = 0

                for (line in lines) {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = Regex("RESOLUTION\\s*=\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(line)
                        val nameMatch = Regex("NAME\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)
                        val bwMatch = Regex("BANDWIDTH\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(line)

                        val w = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val h = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                        val minDim = if (w != null && h != null) minOf(w, h) else h
                        pendingKbps = bwMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.div(1000)

                        if (minDim != null && minDim > 0) {
                            pendingTag = "${minDim}p"
                            pendingHeight = minDim
                        } else if (nameMatch != null) {
                            pendingTag = nameMatch.groupValues[1]
                            pendingHeight = Regex("(\\d{3,4})").find(pendingTag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        } else {
                            pendingTag = ""
                            pendingHeight = 0
                        }
                    } else if (!line.startsWith("#")) {
                        val resolved = runCatching { URL(URL(m3u8Url), line).toString() }.getOrDefault(line)
                        val urlQuality = detectQualityFromUrl(resolved)
                        val qualityText = when {
                            pendingTag.isNotBlank() -> pendingTag
                            urlQuality != null -> {
                                pendingHeight = urlQuality.removeSuffix("p").toIntOrNull() ?: 0
                                urlQuality
                            }
                            pendingKbps != null -> {
                                val (est, estH) = estimateQualityFromKbps(pendingKbps!!)
                                pendingHeight = estH
                                est
                            }
                            else -> "Variant"
                        }
                        val fullLabel = if (pendingKbps != null) "$qualityText (${pendingKbps} kbps)" else qualityText
                        results.add(StreamOption(fullLabel, resolved, qualityText, pendingHeight))
                        pendingTag = ""
                        pendingKbps = null
                        pendingHeight = 0
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
                val urlQuality = detectQualityFromUrl(m3u8Url)
                val height = urlQuality?.removeSuffix("p")?.toIntOrNull() ?: 0
                val qPrefix = if (urlQuality != null) "$urlQuality • " else ""

                val tag = if (totalSeconds in 0.1..15.0) {
                    "⚠ ${qPrefix}Short Clip (${secs}s)"
                } else if (totalSeconds > 0.0) {
                    "${qPrefix}HLS (${mins}m ${secs}s)"
                } else {
                    "${qPrefix}Stream • ${m3u8Url.takeLast(24)}"
                }
                results.add(StreamOption(tag, m3u8Url, urlQuality.orEmpty(), height))
            }
        } catch (_: Exception) {
            val urlQuality = detectQualityFromUrl(m3u8Url)
            val height = urlQuality?.removeSuffix("p")?.toIntOrNull() ?: 0
            val label = if (urlQuality != null) "$urlQuality • ${m3u8Url.takeLast(26)}" else "Direct Stream • ${m3u8Url.takeLast(30)}"
            results.add(StreamOption(label, m3u8Url, urlQuality.orEmpty(), height))
        }
        return results
    }

    override fun onDestroy() {
        inspectExecutor.shutdownNow()
        translateExecutor.shutdownNow()
        tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
// END OF FILE
