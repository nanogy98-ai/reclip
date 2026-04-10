package io.nanogy.reclip

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object DownloaderEngine {
    private val initLock = Mutex()
    @Volatile
    private var initialized = false

    suspend fun ensureReady(context: Context) {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
            initialized = true
        }
    }

    fun buildDownloadRequest(
        url: String,
        mode: DownloadMode,
        formatId: String?,
        outputTemplate: String,
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("-o", outputTemplate)

            when {
                mode == DownloadMode.AUDIO -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                }

                !formatId.isNullOrBlank() -> {
                    addOption("-f", "$formatId+bestaudio/best")
                    addOption("--merge-output-format", "mp4")
                }

                else -> {
                    addOption("-f", "bestvideo+bestaudio/best")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }
    }

    fun sanitizeTitle(rawTitle: String, fallback: String): String {
        val cleaned = rawTitle
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)

        return cleaned.ifBlank { fallback }
    }

    fun findCompletedFile(outputDirectory: File, baseName: String): File? {
        return outputDirectory
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("$baseName.") }
            ?.maxByOrNull { it.lastModified() }
    }
}
