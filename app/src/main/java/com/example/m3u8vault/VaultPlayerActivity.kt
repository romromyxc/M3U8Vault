package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.abs

@OptIn(UnstableApi::class)
class VaultPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var vaultHeaderBar: LinearLayout
    private lateinit var tvHeaderTitle: TextView
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: View
    private lateinit var tvGestureHud: TextView
    private lateinit var vaultListSection: FrameLayout
    private lateinit var downloadListView: ListView
    private lateinit var tvEmptyVault: TextView
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var iconWrapDownloading: FrameLayout
    private lateinit var iconWrapVideos: FrameLayout
    private lateinit var tvBadgeDownloading: TextView
    private lateinit var tvBadgeVideos: TextView
    private lateinit var tvLabelDownloading: TextView
    private lateinit var tvLabelVideos: TextView
    private lateinit var btnFullscreen: Button
    private lateinit var btnSpeed: Button
    private lateinit var audioManager: AudioManager

    private val visibleItems = mutableListOf<Download>()
    private var allCachedDownloads = listOf<Download>()
    private lateinit var vaultAdapter: VaultAdapter
    private var showingDownloadingTab = true
    private var isFullscreen = false
    private var volumeAccum = 0f
    private var currentPlayingId: String? = null
    private val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { tvGestureHud.visibility = View.GONE }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLists()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) {
            runOnUiThread { refreshLists() }
        }
        override fun onDownloadRemoved(dm: DownloadManager, download: Download) {
            runOnUiThread { refreshLists() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshot & screen recording privacy lock intentionally disabled
        setContentView(R.layout.activity_vault_player)

        vaultHeaderBar = findViewById(R.id.vaultHeaderBar)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        playerContainer = findViewById(R.id.playerContainer)
        playerView = findViewById(R.id.playerView)
        gestureOverlay = findViewById(R.id.gestureOverlay)
        tvGestureHud = findViewById(R.id.tvGestureHud)
        vaultListSection = findViewById(R.id.vaultListSection)
        downloadListView = findViewById(R.id.downloadListView)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)
        bottomNavBar = findViewById(R.id.bottomNavBar)
        iconWrapDownloading = findViewById(R.id.iconWrapDownloading)
        iconWrapVideos = findViewById(R.id.iconWrapVideos)
        tvBadgeDownloading = findViewById(R.id.tvBadgeDownloading)
        tvBadgeVideos = findViewById(R.id.tvBadgeVideos)
        tvLabelDownloading = findViewById(R.id.tvLabelDownloading)
        tvLabelVideos = findViewById(R.id.tvLabelVideos)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnSpeed = findViewById(R.id.btnSpeed)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupGestures()
        setupButtons()
        setupList()
        initExoPlayer()
        refreshLists(initialAutoSelect = true)
    }

    private fun setupButtons() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.navDownloading).setOnClickListener {
            showingDownloadingTab = true
            updateBottomNavVisuals()
            refreshLists()
        }

        findViewById<LinearLayout>(R.id.navVideos).setOnClickListener {
            showingDownloadingTab = false
            updateBottomNavVisuals()
            refreshLists()
        }

        findViewById<Button>(R.id.btnStreamOnline).setOnClickListener { showStreamOnlineDialog() }
        findViewById<Button>(R.id.btnHeaderMenu).setOnClickListener { showHeaderMenu() }
        findViewById<Button>(R.id.btnQuality).setOnClickListener { showQualityPicker() }

        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val spd = speeds[speedIdx]
            player?.playbackParameters = PlaybackParameters(spd)
            btnSpeed.text = "${spd}x"
        }

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            val isPortrait = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            requestedOrientation = if (isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        btnFullscreen.setOnClickListener { toggleFullscreen() }

        findViewById<Button>(R.id.btnClosePlayer).setOnClickListener {
            saveCurrentPosition()
            if (isFullscreen) toggleFullscreen()
            player?.pause()
            player?.stop()
            playerContainer.visibility = View.GONE
            currentPlayingId = null
        }
    }

    private fun updateBottomNavVisuals() {
        tvHeaderTitle.text = if (showingDownloadingTab) "Downloading" else "Videos"
        if (showingDownloadingTab) {
            iconWrapDownloading.setBackgroundResource(R.drawable.bg_nav_pill)
            iconWrapVideos.background = null
            tvLabelDownloading.setTextColor(Color.parseColor("#F0F4FF"))
            tvLabelVideos.setTextColor(Color.parseColor("#9AA6D1"))
        } else {
            iconWrapDownloading.background = null
            iconWrapVideos.setBackgroundResource(R.drawable.bg_nav_pill)
            tvLabelDownloading.setTextColor(Color.parseColor("#9AA6D1"))
            tvLabelVideos.setTextColor(Color.parseColor("#F0F4FF"))
        }
    }

    private fun showStreamOnlineDialog() {
        val input = EditText(this).apply { hint = "Paste online .m3u8 URL to stream..." }
        AlertDialog.Builder(this)
            .setTitle("Stream Online Video")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) playStream(url, "Online Stream", null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHeaderMenu() {
        val options = arrayOf(
            "⏸ Pause All Downloads",
            "▶ Resume All Downloads",
            "🔗 Stream Online .m3u8 URL",
            "⌂ Back to Browser (Home)"
        )
        AlertDialog.Builder(this)
            .setTitle("Download Manager")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> allCachedDownloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
                        .forEach { VaultEngine.pauseDownload(this, it.request.id) }
                    1 -> allCachedDownloads.filter { it.state == Download.STATE_STOPPED || it.state == Download.STATE_FAILED }
                        .forEach { VaultEngine.resumeDownload(this, it.request.id) }
                    2 -> showStreamOnlineDialog()
                    3 -> finish()
                }
                uiHandler.postDelayed({ refreshLists() }, 300L)
            }.show()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val params = playerContainer.layoutParams as LinearLayout.LayoutParams

        if (isFullscreen) {
            vaultHeaderBar.visibility = View.GONE
            vaultListSection.visibility = View.GONE
            bottomNavBar.visibility = View.GONE
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            vaultHeaderBar.visibility = View.VISIBLE
            vaultListSection.visibility = View.VISIBLE
            bottomNavBar.visibility = View.VISIBLE
            params.height = (230 * resources.displayMetrics.density).toInt()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        playerContainer.layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                volumeAccum = 0f
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val isRight = e.x > gestureOverlay.width / 2f
                player?.let { exo ->
                    if (isRight) {
                        val target = exo.currentPosition + 10_000L
                        exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration) else target)
                        showHud("⏩ +10s")
                    } else {
                        exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                        showHud("⏪ -10s")
                    }
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                if (abs(distanceY) <= abs(distanceX)) return false
                val h = gestureOverlay.height.coerceAtLeast(1)
                if (start.x > gestureOverlay.width / 2f) {
                    volumeAccum += distanceY
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val stepPx = h / (maxVol * 1.3f)
                    if (abs(volumeAccum) >= stepPx) {
                        val steps = (volumeAccum / stepPx).toInt()
                        volumeAccum -= steps * stepPx
                        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val next = (cur + steps).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                        showHud("🔊 ${(next * 100) / maxVol}%")
                    }
                } else {
                    val lp = window.attributes
                    val curB = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                    val nextB = (curB + (distanceY / h)).coerceIn(0.05f, 1.0f)
                    lp.screenBrightness = nextB
                    window.attributes = lp
                    showHud("☀ ${(nextB * 100).toInt()}%")
                }
                return true
            }
        })
        gestureOverlay.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun showHud(text: String) {
        tvGestureHud.text = text
        tvGestureHud.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideHudRunnable)
        uiHandler.postDelayed(hideHudRunnable, 900L)
    }

    private fun showQualityPicker() {
        val exo = player ?: return
        val labels = mutableListOf("Auto HD (Best Available)")
        val actions = mutableListOf<() -> Unit>({
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        })
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    if (fmt.height > 0) {
                        labels.add("${fmt.height}p (${fmt.width}x${fmt.height})")
                        actions.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                        }
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Video Playback Quality")
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
                Toast.makeText(this, "Quality: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun initExoPlayer() {
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(VaultEngine.buildCacheDataSourceFactory(this))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { playerView.player = it }
    }

    private fun playStream(url: String, title: String, resumeId: String?) {
        saveCurrentPosition()
        playerContainer.visibility = View.VISIBLE
        currentPlayingId = resumeId
        val mediaItem = VaultEngine.buildMediaItem(url, title, resumeId)
        val resumeMs = if (resumeId != null) VaultEngine.getPlaybackPosition(this, resumeId) else 0L
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            if (resumeMs > 0L) seekTo(resumeMs)
            playWhenReady = true
        }
    }

    private fun saveCurrentPosition() {
        val id = currentPlayingId ?: return
        val pos = player?.currentPosition ?: return
        VaultEngine.savePlaybackPosition(this, id, pos)
    }

    private fun setupList() {
        vaultAdapter = VaultAdapter(this, visibleItems) { dl -> showRowActionDialog(dl) }
        downloadListView.adapter = vaultAdapter

        downloadListView.setOnItemClickListener { _, _, position, _ ->
            val selected = visibleItems[position]
            val title = selected.request.data.toString(Charsets.UTF_8)
            playStream(selected.request.uri.toString(), title, selected.request.id)
        }

        downloadListView.setOnItemLongClickListener { _, _, position, _ ->
            showRowActionDialog(visibleItems[position])
            true
        }
    }

    private fun showRowActionDialog(dl: Download) {
        val title = dl.request.data.toString(Charsets.UTF_8).ifBlank { "Video" }
        val isComplete = dl.state == Download.STATE_COMPLETED
        val isActive = dl.state == Download.STATE_DOWNLOADING || dl.state == Download.STATE_QUEUED

        val choices = if (isComplete) {
            arrayOf("▶ Play Video", "🗑 Delete Video")
        } else {
            arrayOf(
                if (isActive) "⏸ Pause Download" else "▶ Resume Download",
                "▶ Play Preview",
                "🗑 Cancel & Delete"
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(choices) { _, which ->
                if (isComplete) {
                    when (which) {
                        0 -> playStream(dl.request.uri.toString(), title, dl.request.id)
                        1 -> confirmDelete(dl, title)
                    }
                } else {
                    when (which) {
                        0 -> {
                            if (isActive) VaultEngine.pauseDownload(this, dl.request.id)
                            else VaultEngine.resumeDownload(this, dl.request.id)
                            uiHandler.postDelayed({ refreshLists() }, 300L)
                        }
                        1 -> playStream(dl.request.uri.toString(), title, dl.request.id)
                        2 -> confirmDelete(dl, title)
                    }
                }
            }.show()
    }

    private fun confirmDelete(dl: Download, title: String) {
        val size = VaultEngine.formatSize(dl.bytesDownloaded)
        AlertDialog.Builder(this)
            .setTitle("Delete Video?")
            .setMessage("Remove \"$title\" ($size)?")
            .setPositiveButton("Delete") { _, _ ->
                if (currentPlayingId == dl.request.id) {
                    player?.stop()
                    playerContainer.visibility = View.GONE
                }
                VaultEngine.removeDownload(this, dl.request.id)
                uiHandler.postDelayed({ refreshLists() }, 300L)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshLists(initialAutoSelect: Boolean = false) {
        allCachedDownloads = VaultEngine.getAllDownloads(this)
        val downloadingList = allCachedDownloads.filter { it.state != Download.STATE_COMPLETED }
        val videosList = allCachedDownloads.filter { it.state == Download.STATE_COMPLETED }

        if (initialAutoSelect) {
            showingDownloadingTab = downloadingList.isNotEmpty()
            updateBottomNavVisuals()
        }

        tvBadgeDownloading.text = downloadingList.size.toString()
        tvBadgeDownloading.visibility = if (downloadingList.isNotEmpty()) View.VISIBLE else View.GONE

        tvBadgeVideos.text = videosList.size.toString()
        tvBadgeVideos.visibility = if (videosList.isNotEmpty()) View.VISIBLE else View.GONE

        visibleItems.clear()
        visibleItems.addAll(if (showingDownloadingTab) downloadingList else videosList)

        tvEmptyVault.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
        tvEmptyVault.text = if (showingDownloadingTab) {
            "No active downloads.\nTap Home below to capture a video stream."
        } else {
            "No downloaded videos yet."
        }
        vaultAdapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        VaultEngine.getDownloadManager(this).addListener(downloadListener)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        saveCurrentPosition()
        super.onStop()
        VaultEngine.getDownloadManager(this).removeListener(downloadListener)
        uiHandler.removeCallbacks(refreshRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        uiHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
// END OF FILE