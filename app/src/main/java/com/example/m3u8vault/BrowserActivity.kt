package com.example.m3u8vault

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.*
import android.content.pm.ActivityInfo
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
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(UnstableApi::class)
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
    private lateinit var btnReload: Button
    private lateinit var btnTabs: TextView
    private lateinit var btnPopupPlayer: Button
    private lateinit var btnFloatingCatcher: Button

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var isPageLoading = false
    private var adShieldEnabled = true
    private var blockThirdPartyScripts = true
    private var desktopMode = false
    private var autoTranslateEnabled = false
    private var translateTargetLang = "en"
    private var searxngBaseUrl = "https://lite.duckduckgo.com/lite/"
    private var isInspecting = false
    private val inspectExecutor = Executors.newSingleThreadExecutor()
    private val translateExecutor = Executors.newFixedThreadPool(3)

    // Floating Ad-Free Pop-up Player state
    private var popupContainer: LinearLayout? = null
    private var popupExoPlayer: ExoPlayer? = null
    private var popupExpanded = false
    private var popupSpeedIndex = 0
    private var collapsePopupFn: (() -> Unit)? = null
    private val playbackSpeeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    private val searchEngines = listOf(
        "DuckDuckGo Lite (No CAPTCHA)" to "https://lite.duckduckgo.com/lite/",
        "Mojeek (No CAPTCHA)" to "https://www.mojeek.com/",
        "Brave Search" to "https://search.brave.com/",
        "4get.ca" to "https://4get.ca"
    )

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
            if (popupExpanded) {
                collapsePopupFn?.invoke()
                return
            }
            if (popupContainer != null) {
                closePopupVideoPlayer()
                return
            }
            val current = currentTab() ?: return
            if (current.webView.canGoBack()) current.webView.goBack()
            else if (tabs.size > 1) closeTab(activeTabIndex)
            syncNav()
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
        adShieldEnabled = prefs.getBoolean("ad_shield_enabled", true)
        blockThirdPartyScripts = prefs.getBoolean("block_third_party_scripts", true)
        searxngBaseUrl = prefs.getString("default_search_url", "https://lite.duckduckgo.com/lite/")
            ?: "https://lite.duckduckgo.com/lite/"

        canvasWrapper = findViewById(R.id.canvasWrapper)
        webContainer = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        pageProgress = findViewById(R.id.pageProgress)
        btnReload = findViewById(R.id.btnReload)
        btnTabs = findViewById(R.id.btnTabs)
        btnPopupPlayer = findViewById(R.id.btnPopupPlayer)
        btnFloatingCatcher = findViewById(R.id.btnFloatingCatcher)

        applyCatppuccinMochaShapes()
        onBackPressedDispatcher.addCallback(this, backCallback)
        setupListeners()
        setupFloatingPills()
        openNewTab(searxngBaseUrl)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dpInt(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyCatppuccinMochaShapes() {
        findViewById<View>(R.id.searchPillContainer).background = GradientDrawable().apply {
            setColor(Color.parseColor("#313244"))
            cornerRadius = dp(20f)
        }

        btnPopupPlayer.backgroundTintList = null
        btnPopupPlayer.background = GradientDrawable().apply {
            setColor(Color.parseColor("#CBA6F7"))
            cornerRadius = dp(19f)
        }

        btnFloatingCatcher.backgroundTintList = null
        btnFloatingCatcher.background = GradientDrawable().apply {
            setColor(Color.parseColor("#A6E3A1"))
            cornerRadius = dp(20f)
        }

        btnTabs.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(5f)
            setStroke(dpInt(1.5f), Color.parseColor("#CDD6F4"))
        }

        btnReload.backgroundTintList = null
        findViewById<Button>(R.id.btnVault).backgroundTintList = null
        findViewById<Button>(R.id.btnSettings).backgroundTintList = null
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

        val tabSlot = findViewById<View>(R.id.tabSlot)
        tabSlot.setOnClickListener { showTabSwitcher() }
        tabSlot.setOnLongClickListener {
            openNewTab(searxngBaseUrl)
            Toast.makeText(this, "New tab opened", Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<Button>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultPlayerActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showCatppuccinSettingsScreen()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingPills() {
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
                    if (!moved) openStreamAction(openAsPopup = false)
                    true
                }
                else -> false
            }
        }

        btnPopupPlayer.setOnClickListener {
            openStreamAction(openAsPopup = true)
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

    private fun isDirectPlayableVideoUrl(url: String, accept: String = ""): Boolean {
        if (BrowserHelper.isHlsStream(url, accept)) return true
        val lower = url.lowercase().substringBefore("?").substringBefore("#")
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m4v")
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

    private fun resolveSearchOrUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (!trimmed.contains(" ") && trimmed.contains(".") && !trimmed.startsWith(".")) {
            return "https://$trimmed"
        }
        val q = URLEncoder.encode(trimmed, "UTF-8")
        return when {
            searxngBaseUrl.contains("duckduckgo.com") -> "https://lite.duckduckgo.com/lite/?q=$q"
            searxngBaseUrl.contains("mojeek.com") -> "https://www.mojeek.com/search?q=$q"
            searxngBaseUrl.contains("brave.com") -> "https://search.brave.com/search?q=$q"
            else -> BrowserHelper.resolveInput(trimmed, searxngBaseUrl)
        }
    }

    private fun injectVideoTagObserver(webView: WebView?) {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vaultVideoObserverAttached) return;
                window.__vaultVideoObserverAttached = true;
                function reportVideos() {
                    document.querySelectorAll('video, source').forEach(el => {
                        const src = el.currentSrc || el.src || el.getAttribute('src');
                        if (src && src.startsWith('http') && !src.startsWith('blob:')) {
                            VaultSniffer.onVideoUrlDetected(src);
                        }
                    });
                }
                reportVideos();
                document.addEventListener('play', reportVideos, true);
                setInterval(reportVideos, 2500);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun triggerAutoTranslation(webView: WebView?, resetExisting: Boolean = false) {
        if (!autoTranslateEnabled || webView == null) return
        val resetFlag = if (resetExisting) "true" else "false"
        val script = """
            (function() {
                window.__vaultAutoTranslateActive = true;
                if (!window.__vaultNodeMap) window.__vaultNodeMap = new Map();
                if (!window.__vaultNextId) window.__vaultNextId = 1;

                if ($resetFlag) {
                    window.__vaultNodeMap.forEach((entry) => {
                        if (entry.type === 'text') {
                            entry.node.__vaultTranslated = false;
                            entry.node.nodeValue = entry.orig;
                        } else if (entry.type === 'placeholder') {
                            entry.node.__vaultPhTranslated = false;
                            entry.node.setAttribute('placeholder', entry.orig);
                        }
                    });
                    window.__vaultNodeMap.clear();
                }

                const skipTags = new Set(['SCRIPT','STYLE','NOSCRIPT','CODE','PRE','TEXTAREA','SVG','CANVAS','IFRAME']);

                function isTranslatableText(str) {
                    if (!str) return false;
                    const t = str.trim();
                    if (!t) return false;
                    const hasNonAsciiLetter = /[\u00C0-\u024F\u0400-\u04FF\u0600-\u06FF\u0900-\u0D7F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/.test(t);
                    if (!hasNonAsciiLetter && t.length < 2) return false;
                    if (/^[0-9\s\W_]+$/.test(t)) return false;
                    if (/^https?:\/\//i.test(t)) return false;
                    return true;
                }

                function scanAndQueue() {
                    if (!window.__vaultAutoTranslateActive) return;
                    const root = document.body || document.documentElement;
                    if (!root) return;
                    const pending = [];

                    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            const p = node.parentElement;
                            if (!p || skipTags.has(p.tagName)) return NodeFilter.FILTER_REJECT;
                            if (p.isContentEditable) return NodeFilter.FILTER_REJECT;
                            if (node.__vaultTranslated) return NodeFilter.FILTER_REJECT;
                            if (!isTranslatableText(node.nodeValue)) return NodeFilter.FILTER_REJECT;
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    });

                    let n;
                    while ((n = walker.nextNode())) {
                        n.__vaultTranslated = true;
                        if (n.__vaultOrig === undefined) n.__vaultOrig = n.nodeValue;
                        const id = window.__vaultNextId++;
                        window.__vaultNodeMap.set(id, { type: 'text', node: n, orig: n.__vaultOrig });
                        pending.push({ id: id, text: n.__vaultOrig.trim() });
                    }

                    const inputs = document.querySelectorAll('input[placeholder], textarea[placeholder]');
                    for (const inp of inputs) {
                        if (inp.__vaultPhTranslated) continue;
                        const ph = inp.getAttribute('placeholder');
                        if (isTranslatableText(ph)) {
                            inp.__vaultPhTranslated = true;
                            const id = window.__vaultNextId++;
                            window.__vaultNodeMap.set(id, { type: 'placeholder', node: inp, orig: ph });
                            pending.push({ id: id, text: ph.trim() });
                        }
                    }

                    if (!pending.length) return;

                    let batch = [];
                    let charCount = 0;
                    const sendBatch = () => {
                        if (!batch.length) return;
                        VaultSniffer.requestTranslateBatch(JSON.stringify(batch));
                        batch = [];
                        charCount = 0;
                    };

                    for (const item of pending) {
                        if (charCount + item.text.length > 1800 || batch.length >= 45) {
                            sendBatch();
                        }
                        batch.push(item);
                        charCount += item.text.length + 10;
                    }
                    sendBatch();
                }

                window.__applyVaultTranslationMap = function(resultJson) {
                    try {
                        const mapObj = JSON.parse(resultJson);
                        for (const key in mapObj) {
                            const id = parseInt(key, 10);
                            const entry = window.__vaultNodeMap.get(id);
                            const translated = mapObj[key];
                            if (!entry || !translated) continue;
                            if (entry.type === 'text') {
                                const orig = entry.orig;
                                const lead = orig.match(/^\s*/)[0];
                                const trail = orig.match(/\s*$/)[0];
                                entry.node.nodeValue = lead + translated.trim() + trail;
                            } else if (entry.type === 'placeholder') {
                                entry.node.setAttribute('placeholder', translated.trim());
                            }
                        }
                    } catch (e) {}
                };

                scanAndQueue();

                if (!window.__vaultObserver && document.body) {
                    let timer = null;
                    window.__vaultObserver = new MutationObserver(() => {
                        if (!window.__vaultAutoTranslateActive) return;
                        clearTimeout(timer);
                        timer = setTimeout(scanAndQueue, 350);
                    });
                    window.__vaultObserver.observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun restoreOriginalPageText(webView: WebView?) {
        webView?.evaluateJavascript(
            """
            (function() {
                window.__vaultAutoTranslateActive = false;
                if (window.__vaultNodeMap) {
                    window.__vaultNodeMap.forEach((entry) => {
                        if (entry.type === 'text') {
                            entry.node.__vaultTranslated = false;
                            entry.node.nodeValue = entry.orig;
                        } else if (entry.type === 'placeholder') {
                            entry.node.__vaultPhTranslated = false;
                            entry.node.setAttribute('placeholder', entry.orig);
                        }
                    });
                    window.__vaultNodeMap.clear();
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun translateBatchRemote(items: List<Pair<Int, String>>, targetLang: String): Map<Int, String> {
        if (items.isEmpty()) return emptyMap()
        val payloadText = items.joinToString("\n") { (id, txt) ->
            "[#$id] ${txt.replace("\n", " ")}"
        }
        val postBody = "q=" + URLEncoder.encode(payloadText, "UTF-8")
        val apiUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t"

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", BrowserHelper.MOBILE_UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.use { os ->
            os.write(postBody.toByteArray(Charsets.UTF_8))
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()

        val root = JSONArray(body)
        val sentences = root.getJSONArray(0)
        val fullTranslated = StringBuilder()
        for (i in 0 until sentences.length()) {
            fullTranslated.append(sentences.getJSONArray(i).optString(0))
        }

        val result = mutableMapOf<Int, String>()
        val regex = Regex("\\[#\\s*(\\d+)\\s*\\](.*?)(?=\\[#\\s*\\d+\\s*\\]|$)", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(fullTranslated.toString())) {
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val translatedText = match.groupValues[2].trim()
            if (translatedText.isNotEmpty()) {
                result[id] = translatedText
            }
        }
        return result
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
        var downX = 0f
        var downY = 0f
        var lastTapMs = 0L

        wv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < dp(10f) && abs(event.rawY - downY) < dp(10f)) {
                        lastTapMs = System.currentTimeMillis()
                    }
                }
            }
            false
        }

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
                if (isDirectPlayableVideoUrl(url)) {
                    synchronized(tab.streams) {
                        tab.streams.remove(url)
                        tab.streams.add(url)
                    }
                    if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
                }
            }

            @JavascriptInterface
            fun onVideoUrlDetected(url: String) {
                if (isDirectPlayableVideoUrl(url)) {
                    synchronized(tab.streams) {
                        if (!tab.streams.contains(url)) {
                            tab.streams.add(url)
                            if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
                        }
                    }
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
            fun requestTranslateBatch(batchJsonStr: String) {
                if (!autoTranslateEnabled) return
                val lang = translateTargetLang
                translateExecutor.execute {
                    runCatching {
                        val arr = JSONArray(batchJsonStr)
                        val items = mutableListOf<Pair<Int, String>>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            items.add(obj.getInt("id") to obj.getString("text"))
                        }
                        val translatedMap = translateBatchRemote(items, lang)
                        if (translatedMap.isNotEmpty()) {
                            val outObj = JSONObject()
                            for ((id, text) in translatedMap) {
                                outObj.put(id.toString(), text)
                            }
                            val jsPayload = JSONObject.quote(outObj.toString())
                            runOnUiThread {
                                wv.evaluateJavascript(
                                    "if(window.__applyVaultTranslationMap) window.__applyVaultTranslationMap($jsPayload);",
                                    null
                                )
                            }
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
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "Blocked ad redirect", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                if (adShieldEnabled) {
                    val currentUrl = view?.url.orEmpty().ifBlank { tab.lastUrl }
                    val currentHost = BrowserHelper.extractHost(currentUrl)
                    val targetHost = BrowserHelper.extractHost(url)
                    val isOffOrigin = currentHost.isNotBlank() &&
                        targetHost.isNotBlank() &&
                        !BrowserHelper.isSameSite(currentHost, targetHost)
                    val hasRecentTap = (System.currentTimeMillis() - lastTapMs) <= 1500L

                    if (isOffOrigin && !hasRecentTap) {
                        runOnUiThread {
                            Toast.makeText(this@BrowserActivity, "Blocked scroll/auto redirect", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                val headers = request?.requestHeaders.orEmpty()
                val accept = headers["Accept"] ?: headers["accept"] ?: ""

                if (isDirectPlayableVideoUrl(url, accept)) {
                    synchronized(tab.streams) {
                        tab.streams.remove(url)
                        tab.streams.add(url)
                    }
                    if (currentTab()?.id == tab.id) runOnUiThread { updateBadge() }
                }

                val blocked = BrowserHelper.shouldBlockRequest(
                    url = url,
                    shieldEnabled = adShieldEnabled,
                    currentPageUrl = tab.lastUrl,
                    blockThirdPartyScripts = blockThirdPartyScripts,
                    headers = headers
                )
                if (blocked) return BrowserHelper.emptyResponse()

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
                view?.evaluateJavascript(BrowserHelper.HOOK_JS, null)
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
                injectVideoTagObserver(view)
                if (autoTranslateEnabled) {
                    triggerAutoTranslation(view, resetExisting = false)
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
        backCallback.isEnabled = popupExpanded || popupContainer != null || canBack || tabs.size > 1
    }

    private fun updateBadge() {
        val count = currentTab()?.let { t -> synchronized(t.streams) { t.streams.size } } ?: 0
        if (count > 0 || isInspecting) {
            btnFloatingCatcher.visibility = View.VISIBLE
            btnFloatingCatcher.text = if (isInspecting) "⏳" else "⬇ $count"
            btnPopupPlayer.visibility = if (count > 0 && !isInspecting) View.VISIBLE else View.GONE
        } else {
            btnFloatingCatcher.visibility = View.GONE
            btnPopupPlayer.visibility = View.GONE
        }
    }

    private fun loadInCurrentTab(input: String) {
        if (input.isBlank()) return
        val tab = currentTab() ?: return
        synchronized(tab.streams) { tab.streams.clear() }
        tab.lastUrl = ""
        updateBadge()
        tab.webView.loadUrl(resolveSearchOrUrl(input))
    }

    // =========================================================================
    // CATPPUCCIN MOCHA FULL-SCREEN SETTINGS (GROUPED SLATE CARDS)
    // =========================================================================

    private fun createMintSwitch(checked: Boolean, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            isChecked = checked
            thumbDrawable?.setTint(if (checked) Color.parseColor("#11111B") else Color.parseColor("#CDD6F4"))
            trackDrawable?.setTint(if (checked) Color.parseColor("#A6E3A1") else Color.parseColor("#45475A"))
            setOnCheckedChangeListener { _, isChecked ->
                thumbDrawable?.setTint(if (isChecked) Color.parseColor("#11111B") else Color.parseColor("#CDD6F4"))
                trackDrawable?.setTint(if (isChecked) Color.parseColor("#A6E3A1") else Color.parseColor("#45475A"))
                onToggle(isChecked)
            }
        }
    }

    private fun showCatppuccinSettingsScreen() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val prefs = getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
        val wv = currentTab()?.webView

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(8f), dpInt(8f), dpInt(16f), dpInt(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpInt(54f)
            )
        }
        val btnClose = Button(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.parseColor("#CDD6F4"))
            backgroundTintList = null
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpInt(42f), dpInt(42f))
            setOnClickListener { dialog.dismiss() }
        }
        val tvHeader = TextView(this).apply {
            text = "Settings"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            setPadding(dpInt(6f), 0, 0, 0)
        }
        topBar.addView(btnClose)
        topBar.addView(tvHeader)
        root.addView(topBar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(16f), dpInt(6f), dpInt(16f), dpInt(28f))
        }

        fun addSectionTitle(title: String) {
            val tv = TextView(this).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBA6F7"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpInt(14f)
                    bottomMargin = dpInt(8f)
                    marginStart = dpInt(4f)
                }
            }
            body.addView(tv)
        }

        fun createCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dp(16f)
            }
        }

        fun addDivider(card: LinearLayout) {
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dpInt(16f)
                    marginEnd = dpInt(16f)
                }
                setBackgroundColor(Color.parseColor("#45475A"))
            })
        }

        fun createRow(label: String, rightView: View? = null, valueText: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpInt(16f), dpInt(14f), dpInt(16f), dpInt(14f))
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#CDD6F4"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvLabel)

            if (valueText != null) {
                val tvVal = TextView(this).apply {
                    text = valueText
                    textSize = 13f
                    setTextColor(Color.parseColor("#A6ADC8"))
                }
                row.addView(tvVal)
            }
            if (rightView != null) {
                row.addView(rightView)
            }
            if (onClick != null) {
                row.setOnClickListener { onClick() }
            }
            return row
        }

        // --- SECTION 1: PRIVACY & VAULT ---
        addSectionTitle("Privacy & Vault")
        val vaultCard = createCard()
        val pinEnabled = prefs.getBoolean("vault_pin_enabled", false)
        val savedPin = prefs.getString("vault_pin", "").orEmpty()

        val swVaultPin = createMintSwitch(pinEnabled) { enabled ->
            if (enabled) {
                if (savedPin.length < 4) {
                    promptSetOptionalPin(prefs) { ok ->
                        if (!ok) {
                            prefs.edit().putBoolean("vault_pin_enabled", false).apply()
                            dialog.dismiss()
                            showCatppuccinSettingsScreen()
                        }
                    }
                } else {
                    prefs.edit().putBoolean("vault_pin_enabled", true).apply()
                    Toast.makeText(this, "Vault PIN Lock Enabled", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean("vault_pin_enabled", false).apply()
                Toast.makeText(this, "Vault PIN Lock Disabled", Toast.LENGTH_SHORT).show()
            }
        }
        vaultCard.addView(createRow("Vault PIN Lock (Optional)", rightView = swVaultPin))
        addDivider(vaultCard)
        vaultCard.addView(createRow("Set / Change 4-Digit PIN", valueText = "›") {
            promptSetOptionalPin(prefs) {}
        })
        body.addView(vaultCard)

        // --- SECTION 2: BROWSER ---
        addSectionTitle("Browser")
        val browserCard = createCard()

        val swAdShield = createMintSwitch(adShieldEnabled) { enabled ->
            adShieldEnabled = enabled
            prefs.edit().putBoolean("ad_shield_enabled", enabled).apply()
        }
        browserCard.addView(createRow("Ad & Popup Shield", rightView = swAdShield))
        addDivider(browserCard)

        val swThirdPartyScripts = createMintSwitch(blockThirdPartyScripts) { enabled ->
            blockThirdPartyScripts = enabled
            prefs.edit().putBoolean("block_third_party_scripts", enabled).apply()
            wv?.reload()
        }
        browserCard.addView(createRow("Block 3rd-Party Scripts", rightView = swThirdPartyScripts))
        addDivider(browserCard)

        val currentEngineName = searchEngines.firstOrNull { it.second == searxngBaseUrl }?.first
            ?.substringBefore(" (") ?: searxngBaseUrl.removePrefix("https://").substringBefore("/")
        browserCard.addView(createRow("Search Engine", valueText = "$currentEngineName  ›") {
            val combined = searchEngines + BrowserHelper.SEARXNG_INSTANCES
                .filterNot { url -> searchEngines.any { it.second == url } }
                .map { it to it }
            val labels = combined.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Search Engine")
                .setItems(labels) { _, i ->
                    searxngBaseUrl = combined[i].second
                    prefs.edit().putString("default_search_url", searxngBaseUrl).apply()
                    loadInCurrentTab(searxngBaseUrl)
                    dialog.dismiss()
                }.show()
        })
        addDivider(browserCard)

        val swDesktop = createMintSwitch(desktopMode) { enabled ->
            desktopMode = enabled
            wv?.settings?.userAgentString = if (desktopMode) BrowserHelper.DESKTOP_UA else BrowserHelper.MOBILE_UA
            wv?.reload()
        }
        browserCard.addView(createRow("Desktop Site", rightView = swDesktop))
        addDivider(browserCard)

        browserCard.addView(createRow("Bookmark Current Page", valueText = "⭐") {
            dialog.dismiss()
            bookmarkCurrentPage()
        })
        addDivider(browserCard)

        browserCard.addView(createRow("Saved Bookmarks", valueText = "›") {
            dialog.dismiss()
            showBookmarksDialog()
        })
        addDivider(browserCard)

        browserCard.addView(createRow("Paste .m3u8 / Video URL", valueText = "›") {
            dialog.dismiss()
            showManualPasteDialog()
        })
        body.addView(browserCard)

        // --- SECTION 3: TRANSLATOR ---
        addSectionTitle("Translator")
        val transCard = createCard()
        val langName = supportedLanguages.firstOrNull { it.second == translateTargetLang }?.first
            ?.substringBefore(" (") ?: translateTargetLang

        val swAutoTrans = createMintSwitch(autoTranslateEnabled) { enabled ->
            autoTranslateEnabled = enabled
            prefs.edit().putBoolean("auto_translate_enabled", enabled).apply()
            if (enabled) {
                triggerAutoTranslation(wv, resetExisting = false)
            } else {
                restoreOriginalPageText(wv)
            }
        }
        transCard.addView(createRow("Auto Page Translator", rightView = swAutoTrans))
        addDivider(transCard)

        transCard.addView(createRow("Target Language", valueText = "$langName  ›") {
            val langLabels = supportedLanguages.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Translate Pages Into")
                .setItems(langLabels) { _, idx ->
                    translateTargetLang = supportedLanguages[idx].second
                    autoTranslateEnabled = true
                    prefs.edit()
                        .putString("translate_target_lang", translateTargetLang)
                        .putBoolean("auto_translate_enabled", true)
                        .apply()
                    triggerAutoTranslation(wv, resetExisting = true)
                    dialog.dismiss()
                    Toast.makeText(this, "Translating to ${supportedLanguages[idx].first}", Toast.LENGTH_SHORT).show()
                }.show()
        })
        addDivider(transCard)

        transCard.addView(createRow("Translate Current Page Now", valueText = "🔄") {
            autoTranslateEnabled = true
            prefs.edit().putBoolean("auto_translate_enabled", true).apply()
            triggerAutoTranslation(wv, resetExisting = true)
            dialog.dismiss()
            Toast.makeText(this, "Translating page...", Toast.LENGTH_SHORT).show()
        })
        body.addView(transCard)

        scroll.addView(body)
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun promptSetOptionalPin(prefs: SharedPreferences, onDone: (Boolean) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val etPin = EditText(this).apply {
            hint = "Enter 4-digit PIN (e.g. 0000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        box.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Set Optional Vault PIN")
            .setView(box)
            .setPositiveButton("Save PIN") { _, _ ->
                val pin = etPin.text.toString().trim()
                if (pin.length == 4) {
                    prefs.edit()
                        .putString("vault_pin", pin)
                        .putBoolean("vault_pin_enabled", true)
                        .apply()
                    Toast.makeText(this, "Vault PIN Saved & Enabled", Toast.LENGTH_SHORT).show()
                    onDone(true)
                } else {
                    Toast.makeText(this, "Please enter exactly 4 digits", Toast.LENGTH_SHORT).show()
                    onDone(false)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onDone(false) }
            .show()
    }

    // =========================================================================
    // UC / COC COC STYLE AD-FREE POP-UP VIDEO PLAYER
    // =========================================================================

    private fun openStreamAction(openAsPopup: Boolean) {
        if (isInspecting) return
        val tab = currentTab() ?: return
        val rawUrls = synchronized(tab.streams) { tab.streams.toList().reversed() }
        if (rawUrls.isEmpty()) {
            Toast.makeText(this, "No video streams detected yet. Play the video first.", Toast.LENGTH_SHORT).show()
            return
        }

        val pageUrl = tab.webView.url.orEmpty()
        val ua = runCatching { tab.webView.settings.userAgentString }.getOrDefault(BrowserHelper.MOBILE_UA)
        val cookies = safeCookies(pageUrl)
        val defaultTitle = tab.webView.title?.take(40)?.ifBlank { "Vault_Video" } ?: "Vault_Video"

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
                if (openAsPopup) {
                    showAdFreePopupVideoPlayer(sortedOptions, 0, defaultTitle, pageUrl, ua, cookies)
                } else {
                    showCatppuccinStreamBottomSheet(sortedOptions, defaultTitle, pageUrl, ua, cookies)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAdFreePopupVideoPlayer(
        options: List<StreamOption>,
        initialIndex: Int,
        videoTitle: String,
        pageUrl: String,
        ua: String,
        cookies: String
    ) {
        closePopupVideoPlayer()
        if (options.isEmpty()) return

        currentTab()?.webView?.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(m => m.pause());",
            null
        )

        var currentIndex = initialIndex.coerceIn(0, options.lastIndex)
        popupSpeedIndex = 0
        popupExpanded = false

        val headersMap = mutableMapOf<String, String>()
        if (ua.isNotBlank()) headersMap["User-Agent"] = ua
        if (pageUrl.isNotBlank()) {
            headersMap["Referer"] = pageUrl
            runCatching {
                val u = URI(pageUrl)
                if (u.scheme != null && u.host != null) {
                    headersMap["Origin"] = "${u.scheme}://${u.host}"
                }
            }
        }
        if (cookies.isNotBlank()) headersMap["Cookie"] = cookies

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(headersMap)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        popupExoPlayer = player

        val cardWidth = (resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels) * 0.90f).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(12f)
            setPadding(dpInt(10f), dpInt(8f), dpInt(10f), dpInt(10f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dp(18f)
                setStroke(dpInt(1.5f), Color.parseColor("#CBA6F7"))
            }
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpInt(6f), dpInt(2f), dpInt(4f), dpInt(6f))
        }

        val tvPopupTitle = TextView(this).apply {
            text = "⚡ $videoTitle"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topHeader.addView(tvPopupTitle)

        val playerView = PlayerView(this).apply {
            this.player = player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.parseColor("#11111B"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpInt(185f)
            )
        }

        lateinit var btnExpand: Button
        fun setPopupFullscreenState(expand: Boolean) {
            popupExpanded = expand
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            val decorView = window.decorView as FrameLayout
            (card.parent as? ViewGroup)?.removeView(card)

            card.translationX = 0f
            card.translationY = 0f

            if (expand) {
                btnExpand.text = "🗗"
                card.setPadding(dpInt(12f), dpInt(8f), dpInt(12f), dpInt(8f))
                card.background = ColorDrawable(Color.parseColor("#11111B"))
                card.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                playerView.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                decorView.addView(card)

                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                btnExpand.text = "⛶"
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insetsController.show(WindowInsetsCompat.Type.systemBars())

                card.setPadding(dpInt(10f), dpInt(8f), dpInt(10f), dpInt(10f))
                card.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#313244"))
                    cornerRadius = dp(18f)
                    setStroke(dpInt(1.5f), Color.parseColor("#CBA6F7"))
                }
                card.layoutParams = FrameLayout.LayoutParams(
                    cardWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
                playerView.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpInt(185f)
                )
                canvasWrapper.addView(card)
            }
            syncNav()
        }

        collapsePopupFn = { setPopupFullscreenState(false) }

        btnExpand = Button(this).apply {
            text = "⛶"
            textSize = 14f
            setTextColor(Color.parseColor("#CDD6F4"))
            backgroundTintList = null
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpInt(36f), dpInt(32f))
            setOnClickListener { setPopupFullscreenState(!popupExpanded) }
        }
        topHeader.addView(btnExpand)

        val btnClosePopup = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#F38BA8"))
            backgroundTintList = null
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpInt(36f), dpInt(32f))
            setOnClickListener { closePopupVideoPlayer() }
        }
        topHeader.addView(btnClosePopup)
        card.addView(topHeader)
        card.addView(playerView)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(8f), 0, 0)
        }

        val btnSpeed = Button(this).apply {
            text = "1.0x"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#CDD6F4"))
            backgroundTintList = null
            setPadding(dpInt(12f), 0, dpInt(12f), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dp(14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpInt(34f)
            ).apply { marginEnd = dpInt(8f) }
            setOnClickListener {
                popupSpeedIndex = (popupSpeedIndex + 1) % playbackSpeeds.size
                val spd = playbackSpeeds[popupSpeedIndex]
                player.playbackParameters = PlaybackParameters(spd)
                text = "${spd}x"
            }
        }
        bottomRow.addView(btnSpeed)

        val btnQuality = Button(this).apply {
            text = options[currentIndex].qualityBadge.ifBlank { "Auto" }
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#CBA6F7"))
            backgroundTintList = null
            setPadding(dpInt(12f), 0, dpInt(12f), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dp(14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpInt(34f)
            ).apply { marginEnd = dpInt(8f) }
            visibility = if (options.size > 1) View.VISIBLE else View.GONE
            setOnClickListener {
                val labels = options.map { it.label }.toTypedArray()
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("Switch Pop-up Quality")
                    .setItems(labels) { _, idx ->
                        currentIndex = idx
                        val pos = player.currentPosition
                        text = options[idx].qualityBadge.ifBlank { "Auto" }
                        player.setMediaItem(MediaItem.fromUri(options[idx].url))
                        player.prepare()
                        if (pos > 0L) player.seekTo(pos)
                        player.playWhenReady = true
                    }.show()
            }
        }
        bottomRow.addView(btnQuality)

        bottomRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        val btnSaveVault = Button(this).apply {
            text = "⬇ Save to Vault"
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#11111B"))
            backgroundTintList = null
            setPadding(dpInt(14f), 0, dpInt(14f), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6E3A1"))
                cornerRadius = dp(16f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpInt(34f)
            )
            setOnClickListener {
                val picked = options[currentIndex]
                runCatching {
                    VaultEngine.enqueueM3u8Download(
                        this@BrowserActivity,
                        videoTitle,
                        picked.url,
                        StreamHeaders(pageUrl, ua, cookies)
                    )
                    Toast.makeText(this@BrowserActivity, "Downloading to Vault: $videoTitle", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@BrowserActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        bottomRow.addView(btnSaveVault)
        card.addView(bottomRow)

        var startRawX = 0f
        var startRawY = 0f
        var startTransX = 0f
        var startTransY = 0f
        topHeader.setOnTouchListener { _, event ->
            if (popupExpanded) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startTransX = card.translationX
                    startTransY = card.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxOffsetX = ((canvasWrapper.width - card.width) / 2f).coerceAtLeast(0f)
                    val maxOffsetY = ((canvasWrapper.height - card.height) / 2f).coerceAtLeast(0f)
                    card.translationX = (startTransX + (event.rawX - startRawX)).coerceIn(-maxOffsetX, maxOffsetX)
                    card.translationY = (startTransY + (event.rawY - startRawY)).coerceIn(-maxOffsetY, maxOffsetY)
                    true
                }
                else -> false
            }
        }

        popupContainer = card
        canvasWrapper.addView(card)
        syncNav()

        player.setMediaItem(MediaItem.fromUri(options[currentIndex].url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun closePopupVideoPlayer() {
        if (popupExpanded) {
            popupExpanded = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        collapsePopupFn = null
        popupExoPlayer?.release()
        popupExoPlayer = null
        popupContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        popupContainer = null
        syncNav()
    }

    // =========================================================================
    // BOOKMARKS, TABS & STREAM BOTTOM SHEET
    // =========================================================================

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
                        Toast.makeText(this, "Added to Vault", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, "Queue error: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

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
                listContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#45475A"))
                })
            }
        }
        updateRadioDots()
        scroll.addView(listContainer)
        sheet.addView(scroll)

        val btnDownload = Button(this).apply {
            text = "Download"
            isAllCaps = false
            setTextColor(Color.parseColor("#11111B"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            backgroundTintList = null
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
        closePopupVideoPlayer()
        inspectExecutor.shutdownNow()
        translateExecutor.shutdownNow()
        tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
// END OF FILE
