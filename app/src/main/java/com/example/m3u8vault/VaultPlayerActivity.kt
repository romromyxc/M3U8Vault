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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var quickSwitchBar: LinearLayout
    private lateinit var vaultPlayerView: PlayerView
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnAutoNext: Button
    private lateinit var btnLockVault: Button
    private lateinit var vaultTopBar: View
    private lateinit var vaultListView: ListView
    private lateinit var tvEmptyVault: TextView
    private lateinit var btnExitFullscreenOverlay: Button

    private lateinit var pinLockScroll: ScrollView
    private lateinit var tvPinHeading: TextView
    private lateinit var btnPinUnlock: Button
    private lateinit var pinDots: List<View>

    private var exoPlayer: ExoPlayer? = null
    private var completedVideos: List<Download> = emptyList()
    private var activeDownloads: List<Download> = emptyList()
    private var currentPlayingId: String? = null
    private var pendingRestoreFullscreen = false
    private var autoAdvanceEnabled = true
    private var isFullscreen = false
    private var isUnlockedSession = false

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

        isUnlockedSession = savedInstanceState?.getBoolean("is_unlocked_session", false) ?: false
        currentPlayingId = savedInstanceState?.getString("current_playing_id", null)
        pendingRestoreFullscreen = savedInstanceState?.getBoolean("is_fullscreen", false) ?: false
        autoAdvanceEnabled = savedInstanceState?.getBoolean("auto_advance_enabled", true) ?: true

        playerSection = findViewById(R.id.playerSection)
        playerFrame = findViewById(R.id.playerFrame)
        quickSwitchBar = findViewById(R.id.quickSwitchBar)
        vaultPlayerView = findViewById(R.id.vaultPlayerView)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        btnAutoNext = findViewById(R.id.btnAutoNext)
        btnLockVault = findViewById(R.id.btnLockVault)
        vaultTopBar = findViewById(R.id.vaultTopBar)
        vaultListView = findViewById(R.id.vaultListView)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)

        pinLockScroll = findViewById(R.id.pinLockScroll)
        tvPinHeading = findViewById(R.id.tvPinHeading)
        btnPinUnlock = findViewById(R.id.btnPinUnlock)
        pinDots = listOf(
            findViewById(R.id.pinDot1),
            findViewById(R.id.pinDot2),
            findViewById(R.id.pinDot3),
            findViewById(R.id.pinDot4)
        )

        btnAutoNext.text = if (autoAdvanceEnabled) "Auto: ON" else "Auto: OFF"
        btnAutoNext.setTextColor(Color.parseColor(if (autoAdvanceEnabled) "#A6E3A1" else "#6C7086"))

        btnExitFullscreenOverlay = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#CDD6F4"))
            backgroundTintList = null
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#AA1E1E2E"))
                setStroke(dpInt(1f), Color.parseColor("#CBA6F7"))
            }
            layoutParams = FrameLayout.LayoutParams(dpInt(36f), dpInt(36f)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpInt(14f)
                marginEnd = dpInt(14f)
            }
            visibility = View.GONE
            setOnClickListener { toggleFullscreen(false) }
        }
        playerFrame.addView(btnExitFullscreenOverlay)

        onBackPressedDispatcher.addCallback(this, backCallback)
        applyCatppuccinStyles()
        initPlayer()
        setupControls()
        setupPinScreen()
        reloadDownloadsList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPlayingId?.let { id ->
            exoPlayer?.let { saveSafePosition(id, it) }
        }
        outState.putBoolean("is_unlocked_session", isUnlockedSession)
        outState.putString("current_playing_id", currentPlayingId)
        outState.putBoolean("is_fullscreen", isFullscreen)
        outState.putBoolean("auto_advance_enabled", autoAdvanceEnabled)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dpInt(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyCatppuccinStyles() {
        listOf(
            findViewById<Button>(R.id.btnAutoNext),
            findViewById<Button>(R.id.btnPlaylistPopup),
            findViewById<Button>(R.id.btnFullscreen)
        ).forEach { btn ->
            btn.backgroundTintList = null
            btn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dp(10f)
            }
        }

        findViewById<Button>(R.id.btnVaultBack).backgroundTintList = null
        btnLockVault.backgroundTintList = null

        btnPinUnlock.backgroundTintList = null
        btnPinUnlock.background = GradientDrawable().apply {
            setColor(Color.parseColor("#CBA6F7"))
            cornerRadius = dp(22f)
        }
    }

    private fun saveSafePosition(videoId: String, player: ExoPlayer) {
        val dur = player.duration
        val pos = player.currentPosition
        val safePos = if (dur > 0L && pos >= dur - 2000L) 0L else pos
        VaultEngine.savePlaybackPosition(this, videoId, safePos)
    }

    private fun initPlayer() {
        val cacheFactory = VaultEngine.buildCacheDataSourceFactory(this)
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            currentPlayingId?.let { VaultEngine.savePlaybackPosition(this@VaultPlayerActivity, it, 0L) }
                            if (autoAdvanceEnabled && completedVideos.size > 1) {
                                playAdjacentVideo(1)
                            } else {
                                playWhenReady = false
                                seekTo(0L)
                            }
                        }
                    }
                })
            }
        vaultPlayerView.player = exoPlayer
    }

    private fun isPinEnabled(): Boolean {
        val prefs = getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
        return prefs.getBoolean("vault_pin_enabled", false) && getSavedPin().length == 4
    }

    private fun updateLockIcon() {
        btnLockVault.text = if (isPinEnabled()) "🔒" else "🔓"
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

        btnLockVault.setOnClickListener {
            if (isPinEnabled()) {
                exoPlayer?.pause()
                isUnlockedSession = false
                showPinOverlay(forceReset = false)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Optional Vault PIN Lock")
                    .setMessage("PIN lock is currently OFF. Would you like to set a 4-digit PIN?")
                    .setPositiveButton("Set 4-Digit PIN") { _, _ ->
                        exoPlayer?.pause()
                        showPinOverlay(forceReset = true)
                    }
                    .setNegativeButton("Keep Unlocked", null)
                    .show()
            }
        }

        btnLockVault.setOnLongClickListener {
            if (!isPinEnabled()) return@setOnLongClickListener false
            AlertDialog.Builder(this)
                .setTitle("Vault PIN Options")
                .setItems(arrayOf("Change 4-Digit PIN", "Turn Off PIN Lock")) { _, which ->
                    if (which == 0) {
                        exoPlayer?.pause()
                        showPinOverlay(forceReset = true)
                    } else {
                        getSharedPreferences("m3u8vault_prefs", MODE_PRIVATE)
                            .edit().putBoolean("vault_pin_enabled", false).apply()
                        updateLockIcon()
                        Toast.makeText(this, "Vault PIN Lock Turned Off", Toast.LENGTH_SHORT).show()
                    }
                }.show()
            true
        }

        findViewById<Button>(R.id.btnPlaylistPopup).setOnClickListener { showQuickPlaylistBottomSheet() }
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen(!isFullscreen) }

        var verticalSwiped = false
        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffY) > abs(diffX) * 1.3f && abs(diffY) > dp(45f) && abs(velocityY) > 150f) {
                    verticalSwiped = true
                    if (diffY < 0) playAdjacentVideo(1) else playAdjacentVideo(-1)
                    return true
                }
                return false
            }
        })

        vaultPlayerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                verticalSwiped = false
            }
            swipeDetector.onTouchEvent(event)
            verticalSwiped
        }
    }

    private fun toggleFullscreen(enable: Boolean, changeOrientation: Boolean = true) {
        isFullscreen = enable
        backCallback.isEnabled = enable
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (enable) {
            vaultTopBar.visibility = View.GONE
            quickSwitchBar.visibility = View.GONE
            vaultListView.visibility = View.GONE
            tvEmptyVault.visibility = View.GONE
            btnExitFullscreenOverlay.visibility = View.VISIBLE

            playerSection.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            if (changeOrientation) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            if (changeOrientation) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            insetsController.show(WindowInsetsCompat.Type.systemBars())

            btnExitFullscreenOverlay.visibility = View.GONE
            vaultTopBar.visibility = View.VISIBLE
            quickSwitchBar.visibility = View.VISIBLE
            vaultListView.visibility = View.VISIBLE

            playerSection.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            playerFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpInt(220f)
            )
            renderUnifiedList()
        }
    }

    private fun playVideo(download: Download, savePrevious: Boolean = true) {
        val player = exoPlayer ?: return
        if (savePrevious) {
            currentPlayingId?.let { prevId ->
                saveSafePosition(prevId, player)
            }
        }

        val id = download.request.id
        val title = download.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video" }
        val url = download.request.uri.toString()
        val cacheKey = download.request.customCacheKey

        currentPlayingId = id
        playerSection.visibility = View.VISIBLE
        tvNowPlaying.text = title

        val mediaItem = VaultEngine.buildMediaItem(url, title, cacheKey)
        player.stop()
        player.setMediaItem(mediaItem)
        player.prepare()

        val savedPos = VaultEngine.getPlaybackPosition(this, id)
        if (savedPos > 1000L) player.seekTo(savedPos)
        player.playWhenReady = true
        if (!isFullscreen) renderUnifiedList()
    }

    private fun playAdjacentVideo(offset: Int) {
        if (completedVideos.isEmpty()) return
        if (completedVideos.size == 1) {
            exoPlayer?.seekTo(0L)
            exoPlayer?.playWhenReady = true
            return
        }
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

    private fun reloadDownloadsList() {
        val all = VaultEngine.getAllDownloads(this)
        activeDownloads = all.filter { it.state != Download.STATE_COMPLETED }
        completedVideos = all.filter { it.state == Download.STATE_COMPLETED }

        // Automatically resume the playing video & fullscreen state if activity recreated on rotation
        val restoreId = currentPlayingId
        if (restoreId != null && (exoPlayer?.mediaItemCount ?: 0) == 0) {
            val target = all.firstOrNull { it.request.id == restoreId }
            if (target != null) {
                playVideo(target, savePrevious = false)
                if (pendingRestoreFullscreen) {
                    pendingRestoreFullscreen = false
                    toggleFullscreen(true, changeOrientation = false)
                }
                return
            }
        }

        if (!isFullscreen) renderUnifiedList()
    }

    private fun renderUnifiedList() {
        val unifiedItems = activeDownloads + completedVideos
        tvEmptyVault.visibility = if (unifiedItems.isEmpty()) View.VISIBLE else View.GONE

        vaultListView.adapter = object : BaseAdapter() {
            override fun getCount() = unifiedItems.size
            override fun getItem(pos: Int) = unifiedItems[pos]
            override fun getItemId(pos: Int) = unifiedItems[pos].request.id.hashCode().toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val dl = unifiedItems[pos]
                val id = dl.request.id
                val isDownloadingItem = dl.state != Download.STATE_COMPLETED
                val title = dl.request.data.toString(Charsets.UTF_8).ifBlank { "Vault Video" }
                val isPlaying = id == currentPlayingId && !isDownloadingItem

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
                    text = if (isDownloadingItem) "⬇" else if (isPlaying) "▶" else "🎬"
                    textSize = 16f
                    setTextColor(Color.parseColor(if (isPlaying || isDownloadingItem) "#A6E3A1" else "#CBA6F7"))
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
                val statusText = if (isDownloadingItem) {
                    val stateLabel = when (dl.state) {
                        Download.STATE_STOPPED -> "Paused"
                        Download.STATE_FAILED -> "Failed"
                        Download.STATE_QUEUED -> "Queued"
                        else -> "Downloading $pct%"
                    }
                    "$stateLabel • $sizeStr"
                } else {
                    if (isPlaying) "Now Playing (Swipe ↑/↓) • $sizeStr" else "Saved Offline • $sizeStr"
                }

                val tvSub = TextView(this@VaultPlayerActivity).apply {
                    text = statusText
                    setTextColor(Color.parseColor("#A6ADC8"))
                    textSize = 12f
                }
                infoCol.addView(tvSub)
                topRow.addView(infoCol)

                if (isDownloadingItem) {
                    val isPaused = dl.state == Download.STATE_STOPPED || dl.state == Download.STATE_FAILED
                    val btnToggle = Button(this@VaultPlayerActivity).apply {
                        text = if (isPaused) "▶" else "⏸"
                        setTextColor(Color.parseColor("#CDD6F4"))
                        backgroundTintList = null
                        background = ColorDrawable(Color.TRANSPARENT)
                        setPadding(0, 0, 0, 0)
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
                    backgroundTintList = null
                    background = ColorDrawable(Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)
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

                if (isDownloadingItem) {
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
            .edit()
            .putString("vault_pin", pin)
            .putBoolean("vault_pin_enabled", true)
            .apply()
    }

    private fun setupPinScreen() {
        val grid = findViewById<GridLayout>(R.id.pinKeypadGrid)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")

        keys.forEach { key ->
            val btn = Button(this).apply {
                text = key
                textSize = 18f
                setTextColor(Color.parseColor("#CDD6F4"))
                backgroundTintList = null
                setPadding(0, 0, 0, 0)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dpInt(60f)
                    height = dpInt(60f)
                    setMargins(dpInt(8f), dpInt(6f), dpInt(8f), dpInt(6f))
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
        updateLockIcon()

        if (isPinEnabled() && !isUnlockedSession) {
            showPinOverlay(forceReset = false)
        } else {
            pinLockScroll.visibility = View.GONE
        }
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
        pinLockScroll.visibility = View.VISIBLE
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
            isUnlockedSession = true
            updateLockIcon()
            pinLockScroll.visibility = View.GONE
            Toast.makeText(this, "Vault PIN Saved", Toast.LENGTH_SHORT).show()
        } else if (enteredPin == getSavedPin()) {
            isUnlockedSession = true
            pinLockScroll.visibility = View.GONE
        } else {
            enteredPin = ""
            updatePinDots()
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLockIcon()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        currentPlayingId?.let { id ->
            exoPlayer?.let { saveSafePosition(id, it) }
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
