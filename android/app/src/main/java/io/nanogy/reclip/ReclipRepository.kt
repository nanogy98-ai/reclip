package io.nanogy.reclip

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReclipRepository(
    private val application: Application,
) {
    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            DownloaderEngine.ensureReady(application)
        }
    }

    suspend fun fetchInfo(url: String): Result<ClipDetails> = withContext(Dispatchers.IO) {
        runCatching {
            DownloaderEngine.ensureReady(application)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
            }

            val info = YoutubeDL.getInfo(request)
            val formats = info.formats
                .orEmpty()
                .asSequence()
                .filter { format ->
                    format.height > 0 &&
                        !format.formatId.isNullOrBlank() &&
                        !format.vcodec.isNullOrBlank() &&
                        format.vcodec != "none"
                }
                .groupBy { it.height }
                .mapNotNull { (height, candidates) ->
                    val best = candidates.maxByOrNull { it.tbr }
                    val formatId = best?.formatId ?: return@mapNotNull null
                    ClipFormat(
                        id = formatId,
                        label = "${height}p",
                        height = height,
                    )
                }
                .sortedByDescending { it.height }
                .toList()

            ClipDetails(
                title = info.title.orEmpty().ifBlank { "Untitled clip" },
                uploader = info.uploader.orEmpty(),
                extractor = info.extractorKey ?: info.extractor.orEmpty(),
                durationSeconds = info.duration.takeIf { it > 0 },
                formats = formats,
            )
        }
    }
}
