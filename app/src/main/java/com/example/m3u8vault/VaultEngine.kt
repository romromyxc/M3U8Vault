package com.example.m3u8vault

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class StreamHeaders(
    val referer: String = "",
    val userAgent: String = "",
    val cookie: String = ""
)

@OptIn(UnstableApi::class)
object VaultEngine {
    const val CHANNEL_ID = "vault_downloads"
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var privateCache: SimpleCache? = null
    private var downloadManager: DownloadManager? = null

    val headerRegistry = ConcurrentHashMap<String, StreamHeaders>()

    fun getPrivateVaultDir(context: Context): File {
        val dir = File(context.filesDir, "private_vault")
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, ".nomedia").createNewFile()
        }
        return dir
    }

    @Synchronized
    fun getDatabase(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    @Synchronized
    fun getPrivateCache(context: Context): SimpleCache {
        return privateCache ?: SimpleCache(
            getPrivateVaultDir(context.applicationContext),
            NoOpCacheEvictor(),
            getDatabase(context)
        ).also { privateCache = it }
    }

    fun buildUpstreamFactory(): DataSource.Factory {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val urlStr = original.url.toString()
                val matched = headerRegistry.entries.firstOrNull { (m3u8Url, _) ->
                    urlStr == m3u8Url || original.url.host == Uri.parse(m3u8Url).host
                }?.value

                val builder = original.newBuilder()
                if (matched != null) {
                    if (matched.referer.isNotBlank()) builder.header("Referer", matched.referer)
                    if (matched.userAgent.isNotBlank()) builder.header("User-Agent", matched.userAgent)
                    if (matched.cookie.isNotBlank()) builder.header("Cookie", matched.cookie)
                }
                chain.proceed(builder.build())
            }
            .build()
        return OkHttpDataSource.Factory(client)
    }

    fun buildCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(getPrivateCache(context))
            .setUpstreamDataSourceFactory(buildUpstreamFactory())
            .setCacheWriteDataSinkFactory(null)
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: DownloadManager(
            context.applicationContext,
            getDatabase(context),
            getPrivateCache(context),
            buildUpstreamFactory(),
            Executors.newFixedThreadPool(6)
        ).apply {
            maxParallelDownloads = 3
            downloadManager = this
        }
    }

    fun enqueueM3u8Download(context: Context, title: String, m3u8Url: String, headers: StreamHeaders) {
        headerRegistry[m3u8Url] = headers
        val request = DownloadRequest.Builder(m3u8Url, Uri.parse(m3u8Url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(title.toByteArray(Charsets.UTF_8))
            .build()

        DownloadService.sendAddDownload(
            context,
            VaultDownloadService::class.java,
            request,
            true
        )
    }

    fun buildOfflineMediaItem(url: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
            .build()
    }
}

@OptIn(UnstableApi::class)
class VaultDownloadService : DownloadService(
    101,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    VaultEngine.CHANNEL_ID,
    android.R.string.untitled,
    0
) {
    override fun getDownloadManager(): DownloadManager = VaultEngine.getDownloadManager(this)
    override fun getScheduler() = PlatformScheduler(this, 1)
    override fun getForegroundNotification(
        downloads: MutableList<androidx.media3.exoplayer.offline.Download>,
        notMetRequirements: Int
    ) = DownloadNotificationHelper(this, VaultEngine.CHANNEL_ID)
        .buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            "Downloading ${downloads.size} HLS video(s) to Private Vault",
            downloads,
            notMetRequirements
        )
}