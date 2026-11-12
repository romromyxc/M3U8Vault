package com.example.m3u8vault

import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class VaultPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var downloadListView: ListView
    private val vaultItems = mutableListOf<Download>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_vault_player)

        playerView = findViewById(R.id.playerView)
        downloadListView = findViewById(R.id.downloadListView)

        authenticateVaultAccess()
    }

    private fun authenticateVaultAccess() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                initExoPlayer()
                loadVaultVideos()
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
            .build()
            .also { exo ->
                playerView.player = exo
            }
    }

    private fun loadVaultVideos() {
        vaultItems.clear()
        val cursor = VaultEngine.getDownloadManager(this).downloadIndex.getDownloads()
        cursor.use {
            while (it.moveToNext()) {
                vaultItems.add(it.download)
            }
        }

        val labels = vaultItems.map { dl ->
            val title = dl.request.data.toString(Charsets.UTF_8).ifBlank { dl.request.id }
            val status = when (dl.state) {
                Download.STATE_COMPLETED -> "Ready (100%)"
                Download.STATE_DOWNLOADING -> "Downloading (${dl.percentDownloaded.toInt()}%)"
                Download.STATE_FAILED -> "Failed (Code ${dl.failureReason})"
                else -> "Queued"
            }
            "$title — $status"
        }

        downloadListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
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
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}