package io.nanogy.reclip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)
        val rawTitle = inputData.getString(KEY_TITLE).orEmpty()
        val modeName = inputData.getString(KEY_MODE)
        val requestId = inputData.getString(KEY_REQUEST_ID)
        val formatId = inputData.getString(KEY_FORMAT_ID)

        if (url.isNullOrBlank() || modeName.isNullOrBlank() || requestId.isNullOrBlank()) {
            return@withContext failureResult("Missing download arguments.")
        }

        val mode = runCatching { DownloadMode.valueOf(modeName) }
            .getOrDefault(DownloadMode.VIDEO)

        createNotificationChannel()
        setForeground(createForegroundInfo(0, applicationContext.getString(R.string.download_preparing)))

        try {
            DownloaderEngine.ensureReady(applicationContext)
            currentCoroutineContext().job.invokeOnCompletion { throwable: Throwable? ->
                if (throwable != null) {
                    runCatching { YoutubeDL.destroyProcessById(id.toString()) }
                }
            }

            val outputDirectory = DownloaderEngine.outputDirectoryFor(mode).apply {
                mkdirs()
            }

            if (!outputDirectory.exists()) {
                return@withContext failureResult("Could not create the temporary download folder.")
            }

            val safeTitle = DownloaderEngine.sanitizeTitle(rawTitle, "reclip")
            val baseName = "$safeTitle-$requestId"
            val outputTemplate = File(outputDirectory, "$baseName.%(ext)s").absolutePath
            val request = DownloaderEngine.buildDownloadRequest(url, mode, formatId, outputTemplate)

            YoutubeDL.execute(request, id.toString()) { progress, _, line ->
                val percent = progress
                    .takeIf { it >= 0f }
                    ?.toInt()
                    ?.coerceIn(0, 99)
                    ?: 0
                val statusLine = line.trim().ifBlank {
                    applicationContext.getString(R.string.download_running)
                }
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS to percent,
                        KEY_STATUS_LINE to statusLine,
                    ),
                )
                notifyIfAllowed(percent, statusLine, isComplete = false)
            }

            val completedFile = DownloaderEngine.findCompletedFile(outputDirectory, baseName)
                ?: return@withContext failureResult("Download finished but the output file could not be found.")

            val savedLocation = publishToMediaLibrary(
                sourceFile = completedFile,
                displayName = completedFile.name,
                mode = mode,
            )

            notifyIfAllowed(
                progress = 100,
                statusText = applicationContext.getString(R.string.download_complete),
                isComplete = true,
            )

            androidx.work.ListenableWorker.Result.success(
                workDataOf(
                    KEY_FILE_NAME to completedFile.name,
                    KEY_FILE_PATH to savedLocation,
                ),
            )
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw cancelled
        } catch (error: Exception) {
            failureResult(error.message ?: "Download failed.")
        }
    }

    private fun failureResult(message: String): androidx.work.ListenableWorker.Result {
        runCatching {
            NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        }
        return androidx.work.ListenableWorker.Result.failure(
            workDataOf(KEY_ERROR to message),
        )
    }

    private fun createForegroundInfo(progress: Int, statusText: String): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                buildNotification(progress, statusText, isComplete = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                notificationId,
                buildNotification(progress, statusText, isComplete = false),
            )
        }
    }

    private fun buildNotification(
        progress: Int,
        statusText: String,
        isComplete: Boolean,
    ): Notification {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(!isComplete)
            .setOnlyAlertOnce(true)
            .setAutoCancel(isComplete)
            .setProgress(100, progress, progress <= 0 && !isComplete)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.download_channel_description)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun notifyIfAllowed(
        progress: Int,
        statusText: String,
        isComplete: Boolean,
    ) {
        if (!canPostNotifications()) return

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                notificationId,
                buildNotification(progress, statusText, isComplete),
            )
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun publishToMediaLibrary(
        sourceFile: File,
        displayName: String,
        mode: DownloadMode,
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertIntoMediaStore(sourceFile, displayName, mode).toString()
        } else {
            copyToLegacyPublicFolder(sourceFile, displayName, mode)
        }
    }

    private fun insertIntoMediaStore(
        sourceFile: File,
        displayName: String,
        mode: DownloadMode,
    ): Uri {
        val mimeType = mimeTypeFor(displayName, mode)
        val (collection, relativePath) = when (mode) {
            DownloadMode.VIDEO -> {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/ReClip"
            }

            DownloadMode.AUDIO -> {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/ReClip"
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = applicationContext.contentResolver
        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create media store record.")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open media destination.")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            sourceFile.delete()
            return itemUri
        } catch (error: Exception) {
            resolver.delete(itemUri, null, null)
            throw error
        }
    }

    private fun copyToLegacyPublicFolder(
        sourceFile: File,
        displayName: String,
        mode: DownloadMode,
    ): String {
        val baseDirectory = when (mode) {
            DownloadMode.VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            DownloadMode.AUDIO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }

        val outputDirectory = File(baseDirectory, "ReClip").apply { mkdirs() }
        if (!outputDirectory.exists()) {
            throw IOException("Could not create public media folder.")
        }

        val targetFile = File(outputDirectory, displayName)
        sourceFile.copyTo(targetFile, overwrite = true)
        MediaScannerConnection.scanFile(
            applicationContext,
            arrayOf(targetFile.absolutePath),
            arrayOf(mimeTypeFor(displayName, mode)),
            null,
        )
        sourceFile.delete()
        return targetFile.absolutePath
    }

    private fun mimeTypeFor(displayName: String, mode: DownloadMode): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when {
            extension == "mp3" -> "audio/mpeg"
            extension == "mp4" -> "video/mp4"
            mode == DownloadMode.AUDIO -> "audio/*"
            else -> "video/*"
        }
    }

    private val notificationId: Int
        get() = abs(id.hashCode())

    companion object {
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_MODE = "mode"
        const val KEY_FORMAT_ID = "format_id"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS_LINE = "status_line"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "reclip_downloads"
    }
}
