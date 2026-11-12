package com.example.m3u8vault

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
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
    private val onTogglePauseResume: (Download) -> Unit
) : BaseAdapter() {

    private data class SpeedSample(val bytes: Long, val timeMs: Long, val speedText: String)

    private val speedHistory = ConcurrentHashMap<String, SpeedSample>()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Download = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_vault_video, parent, false)

        val dl = items[position]
        val tvTitle = view.findViewById<TextView>(R.id.tvVideoTitle)
        val tvMeta = view.findViewById<TextView>(R.id.tvVideoMeta)
        val tvStatus = view.findViewById<TextView>(R.id.tvVideoStatus)
        val btnAction = view.findViewById<Button>(R.id.btnRowAction)

        val rawTitle = dl.request.data.toString(Charsets.UTF_8).ifBlank { "HLS_Video_${position + 1}" }
        tvTitle.text = rawTitle

        val dateStr = dateFormat.format(Date(dl.updateTimeMs))
        tvMeta.text = "Format: HLS (M3U8)  •  $dateStr"

        val sizeStr = VaultEngine.formatSize(dl.bytesDownloaded)
        val pct = dl.percentDownloaded.coerceAtLeast(0f).toInt()

        when (dl.state) {
            Download.STATE_COMPLETED -> {
                speedHistory.remove(dl.request.id)
                tvStatus.setTextColor(Color.parseColor("#66BB6A"))
                tvStatus.text = "✔ Completed (100%)  •  Size: $sizeStr"
                btnAction.visibility = View.GONE
            }
            Download.STATE_DOWNLOADING -> {
                val speed = computeLiveSpeed(dl.request.id, dl.bytesDownloaded)
                tvStatus.setTextColor(Color.parseColor("#4FC3F7"))
                tvStatus.text = "⬇ $pct%  •  $speed  •  $sizeStr"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "Pause"
            }
            Download.STATE_STOPPED -> {
                speedHistory.remove(dl.request.id)
                tvStatus.setTextColor(Color.parseColor("#FFB74D"))
                tvStatus.text = "⏸ Paused ($pct%)  •  $sizeStr"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "Resume"
            }
            Download.STATE_FAILED -> {
                speedHistory.remove(dl.request.id)
                tvStatus.setTextColor(Color.parseColor("#EF5350"))
                tvStatus.text = "✖ Failed (Code ${dl.failureReason})  •  $sizeStr"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "Retry"
            }
            else -> {
                tvStatus.setTextColor(Color.parseColor("#BDBDBD"))
                tvStatus.text = "⏳ Queued ($pct%)  •  $sizeStr"
                btnAction.visibility = View.VISIBLE
                btnAction.text = "Pause"
            }
        }

        btnAction.setOnClickListener { onTogglePauseResume(dl) }
        return view
    }

    private fun computeLiveSpeed(id: String, currentBytes: Long): String {
        val now = System.currentTimeMillis()
        val prev = speedHistory[id]
        if (prev == null) {
            speedHistory[id] = SpeedSample(currentBytes, now, "Calculating...")
            return "Calculating..."
        }
        val elapsedMs = now - prev.timeMs
        if (elapsedMs < 800L) return prev.speedText

        val deltaBytes = (currentBytes - prev.bytes).coerceAtLeast(0L)
        val bytesPerSec = (deltaBytes * 1000.0) / elapsedMs
        val formatted = when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "${bytesPerSec.toInt()} B/s"
        }
        speedHistory[id] = SpeedSample(currentBytes, now, formatted)
        return formatted
    }
}
// END OF FILE
