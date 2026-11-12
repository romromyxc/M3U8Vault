package com.example.m3u8vault

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: View
    private lateinit var tvGestureHud: TextView
    private lateinit var vaultListSection: LinearLayout
    private lateinit var downloadListView: ListView
    private lateinit var btnFullscreen: Button
    private lateinit var audioManager: AudioManager

    private val vaultItems = mutableListOf<Download>()
    private lateinit var vaultAdapter: VaultAdapter
    private var isFullscreen = false
    private var volumeAccum = 0f

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { tvGestureHud.visibility = View.GONE }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshVaultItems()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) {
            runOnUiThread { refreshVaultItems() }
        }
        override fun onDownloadRemoved(dm: DownloadManager, download: Download) {
            runOnUiThread { refreshVaultItems() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_vault_player)

        playerContainer = findViewById(R.id.playerContainer)
        playerView = findViewById(R.id.playerView)
        gestureOverlay = findViewById(R.id.gestureOverlay)
        tvGestureHud = findViewById(R.id.tvGestureHud)
        vaultListSection = findViewById(R.id.vaultListSection)
        downloadListView = findViewById(R.id.downloadListView)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupGestures()
        setupPlayerButtons()
        setupVaultList()
        initExoPlayer()
        refreshVaultItems()
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
                val isRightSide = e.x > gestureOverlay.width / 2f
                player?.let { exo ->
                    if (isRightSide) {
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

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val start = e1 ?: return false
                if (abs(distanceY) <= abs(distanceX)) return false
                val h = gestureOverlay.height.coerceAtLeast(1)

                if (start.x > gestureOverlay.width / 2f) {
                    // Right side swipe up/down -> Volume
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
                    // Left side swipe up/down -> Brightness
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

    private fun setupPlayerButtons() {
        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            val isPortrait = requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            requestedOrientation = if (isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        btnFullscreen.setOnClickListener { toggleFullscreen() }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val params = playerContainer.layoutParams as LinearLayout.LayoutParams

        if (isFullscreen) {
            vaultListSection.visibility = View.GONE
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            btnFullscreen.text = "✕"
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            vaultListSection.visibility = View.VISIBLE
            params.height = (240 * resources.displayMetrics.density).toInt()
            btnFullscreen.text = "⛶"
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        playerContainer.layoutParams = params
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

    private fun setupVaultList() {
        vaultAdapter = VaultAdapter(this, vaultItems) { dl ->
            if (dl.state == Download.STATE_DOWNLOADING || dl.state == Download.STATE_QUEUED) {
                VaultEngine.pauseDownload(this, dl.request.id)
            } else {
                VaultEngine.resumeDownload(this, dl.request.id)
            }
            uiHandler.postDelayed({ refreshVaultItems() }, 300L)
        }
        downloadListView.adapter = vaultAdapter

        downloadListView.setOnItemClickListener { _, _, position, _ ->
            val selected = vaultItems[position]
            val title = selected.request.data.toString(Charsets.UTF_8)
            val mediaItem = VaultEngine.buildOfflineMediaItem(selected.request.uri.toString(), title)
            player?.apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
        }

        downloadListView.setOnItemLongClickListener { _, _, position, _ ->
            val selected = vaultItems[position]
            val title = selected.request.data.toString(Charsets.UTF_8).ifBlank { "Video" }
            AlertDialog.Builder(this)
                .setTitle("Delete Video?")
                .setMessage("Remove \"$title\" from your Vault?")
                .setPositiveButton("Delete") { _, _ ->
                    VaultEngine.removeDownload(this, selected.request.id)
                    uiHandler.postDelayed({ refreshVaultItems() }, 300L)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun refreshVaultItems() {
        vaultItems.clear()
        vaultItems.addAll(VaultEngine.getAllDownloads(this))
        vaultAdapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        VaultEngine.getDownloadManager(this).addListener(downloadListener)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        super.onStop()
        VaultEngine.getDownloadManager(this).removeListener(downloadListener)
        uiHandler.removeCallbacks(refreshRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
// END OF FILE
