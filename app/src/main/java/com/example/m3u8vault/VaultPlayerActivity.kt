package com.example.m3u8vault

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class VaultPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var vaultListSection: LinearLayout
    private lateinit var downloadListView: ListView
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var tvVolumePct: TextView
    private lateinit var btnFullscreen: Button
    private lateinit var audioManager: AudioManager

    private val vaultItems = mutableListOf<Download>()
    private lateinit var vaultAdapter: VaultAdapter
    private var isFullscreen = false

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshVaultItems()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_vault_player)

        playerContainer = findViewById(R.id.playerContainer)
        playerView = findViewById(R.id.playerView)
        vaultListSection = findViewById(R.id.vaultListSection)
        downloadListView = findViewById(R.id.downloadListView)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        tvVolumePct = findViewById(R.id.tvVolumePct)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupPlayerControls()
        setupVolumeBar()
        setupVaultList()
        authenticateVaultAccess()
    }

    private fun setupPlayerControls() {
        findViewById<Button>(R.id.btnRewind).setOnClickListener {
            player?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }

        findViewById<Button>(R.id.btnFastForward).setOnClickListener {
            player?.let {
                val target = it.currentPosition + 10_000L
                it.seekTo(if (it.duration > 0) target.coerceAtMost(it.duration) else target)
            }
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
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val params = playerContainer.layoutParams as LinearLayout.LayoutParams

        if (isFullscreen) {
            vaultListSection.visibility = View.GONE
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            btnFullscreen.text = "⛶ Exit"
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            vaultListSection.visibility = View.VISIBLE
            params.height = (250 * resources.displayMetrics.density).toInt()
            btnFullscreen.text = "⛶ Full"
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        playerContainer.layoutParams = params
    }

    private fun setupVolumeBar() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSeekBar.max = maxVol
        syncVolumeUi()

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    val pct = if (maxVol > 0) (progress * 100) / maxVol else 0
                    tvVolumePct.text = "$pct%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun syncVolumeUi() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeekBar.progress = curVol
        val pct = if (maxVol > 0) (curVol * 100) / maxVol else 0
        tvVolumePct.text = "$pct%"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            syncVolumeUi()
        }
        return handled
    }

    private fun authenticateVaultAccess() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                initExoPlayer()
                refreshVaultItems()
                refreshHandler.postDelayed(refreshRunnable, 1000L)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@VaultPlayerActivity, "Vault Locked: $errString", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Video Vault")
            .setSubtitle("Authenticate to view offline M3U8 downloads")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
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
            refreshHandler.postDelayed({ refreshVaultItems() }, 300L)
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
                .setMessage("Remove \"$title\" from your Private Vault?")
                .setPositiveButton("Delete") { _, _ ->
                    VaultEngine.removeDownload(this, selected.request.id)
                    refreshHandler.postDelayed({ refreshVaultItems() }, 300L)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun refreshVaultItems() {
        vaultItems.clear()
        val cursor = VaultEngine.getDownloadManager(this).downloadIndex.getDownloads()
        cursor.use {
            while (it.moveToNext()) vaultItems.add(it.download)
        }
        vaultItems.sortByDescending { it.updateTimeMs }
        vaultAdapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
// END OF FILE
