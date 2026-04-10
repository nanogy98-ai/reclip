package io.nanogy.reclip

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object DownloaderEngine {
    private val initLock = Mutex()
    private val updateLock = Mutex()
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

    fun outputDirectoryFor(mode: DownloadMode): File {
        val publicDir = when (mode) {
            DownloadMode.VIDEO -> Environment.DIRECTORY_MOVIES
            DownloadMode.AUDIO -> Environment.DIRECTORY_MUSIC
        }

        return File(
            Environment.getExternalStoragePublicDirectory(publicDir),
            "ReClip",
        )
    }

    suspend fun maybeRefreshExtractor(context: Context) {
        updateLock.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val lastCheckAt = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
            val now = System.currentTimeMillis()

            if (now - lastCheckAt < UPDATE_INTERVAL_MS) {
                return
            }

            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, now).apply()
            runCatching {
                YoutubeDL.updateYoutubeDL(
                    context.applicationContext,
                    YoutubeDL.UpdateChannel.STABLE,
                )
            }
        }
    }

    private const val PREFS_NAME = "reclip_android"
    private const val KEY_LAST_UPDATE_CHECK_AT = "last_ytdlp_update_check_at"
    private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
}
