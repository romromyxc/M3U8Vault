package com.example.m3u8vault

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class StreamOption(
    val url: String,
    val qualityBadge: String,
    val infoLine: String,
    val isLikelyAd: Boolean,
    val sortScore: Int,
    var selected: Boolean
)

object StreamInspector {

    fun inspectAll(urls: List<String>, headers: StreamHeaders): List<StreamOption> {
        return runCatching {
            val results = mutableListOf<StreamOption>()
            val seen = HashSet<String>()

            for (rawUrl in urls) {
                if (rawUrl.isBlank() || !seen.add(rawUrl)) continue
                val parsed = runCatching { inspectSingle(rawUrl, headers) }
                    .getOrElse { listOf(fallbackOption(rawUrl)) }
                for (item in parsed) {
                    if (seen.add(item.url) || item.url == rawUrl) {
                        results.add(item)
                    }
                }
            }

            if (results.isEmpty()) {
                urls.filter { it.isNotBlank() }.map { fallbackOption(it) }
            } else {
                val sorted = results.sortedByDescending { it.sortScore }
                val firstGood = sorted.indexOfFirst { !it.isLikelyAd }
                sorted.forEachIndexed { idx, opt ->
                    opt.selected = (idx == firstGood) || (firstGood == -1 && idx == 0)
                }
                sorted
            }
        }.getOrElse {
            urls.filter { it.isNotBlank() }.map { fallbackOption(it) }
        }
    }

    private fun fallbackOption(m3u8Url: String): StreamOption {
        val guessed = guessQualityFromUrl(m3u8Url)
        return StreamOption(
            url = m3u8Url,
            qualityBadge = guessed.first,
            infoLine = "HLS Stream • Ready to queue",
            isLikelyAd = false,
            sortScore = guessed.second,
            selected = true
        )
    }

    private fun inspectSingle(m3u8Url: String, headers: StreamHeaders): List<StreamOption> {
        val body = fetchPlaylistText(m3u8Url, headers)
        if (body.isNullOrBlank() || !body.contains("#EXTM3U")) {
            return listOf(fallbackOption(m3u8Url))
        }

        if (body.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(m3u8Url, body)
            if (variants.isNotEmpty()) return variants
        }

        var totalSec = 0.0
        var segments = 0
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val num = trimmed.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull()
                if (num != null) totalSec += num
                segments++
            }
        }

        val isAd = totalSec in 0.1..25.0 && segments in 1..6
        val guessed = guessQualityFromUrl(m3u8Url)
        val badge = if (isAd) "⚠ Short Clip / Ad (${totalSec.toInt()}s)" else guessed.first
        val durText = formatDuration(totalSec.toInt())
        val info = if (isAd) {
            "$segments segments • Likely pre-roll ad"
        } else {
            "Duration: $durText • $segments segments"
        }

        return listOf(
            StreamOption(
                url = m3u8Url,
                qualityBadge = badge,
                infoLine = info,
                isLikelyAd = isAd,
                sortScore = if (isAd) -100 else (guessed.second + totalSec.toInt()),
                selected = !isAd
            )
        )
    }

    private fun parseMasterVariants(baseUrl: String, body: String): List<StreamOption> {
        val list = mutableListOf<StreamOption>()
        val lines = body.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val nextUri = lines.drop(i + 1)
                .firstOrNull { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                ?.trim() ?: continue

            val resolvedUrl = resolveUrl(baseUrl, nextUri)
            val resMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
            val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)

            val height = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val width = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val label = when {
                height >= 2160 -> "4K Ultra HD (${width}x$height)"
                height >= 1080 -> "1080p Full HD (${width}x$height)"
                height >= 720 -> "720p HD (${width}x$height)"
                height >= 480 -> "480p SD (${width}x$height)"
                height > 0 -> "${height}p (${width}x$height)"
                else -> guessQualityFromUrl(resolvedUrl).first
            }

            val bitrateText = if (bw > 0) {
                String.format(Locale.US, " • %.1f Mbps", bw / 1_000_000.0)
            } else ""

            val score = (if (height > 0) height * 10 else 1000) + (bw / 10_000L).toInt()
            list.add(
                StreamOption(
                    url = resolvedUrl,
                    qualityBadge = label,
                    infoLine = "Direct Video Track$bitrateText",
                    isLikelyAd = false,
                    sortScore = score,
                    selected = false
                )
            )
        }
        return list
    }

    private fun fetchPlaylistText(urlStr: String, headers: StreamHeaders): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                if (headers.referer.isNotBlank()) setRequestProperty("Referer", headers.referer)
                if (headers.userAgent.isNotBlank()) setRequestProperty("User-Agent", headers.userAgent)
                if (headers.cookie.isNotBlank()) setRequestProperty("Cookie", headers.cookie)
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText().take(32_768) }
            } else null
        }.getOrNull()
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URL(URL(base), relative).toString() }.getOrElse {
            runCatching { Uri.parse(base).buildUpon().path(relative).build().toString() }
                .getOrDefault(relative)
        }
    }

    private fun guessQualityFromUrl(url: String): Pair<String, Int> {
        val u = url.lowercase()
        return when {
            u.contains("2160") || u.contains("4k") -> "4K Ultra HD" to 4000
            u.contains("1080") -> "1080p Full HD" to 3000
            u.contains("720") -> "720p HD" to 2000
            u.contains("480") -> "480p SD" to 1200
            u.contains("360") -> "360p Low" to 800
            u.contains("master") -> "Master Playlist (Auto HD)" to 2500
            else -> "HLS Video Stream" to 1500
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "Live / Unknown"
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hrs > 0) String.format(Locale.US, "%dh %02dm %02ds", hrs, mins, secs)
        else String.format(Locale.US, "%dm %02ds", mins, secs)
    }
}
// END OF FILE
