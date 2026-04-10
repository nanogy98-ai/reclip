package io.nanogy.reclip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ReclipRepository(application)
    private val workManager = WorkManager.getInstance(application)
    private val workWatchers = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.warmUp()
        }
    }

    fun setMode(mode: DownloadMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun fetchUrls(rawInput: String) {
        val urls = parseUrls(rawInput)
        if (urls.isEmpty()) return

        val placeholders = urls.map { url ->
            ClipItem(
                id = UUID.randomUUID().toString(),
                url = url,
            )
        }

        _state.update {
            it.copy(
                isFetching = true,
                clips = placeholders,
            )
        }

        placeholders.forEach { clip ->
            viewModelScope.launch {
                resolveClip(clip.id, clip.url)
            }
        }
    }

    fun retryFetch(clipId: String) {
        val clip = _state.value.clips.firstOrNull { it.id == clipId } ?: return
        updateClip(clipId) {
            it.copy(
                status = ClipStatus.LOADING_INFO,
                errorMessage = null,
                statusLine = null,
                downloadedPath = null,
            )
        }

        viewModelScope.launch {
            resolveClip(clip.id, clip.url)
        }
    }

    fun selectFormat(clipId: String, formatId: String) {
        updateClip(clipId) {
            it.copy(selectedFormatId = formatId)
        }
    }

    fun downloadClip(clipId: String) {
        val uiState = _state.value
        val clip = uiState.clips.firstOrNull { it.id == clipId } ?: return
        if (clip.status == ClipStatus.DOWNLOADING) return

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to clip.url,
                    DownloadWorker.KEY_TITLE to clip.title,
                    DownloadWorker.KEY_MODE to uiState.mode.name,
                    DownloadWorker.KEY_FORMAT_ID to clip.selectedFormatId,
                    DownloadWorker.KEY_REQUEST_ID to clip.id.takeLast(8),
                ),
            )
            .addTag("reclip-download")
            .build()

        updateClip(clipId) {
            it.copy(
                status = ClipStatus.DOWNLOADING,
                progress = 0,
                errorMessage = null,
                statusLine = getApplication<Application>().getString(R.string.status_downloading),
                workId = workRequest.id,
            )
        }

        observeWork(clipId, workRequest.id)
        workManager.enqueue(workRequest)
    }

    fun downloadAll() {
        _state.value.clips
            .filter { it.status == ClipStatus.READY || it.status == ClipStatus.ERROR }
            .forEach { downloadClip(it.id) }
    }

    fun cancelDownload(clipId: String) {
        val clip = _state.value.clips.firstOrNull { it.id == clipId } ?: return
        val workId = clip.workId ?: return
        workManager.cancelWorkById(workId)
    }

    private suspend fun resolveClip(clipId: String, url: String) {
        val result = repository.fetchInfo(url)
        result.fold(
            onSuccess = { details ->
                updateClip(clipId) {
                    it.copy(
                        title = details.title,
                        uploader = details.uploader,
                        extractor = details.extractor,
                        durationSeconds = details.durationSeconds,
                        formats = details.formats,
                        selectedFormatId = details.formats.firstOrNull()?.id,
                        status = ClipStatus.READY,
                        errorMessage = null,
                        statusLine = getApplication<Application>().getString(R.string.status_ready),
                    )
                }
            },
            onFailure = { error ->
                updateClip(clipId) {
                    it.copy(
                        title = "Could not fetch video",
                        status = ClipStatus.INFO_ERROR,
                        errorMessage = friendlyError(error.message),
                        statusLine = getApplication<Application>().getString(R.string.status_info_error),
                    )
                }
            },
        )

        _state.update { current ->
            val stillLoading = current.clips.any { it.status == ClipStatus.LOADING_INFO }
            current.copy(isFetching = stillLoading)
        }
    }

    private fun observeWork(clipId: String, workId: UUID) {
        workWatchers.remove(clipId)?.cancel()
        workWatchers[clipId] = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                if (info == null) return@collect

                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                    -> {
                        updateClip(clipId) {
                            it.copy(
                                status = ClipStatus.DOWNLOADING,
                                progress = info.progress.getInt(DownloadWorker.KEY_PROGRESS, it.progress),
                                statusLine = info.progress.getString(DownloadWorker.KEY_STATUS_LINE)
                                    ?: it.statusLine,
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        updateClip(clipId) {
                            it.copy(
                                status = ClipStatus.DONE,
                                progress = 100,
                                statusLine = getApplication<Application>()
                                    .getString(R.string.status_finished),
                                downloadedPath = info.outputData.getString(DownloadWorker.KEY_FILE_PATH),
                                workId = null,
                            )
                        }
                        workWatchers.remove(clipId)?.cancel()
                    }

                    WorkInfo.State.FAILED -> {
                        updateClip(clipId) {
                            it.copy(
                                status = ClipStatus.ERROR,
                                progress = 0,
                                statusLine = getApplication<Application>()
                                    .getString(R.string.status_error),
                                errorMessage = friendlyError(
                                    info.outputData.getString(DownloadWorker.KEY_ERROR),
                                ),
                                workId = null,
                            )
                        }
                        workWatchers.remove(clipId)?.cancel()
                    }

                    WorkInfo.State.CANCELLED -> {
                        updateClip(clipId) {
                            it.copy(
                                status = ClipStatus.READY,
                                progress = 0,
                                statusLine = getApplication<Application>()
                                    .getString(R.string.status_ready),
                                workId = null,
                            )
                        }
                        workWatchers.remove(clipId)?.cancel()
                    }
                }
            }
        }
    }

    private fun updateClip(clipId: String, transform: (ClipItem) -> ClipItem) {
        _state.update { current ->
            current.copy(
                clips = current.clips.map { clip ->
                    if (clip.id == clipId) transform(clip) else clip
                },
            )
        }
    }

    private fun parseUrls(rawInput: String): List<String> {
        return rawInput
            .split(Regex("[\\s,]+"))
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    private fun friendlyError(message: String?): String {
        val source = message.orEmpty()
        return when {
            source.contains("Unsupported URL", ignoreCase = true) -> "This URL is not supported."
            source.contains("Video unavailable", ignoreCase = true) -> "This video is unavailable or private."
            source.contains("Private video", ignoreCase = true) -> "This video is private."
            source.contains("HTTP Error 403", ignoreCase = true) -> "Access was denied by the source platform."
            source.contains("HTTP Error 404", ignoreCase = true) -> "The source video could not be found."
            source.contains("copyright", ignoreCase = true) -> "The video appears to be blocked by copyright restrictions."
            source.contains("timed out", ignoreCase = true) -> "The request timed out. Try again."
            source.contains("network", ignoreCase = true) -> "Network error. Check your connection and retry."
            source.isBlank() -> "Something went wrong."
            else -> source.lineSequence().last().trim().ifBlank { "Something went wrong." }
        }
    }
}
