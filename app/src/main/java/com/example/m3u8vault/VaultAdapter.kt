package com.example.m3u8vault

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class VaultAdapter(
    private val context: Context,
    private val items: List<Download>,
    private val onRowMenu: (Download) -> Unit
) : BaseAdapter() {

    private data class SpeedSample(
        val bytes: Long,
        val timeMs: Long,
        val speedText: String,
        val bytesPerSec: Double
    )

    private val speedHistory = ConcurrentHashMap<String, SpeedSample>()
    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Download = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_vault_video, parent, false)

        val dl = items[position]
        val tvBadge = view.findViewById<TextView>(R.id.tvThumbBadge)
        val tvTitle = view.findViewById<TextView>(R.id.tvVideoTitle)
        val pbProgress = view.findViewById<ProgressBar>(R.id.pbItemProgress)
        val tvStatus = view.findViewById<TextView>(R.id.tvVideoStatus)
        val btnMore = view.findViewById<Button>(R.id.btnRowMore)

        val rawTitle = dl.request.data.toString(Charsets.UTF_8).ifBlank { "HLS_Video_${position + 1}" }
        tvTitle.text = rawTitle

        val sizeStr = VaultEngine.formatSize(dl.bytesDownloaded)
        val pctFloat = dl.percentDownloaded
        val pct = pctFloat.coerceAtLeast(0f).toInt().coerceAtMost(100)

        when (dl.state) {
            Download.STATE_COMPLETED -> {
                speedHistory.remove(dl.request.id)
                tvBadge.text = "✔"
                tvBadge.setTextColor(Color.parseColor("#00FF9C"))
                pbProgress.visibility = View.GONE
                tvStatus.setTextColor(Color.parseColor("#00FF9C"))
                tvStatus.text = "Completed  •  $sizeStr  •  ${dateFormat.format(Date(dl.updateTimeMs))}"
            }
            Download.STATE_DOWNLOADING -> {
                val sample = updateSpeedSample(dl.request.id, dl.bytesDownloaded)
                val eta = estimateRemainingTime(dl.bytesDownloaded, pctFloat, sample.bytesPerSec)
                tvBadge.text = "⬇"
                tvBadge.setTextColor(Color.parseColor("#00E5FF"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.progress = pct
                tvStatus.setTextColor(Color.parseColor("#00E5FF"))
                tvStatus.text = "$pct%  •  ${sample.speedText}  •  $sizeStr$eta"
            }
            Download.STATE_STOPPED -> {
                speedHistory.remove(dl.request.id)
                tvBadge.text = "⏸"
                tvBadge.setTextColor(Color.parseColor("#FF6EB4"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.progress = pct
                tvStatus.setTextColor(Color.parseColor("#FF6EB4"))
                tvStatus.text = "$pct%  •  Downloads paused  •  $sizeStr"
            }
            Download.STATE_FAILED -> {
                speedHistory.remove(dl.request.id)
                tvBadge.text = "!"
                tvBadge.setTextColor(Color.parseColor("#FF5370"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.progress = pct
                tvStatus.setTextColor(Color.parseColor("#FF5370"))
                tvStatus.text = "Failed (Code ${dl.failureReason})  •  $sizeStr"
            }
            else -> {
                tvBadge.text = "⏳"
                tvBadge.setTextColor(Color.parseColor("#BB9AF7"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.progress = pct
                tvStatus.setTextColor(Color.parseColor("#BB9AF7"))
                tvStatus.text = "Queued ($pct%)  •  $sizeStr"
            }
        }

        btnMore.setOnClickListener { onRowMenu(dl) }
        return view
    }

    private fun updateSpeedSample(id: String, currentBytes: Long): SpeedSample {
        val now = System.currentTimeMillis()
        val prev = speedHistory[id]
        if (prev == null) {
            return SpeedSample(currentBytes, now, "Starting...", 0.0).also { speedHistory[id] = it }
        }
        val elapsedMs = now - prev.timeMs
        if (elapsedMs < 800L) return prev

        val deltaBytes = (currentBytes - prev.bytes).coerceAtLeast(0L)
        val bps = (deltaBytes * 1000.0) / elapsedMs
        val formatted = when {
            bps >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
            bps >= 1024 -> String.format(Locale.US, "%.0f KB/s", bps / 1024.0)
            else -> "${bps.toInt()} B/s"
        }
        return SpeedSample(currentBytes, now, formatted, bps).also { speedHistory[id] = it }
    }

    private fun estimateRemainingTime(downloadedBytes: Long, pct: Float, bps: Double): String {
        if (pct <= 1f || pct >= 100f || bps <= 100.0 || downloadedBytes <= 0L) return ""
        val totalEst = (downloadedBytes * 100.0) / pct
        val remBytes = (totalEst - downloadedBytes).coerceAtLeast(0.0)
        val sec = (remBytes / bps).toLong()
        return when {
            sec < 60 -> "  •  ⏱ ${sec}s"
            sec < 3600 -> "  •  ⏱ ${sec / 60}m ${sec % 60}s"
            else -> "  •  ⏱ ${sec / 3600}h ${(sec % 3600) / 60}m"
        }
    }
}
// END OF FILE
