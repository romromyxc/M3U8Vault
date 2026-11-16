package com.example.m3u8vault

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.abs

@OptIn(UnstableApi::class)
class VaultPlayerActivity : AppCompatActivity() {

    private lateinit var playerSection: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var vaultPlayerView: PlayerView
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnAutoNext: Button
    private lateinit var vaultTopBar: View
    private lateinit var vaultTabsBar: View
    private lateinit var vaultListView: ListView
    private lateinit var tvEmptyVault: TextView
    private lateinit var tvTabVideos: TextView
    private lateinit var tvTabDownloading: TextView
    private lateinit var indTabVideos: View
    private lateinit var indTabDownloading: View

    private lateinit var pinLockOverlay: LinearLayout
    private lateinit var tvPinHeading: TextView
    private lateinit var btnPinUnlock: Button
    private lateinit var pinDots: List<View>

    private var exoPlayer: ExoPlayer? = null
    private var completedVideos: List<Download> = emptyList()
    private var activeDownloads: List<Download> = emptyList()
    private var currentPlayingId: String? = null
    private var showingDownloadingTab = false
    private var autoAdvanceEnabled = true
    private var isFullscreen = false

    private var enteredPin = ""
    private var settingNewPinMode = false
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            reloadDownloadsList()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isFullscreen) {
                toggleFullscreen(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1E1E2E")
        window.navigationBarColor = Color.parseColor("#1E1E2E")
        setContentView(R.layout.activity_vault_player)

        playerSection = findViewById(R.id.playerSection)
        playerFrame = findViewById(R.id.playerFrame)
        vaultPlayerView = findViewById(R.id.vaultPlayerView)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        btnAutoNext = findViewById(R.id.btnAutoNext)
        vaultTopBar = findViewById(R.id.vaultTopBar)
        vaultTabsBar = findViewById(R.id.vaultTabsBar)
        vaultListView = findViewById(R.id.vaultListView)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)
        tvTabVideos = findViewById(R.id.tvTabVideos)
        tvTabDownloading = findViewById(R.id.tvTabDownloading)
        indTabVideos = findViewById(R.id.indTabVideos)
        indTabDownloading = findViewById(R.id.indTabDownloading)

        pinLockOverlay = findViewById(R.id.pinLockOverlay)
        tvPinHeading = findViewById(R.id.tvPinHeading)
        btnPinUnlock = findViewById(R.id.btnPinUnlock)
        pinDots = listOf(
            findViewById(R.id.pinDot1),
            findViewById(R.id.pinDot2),
            findViewById(R.id.pinDot3),
            findViewById(R.id.pinDot4)
        )

        onBackPressedDispatcher.addCallback(this, backCallback)
        applyCatppuccinStyles()
        initPlayer()
        setupControls()
        setupPinScreen()
        reloadDownloadsList()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dpInt(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyCatppuccinStyles() {
        listOf(
            findViewById<View>(R.id.btnAutoNext),
            findViewById<View>(R.id.btnPrevVideo),
            findViewById<View>(R.id.btnNextVideo),
            findViewById<View>(R.id.btnPlaylistPopup),
            findViewById<View>(R.id.btnFullscreen)
        ).forEach { btn ->
            btn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dp(10f)
            }
        }

        btnPinUnlock.background = GradientDrawable().apply {
            setColor(Color.parseColor("#CBA6F7"))
            cornerRadius = dp(24f)
        }
    }

    private fun initPlayer() {
        val cacheFactory = VaultEngine.buildCacheDataSourceFactory(this)
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && autoAdvanceEnabled) {
                            playAdjacentVideo(1)
                        }
                    }
                })
            }
        vaultPlayerView.player = exoPlayer
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        findViewById<Button>(R.id.btnVaultBack).setOnClickListener {
            if (isFullscreen) toggleFullscreen(false) else finish()
        }

        btnAutoNext.setOnClickListener {
            autoAdvanceEnabled = !autoAdvanceEnabled
            btnAutoNext.text = if (autoAdvanceEnabled) "Auto: ON" else "Auto: OFF"
            btnAutoNext.setTextColor(Color.parseColor(if (autoAdvanceEnabled) "#A6E3A1" else "#6C7086"))
        }

        findViewById<Button>(R.id.btnLockVault).setOnClickListener {
            exoPlayer?.pause()
            showPinOverlay(forceReset = false)
        }

        findViewById<Button>(R.id.btnLockVault).setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Change 4-Digit Vault PIN?")
                .setPositiveButton("Change PIN") { _, _ ->
                    exoPlayer?.pause()
                    showPinOverlay(forceReset = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        findViewById<Button>(R.id.btnPrevVideo).setOnClickListener { playAdjacentVideo(-1) }
        findViewById<Button>(R.id.btnNextVideo).setOnClickListener { playAdjacentVideo(1) }
        findViewById<Button>(R.id.btnPlaylistPopup).setOnClickListener { showQuickPlaylistBottomSheet() }
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen(!isFullscreen) }

        findViewById<View>(R.id.tabVideos).setOnClickListener {
            showingDownloadingTab = false
            updateTabIndicators()
            renderList()
        }

        findViewById<View>(R.id.tabDownloading).setOnClickListener {
            showingDownloadingTab = true
            updateTabIndicators()
            renderList()
        }

        // Swipe left/right on player frame to jump Next/Previous without closing
        var downX = 0f
        var downY = 0f
        playerFrame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > dp(70f) && abs(dx) > abs(dy) * 1.5f) {
                        if (dx < 0) playAdjacentVideo(1) else playAdjacentVideo(-1)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun toggleFullscreen(enable: Boolean) {
        isFullscreen = enable
        backCallback.isEnabled = enable
        if (enable) {
            vaultTopBar.visibility = View.GONE
            vaultTabsBar.visibility = View.GONE
            vaultListView.visibility = View.GONE
            tvEmptyVault.visibility = View.GONE
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            vaultTopBar.visibility = View.VISIBLE
            vaultTabsBar.visibility = View.VISIBLE
            vaultListView.visibility = View.VISIBLE
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpInt(220f)
            )
            renderList()
        }
    }

    private fun playVideo(download: Download) {
        val player = exoPlayer ?: return
        currentPlayingId?.let { prevId ->
            VaultEngine.savePlaybackPosition(this, prevId, player.currentPosition)
        }

        val id = download.request.id
        val title = download.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video" }
        val url = download.request.uri.toString()
        val cacheKey = download.request.customCacheKey

        currentPlayingId = id
        playerSection.visibility = View.VISIBLE
        tvNowPlaying.text = title

        val mediaItem = VaultEngine.buildMediaItem(url, title, cacheKey)
        player.setMediaItem(mediaItem)
        player.prepare()

        val savedPos = VaultEngine.getPlaybackPosition(this, id)
        if (savedPos > 0L) player.seekTo(savedPos)
        player.playWhenReady = true
        renderList()
    }

    private fun playAdjacentVideo(offset: Int) {
        if (completedVideos.isEmpty()) return
        val currentIdx = completedVideos.indexOfFirst { it.request.id == currentPlayingId }
        val nextIdx = if (currentIdx == -1) {
            0
        } else {
            (currentIdx + offset + completedVideos.size) % completedVideos.size
        }
        val target = completedVideos[nextIdx]
        playVideo(target)
        val title = target.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video" }
        Toast.makeText(this, "Playing (${nextIdx + 1}/${completedVideos.size}): $title", Toast.LENGTH_SHORT).show()
    }

    private fun showQuickPlaylistBottomSheet() {
        if (completedVideos.isEmpty()) {
            Toast.makeText(this, "No completed videos in vault", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(18f), dpInt(12f), dpInt(18f), dpInt(18f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
            }
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(40f), dpInt(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpInt(12f)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CBA6F7"))
                cornerRadius = dp(2f)
            }
        }
        sheet.addView(handle)

        val header = TextView(this).apply {
            text = "Switch Vault Video (${completedVideos.size})"
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10f) }
        }
        sheet.addView(header)

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        completedVideos.forEachIndexed { idx, dl ->
            val title = dl.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video #${idx + 1}" }
            val isPlaying = dl.request.id == currentPlayingId
            val row = TextView(this).apply {
                text = if (isPlaying) "▶  ${idx + 1}. $title" else "     ${idx + 1}. $title"
                setTextColor(Color.parseColor(if (isPlaying) "#A6E3A1" else "#CDD6F4"))
                textSize = 14f
                setPadding(dpInt(12f), dpInt(13f), dpInt(12f), dpInt(13f))
                if (isPlaying) {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E1E2E"))
                        cornerRadius = dp(10f)
                    }
                }
                setOnClickListener {
                    playVideo(dl)
                    dialog.dismiss()
                }
            }
            col.addView(row)
        }
        scroll.addView(col)
        sheet.addView(scroll)

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun updateTabIndicators() {
        tvTabVideos.setTextColor(Color.parseColor(if (!showingDownloadingTab) "#CBA6F7" else "#6C7086"))
        tvTabVideos.typeface = if (!showingDownloadingTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        indTabVideos.setBackgroundColor(if (!showingDownloadingTab) Color.parseColor("#CBA6F7") else Color.TRANSPARENT)

        tvTabDownloading.setTextColor(Color.parseColor(if (showingDownloadingTab) "#CBA6F7" else "#6C7086"))
        tvTabDownloading.typeface = if (showingDownloadingTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        indTabDownloading.setBackgroundColor(if (showingDownloadingTab) Color.parseColor("#CBA6F7") else Color.TRANSPARENT)
    }

    private fun reloadDownloadsList() {
        val all = VaultEngine.getAllDownloads(this)
        completedVideos = all.filter { it.state == Download.STATE_COMPLETED }
        activeDownloads = all.filter { it.state != Download.STATE_COMPLETED }
        tvTabVideos.text = "Videos (${completedVideos.size})"
        tvTabDownloading.text = "Downloading (${activeDownloads.size})"
        if (!isFullscreen) renderList()
    }

    private fun renderList() {
        val items = if (showingDownloadingTab) activeDownloads else completedVideos
        tvEmptyVault.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        tvEmptyVault.text = if (showingDownloadingTab) "No active downloads" else "No saved videos in vault yet"

        vaultListView.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = items[pos].request.id.hashCode().toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val dl = items[pos]
                val id = dl.request.id
                val title = dl.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video" }
                val isPlaying = id == currentPlayingId && !showingDownloadingTab

                val card = LinearLayout(this@VaultPlayerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpInt(14f), dpInt(12f), dpInt(14f), dpInt(12f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#313244"))
                        cornerRadius = dp(14f)
                        if (isPlaying) {
                            setStroke(dpInt(1.5f), Color.parseColor("#CBA6F7"))
                        }
                    }
                }

                val topRow = LinearLayout(this@VaultPlayerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val iconBadge = TextView(this@VaultPlayerActivity).apply {
                    text = if (showingDownloadingTab) "⬇" else if (isPlaying) "▶" else "🎬"
                    textSize = 16f
                    setTextColor(Color.parseColor(if (isPlaying) "#A6E3A1" else "#CBA6F7"))
                    setPadding(0, 0, dpInt(10f), 0)
                }
                topRow.addView(iconBadge)

                val infoCol = LinearLayout(this@VaultPlayerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvTitle = TextView(this@VaultPlayerActivity).apply {
                    text = title
                    setTextColor(Color.parseColor(if (isPlaying) "#A6E3A1" else "#CDD6F4"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                }
                infoCol.addView(tvTitle)

                val pct = dl.percentDownloaded.coerceIn(0f, 100f).toInt()
                val sizeStr = VaultEngine.formatSize(dl.bytesDownloaded)
                val statusText = if (showingDownloadingTab) {
                    val stateLabel = when (dl.state) {
                        Download.STATE_STOPPED -> "Paused"
                        Download.STATE_FAILED -> "Failed"
                        Download.STATE_QUEUED -> "Queued"
                        else -> "$pct%"
                    }
                    "$stateLabel • $sizeStr"
                } else {
                    if (isPlaying) "Now Playing • $sizeStr" else "Saved Offline • $sizeStr"
                }

                val tvSub = TextView(this@VaultPlayerActivity).apply {
                    text = statusText
                    setTextColor(Color.parseColor("#A6ADC8"))
                    textSize = 12f
                }
                infoCol.addView(tvSub)
                topRow.addView(infoCol)

                if (showingDownloadingTab) {
                    val isPaused = dl.state == Download.STATE_STOPPED || dl.state == Download.STATE_FAILED
                    val btnToggle = Button(this@VaultPlayerActivity).apply {
                        text = if (isPaused) "▶" else "⏸"
                        setTextColor(Color.parseColor("#CDD6F4"))
                        background = ColorDrawable(Color.TRANSPARENT)
                        layoutParams = LinearLayout.LayoutParams(dpInt(38f), dpInt(38f))
                        setOnClickListener {
                            if (isPaused) VaultEngine.resumeDownload(this@VaultPlayerActivity, id)
                            else VaultEngine.pauseDownload(this@VaultPlayerActivity, id)
                            reloadDownloadsList()
                        }
                    }
                    topRow.addView(btnToggle)
                }

                val btnDelete = Button(this@VaultPlayerActivity).apply {
                    text = "🗑"
                    setTextColor(Color.parseColor("#F38BA8"))
                    background = ColorDrawable(Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(dpInt(38f), dpInt(38f))
                    setOnClickListener {
                        AlertDialog.Builder(this@VaultPlayerActivity)
                            .setTitle("Delete \"$title\"?")
                            .setPositiveButton("Delete") { _, _ ->
                                if (currentPlayingId == id) {
                                    exoPlayer?.stop()
                                    currentPlayingId = null
                                    playerSection.visibility = View.GONE
                                }
                                VaultEngine.removeDownload(this@VaultPlayerActivity, id)
                                reloadDownloadsList()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                topRow.addView(btnDelete)
                card.addView(topRow)

                if (showingDownloadingTab) {
                    val bar = ProgressBar(this@VaultPlayerActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = pct
                        progressDrawable.setTint(Color.parseColor("#A6E3A1"))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dpInt(5f)
                        ).apply { topMargin = dpInt(8f) }
                    }
                    card.addView(bar)
                } else {
                    card.setOnClickListener { playVideo(dl) }
                }

                return card
            }
        }
    }

    private fun getSavedPin(): String {
        val p1 = getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
        val p2 = getSharedPreferences("vault_prefs", MODE_PRIVATE)
        return p1.getString("vault_pin", null)
            ?: p1.getString("pin_code", null)
            ?: p2.getString("vault_pin", null)
            ?: p2.getString("pin_code", null)
            ?: ""
    }

    private fun savePin(pin: String) {
        getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
            .edit().putString("vault_pin", pin).apply()
    }

    private fun setupPinScreen() {
        val grid = findViewById<GridLayout>(R.id.pinKeypadGrid)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")

        keys.forEach { key ->
            val btn = Button(this).apply {
                text = key
                textSize = 20f
                setTextColor(Color.parseColor("#CDD6F4"))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dpInt(68f)
                    height = dpInt(68f)
                    setMargins(dpInt(10f), dpInt(8f), dpInt(10f), dpInt(8f))
                }
                if (key.isEmpty()) {
                    visibility = View.INVISIBLE
                } else {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#313244"))
                    }
                    setOnClickListener { onPinKeyPress(key) }
                }
            }
            grid.addView(btn)
        }

        btnPinUnlock.setOnClickListener { verifyOrSavePin() }
        showPinOverlay(forceReset = false)
    }

    private fun updatePinDots() {
        pinDots.forEachIndexed { idx, dot ->
            val filled = idx < enteredPin.length
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) {
                    setColor(Color.parseColor("#CBA6F7"))
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dpInt(1.5f), Color.parseColor("#45475A"))
                }
            }
        }
    }

    private fun showPinOverlay(forceReset: Boolean) {
        enteredPin = ""
        val existing = getSavedPin()
        settingNewPinMode = forceReset || existing.length < 4
        tvPinHeading.text = if (settingNewPinMode) "Set 4-Digit Vault PIN" else "Vault Locked"
        btnPinUnlock.text = if (settingNewPinMode) "Save PIN" else "Unlock"
        updatePinDots()
        pinLockOverlay.visibility = View.VISIBLE
    }

    private fun onPinKeyPress(key: String) {
        if (key == "⌫") {
            if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
        } else if (enteredPin.length < 4) {
            enteredPin += key
        }
        updatePinDots()
        if (enteredPin.length == 4 && !settingNewPinMode) {
            verifyOrSavePin()
        }
    }

    private fun verifyOrSavePin() {
        if (enteredPin.length < 4) {
            Toast.makeText(this, "Enter 4 digits", Toast.LENGTH_SHORT).show()
            return
        }
        if (settingNewPinMode) {
            savePin(enteredPin)
            pinLockOverlay.visibility = View.GONE
            Toast.makeText(this, "Vault PIN Saved", Toast.LENGTH_SHORT).show()
        } else if (enteredPin == getSavedPin()) {
            pinLockOverlay.visibility = View.GONE
        } else {
            enteredPin = ""
            updatePinDots()
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        currentPlayingId?.let { id ->
            exoPlayer?.let { VaultEngine.savePlaybackPosition(this, id, it.currentPosition) }
        }
        super.onPause()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
// END OF FILE
