package io.nanogy.reclip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
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

            val outputDirectory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                OUTPUT_DIR_NAME,
            ).apply { mkdirs() }

            if (!outputDirectory.exists()) {
                return@withContext failureResult("Could not create the Downloads/ReClip folder.")
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
                NotificationManagerCompat.from(applicationContext).notify(
                    notificationId,
                    buildNotification(percent, statusLine, isComplete = false),
                )
            }

            val completedFile = DownloaderEngine.findCompletedFile(outputDirectory, baseName)
                ?: return@withContext failureResult("Download finished but the output file could not be found.")

            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(completedFile.absolutePath),
                null,
                null,
            )

            NotificationManagerCompat.from(applicationContext).notify(
                notificationId,
                buildNotification(
                    progress = 100,
                    statusText = applicationContext.getString(R.string.download_complete),
                    isComplete = true,
                ),
            )

            androidx.work.ListenableWorker.Result.success(
                workDataOf(
                    KEY_FILE_NAME to completedFile.name,
                    KEY_FILE_PATH to completedFile.absolutePath,
                ),
            )
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw cancelled
        } catch (error: Exception) {
            failureResult(error.message ?: "Download failed.")
        }
    }

    private fun failureResult(message: String): androidx.work.ListenableWorker.Result {
        NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        return androidx.work.ListenableWorker.Result.failure(
            workDataOf(KEY_ERROR to message),
        )
    }

    private fun createForegroundInfo(progress: Int, statusText: String): ForegroundInfo {
        return ForegroundInfo(
            notificationId,
            buildNotification(progress, statusText, isComplete = false),
        )
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
        private const val OUTPUT_DIR_NAME = "ReClip"
    }
}
