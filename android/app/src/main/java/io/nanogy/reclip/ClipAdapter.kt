package io.nanogy.reclip

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.load
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
            if (item.thumbnailUrl.isNullOrBlank()) {
                binding.thumbnail.setImageDrawable(null)
            } else {
                binding.thumbnail.load(item.thumbnailUrl) {
                    crossfade(true)
                }
            }

            binding.title.text = item.title
            binding.meta.text = buildMeta(item)
            binding.meta.isVisible = binding.meta.text.isNotBlank()

            binding.status.text = buildStatusText(item)
            binding.error.text = item.errorMessage
            binding.error.isVisible = !item.errorMessage.isNullOrBlank()

            val showProgress = item.status == ClipStatus.DOWNLOADING || item.status == ClipStatus.DONE
            binding.progress.isVisible = showProgress
            binding.progress.progress = when (item.status) {
                ClipStatus.DONE -> 100
                else -> item.progress.coerceIn(0, 100)
            }
            binding.progressLabel.isVisible = showProgress
            binding.progressLabel.text = when (item.status) {
                ClipStatus.DONE -> "100%"
                ClipStatus.DOWNLOADING -> "${item.progress.coerceIn(0, 100)}%"
                else -> ""
            }

            val showFormats =
                mode == DownloadMode.VIDEO &&
                    item.formats.size > 1 &&
                    (item.status == ClipStatus.READY || item.status == ClipStatus.ERROR)

            binding.formatGroup.isVisible = showFormats
            if (showFormats) {
                binding.formatGroup.removeAllViews()
                item.formats.forEach { format ->
                    val chip = Chip(binding.root.context).apply {
                        text = format.label
                        tag = format.id
                        isCheckable = true
                        isChecked = format.id == item.selectedFormatId
                        chipBackgroundColor =
                            ContextCompat.getColorStateList(
                                context,
                                R.color.mode_button_bg,
                            )
                        setTextColor(
                            ContextCompat.getColorStateList(
                                context,
                                R.color.mode_button_text,
                            ),
                        )
                        chipStrokeWidth = 0f
                        checkedIcon = null
                        setOnClickListener { onFormatSelected(item.id, format.id) }
                    }
                    binding.formatGroup.addView(chip)
                }
            } else {
                binding.formatGroup.removeAllViews()
            }

            val actionIcon = when (item.status) {
                ClipStatus.LOADING_INFO -> R.drawable.ic_download_arrow
                ClipStatus.READY -> R.drawable.ic_download_arrow
                ClipStatus.INFO_ERROR -> R.drawable.ic_retry
                ClipStatus.DOWNLOADING -> R.drawable.ic_close
                ClipStatus.DONE -> R.drawable.ic_check_circle
                ClipStatus.ERROR -> R.drawable.ic_retry
            }
            val actionLabel = when (item.status) {
                ClipStatus.LOADING_INFO -> binding.root.context.getString(R.string.fetch)
                ClipStatus.READY -> binding.root.context.getString(R.string.action_start_download)
                ClipStatus.INFO_ERROR -> binding.root.context.getString(R.string.action_retry_download)
                ClipStatus.DOWNLOADING -> binding.root.context.getString(R.string.action_cancel_download)
                ClipStatus.DONE -> binding.root.context.getString(R.string.action_open_file)
                ClipStatus.ERROR -> binding.root.context.getString(R.string.action_retry_download)
            }
            val actionColor = when (item.status) {
                ClipStatus.DOWNLOADING -> R.color.reclip_surface_variant
                ClipStatus.INFO_ERROR, ClipStatus.ERROR -> R.color.reclip_error
                else -> R.color.reclip_cyan_bright
            }
            val actionIconTint = when (item.status) {
                ClipStatus.DOWNLOADING -> R.color.reclip_white
                else -> R.color.reclip_bg
            }

            binding.action.icon = ContextCompat.getDrawable(binding.root.context, actionIcon)
            binding.action.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(binding.root.context, actionIconTint),
            )
            binding.action.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(binding.root.context, actionColor),
            )
            binding.action.contentDescription = actionLabel
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
                ClipStatus.DONE -> binding.root.context.getString(R.string.status_completed)
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
