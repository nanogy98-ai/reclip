package io.nanogy.reclip

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import io.nanogy.reclip.databinding.ItemClipBinding

class ClipAdapter(
    private val onAction: (ClipItem) -> Unit,
    private val onFormatSelected: (clipId: String, formatId: String) -> Unit,
) : ListAdapter<ClipItem, ClipAdapter.ClipViewHolder>(DiffCallback) {
    var mode: DownloadMode = DownloadMode.VIDEO
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemClipBinding.inflate(inflater, parent, false)
        return ClipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ClipViewHolder(
        private val binding: ItemClipBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ClipItem) {
            binding.title.text = item.title
            binding.url.text = item.url
            binding.meta.text = buildMeta(item)
            binding.meta.isVisible = binding.meta.text.isNotBlank()

            binding.status.text = buildStatusText(item)
            binding.error.text = item.errorMessage
            binding.error.isVisible = !item.errorMessage.isNullOrBlank()

            val isDownloading = item.status == ClipStatus.DOWNLOADING
            binding.progress.isVisible = isDownloading
            binding.progress.progress = item.progress.coerceIn(0, 100)
            binding.progressLabel.isVisible = isDownloading
            binding.progressLabel.text = if (isDownloading) {
                "${item.progress.coerceIn(0, 100)}%"
            } else {
                ""
            }

            binding.formatGroup.removeAllViews()
            val showFormats =
                mode == DownloadMode.VIDEO &&
                    item.formats.size > 1 &&
                    item.status != ClipStatus.INFO_ERROR &&
                    item.status != ClipStatus.LOADING_INFO

            binding.formatGroup.isVisible = showFormats
            if (showFormats) {
                item.formats.forEach { format ->
                    val chip = Chip(binding.root.context).apply {
                        text = format.label
                        isCheckable = true
                        isChecked = format.id == item.selectedFormatId
                        setOnClickListener { onFormatSelected(item.id, format.id) }
                    }
                    binding.formatGroup.addView(chip)
                }
            }

            binding.action.text = when (item.status) {
                ClipStatus.LOADING_INFO -> binding.root.context.getString(R.string.fetch)
                ClipStatus.READY -> binding.root.context.getString(R.string.action_download)
                ClipStatus.INFO_ERROR -> binding.root.context.getString(R.string.action_retry)
                ClipStatus.DOWNLOADING -> binding.root.context.getString(R.string.action_cancel)
                ClipStatus.DONE -> binding.root.context.getString(R.string.action_open)
                ClipStatus.ERROR -> binding.root.context.getString(R.string.action_retry)
            }
            binding.action.isEnabled = item.status != ClipStatus.LOADING_INFO
            binding.action.setOnClickListener { onAction(item) }
        }

        private fun buildMeta(item: ClipItem): String {
            val duration = item.durationSeconds?.let { seconds ->
                val minutes = seconds / 60
                val remainder = seconds % 60
                "$minutes:${remainder.toString().padStart(2, '0')}"
            }

            return listOfNotNull(
                item.uploader.takeIf { it.isNotBlank() },
                duration,
                item.extractor.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
        }

        private fun buildStatusText(item: ClipItem): String {
            return when (item.status) {
                ClipStatus.LOADING_INFO -> binding.root.context.getString(R.string.status_fetching)
                ClipStatus.READY -> item.statusLine ?: binding.root.context.getString(R.string.status_ready)
                ClipStatus.INFO_ERROR -> item.statusLine ?: binding.root.context.getString(R.string.status_info_error)
                ClipStatus.DOWNLOADING -> item.statusLine ?: binding.root.context.getString(R.string.status_downloading)
                ClipStatus.DONE -> item.downloadedPath ?: binding.root.context.getString(R.string.status_finished)
                ClipStatus.ERROR -> item.errorMessage ?: binding.root.context.getString(R.string.status_error)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ClipItem>() {
        override fun areItemsTheSame(oldItem: ClipItem, newItem: ClipItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ClipItem, newItem: ClipItem): Boolean {
            return oldItem == newItem
        }
    }
}
