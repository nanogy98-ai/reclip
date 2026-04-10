package io.nanogy.reclip

import java.util.UUID

enum class DownloadMode {
    VIDEO,
    AUDIO,
}

enum class ClipStatus {
    LOADING_INFO,
    READY,
    INFO_ERROR,
    DOWNLOADING,
    DONE,
    ERROR,
}

data class ClipFormat(
    val id: String,
    val label: String,
    val height: Int,
)

data class ClipItem(
    val id: String,
    val url: String,
    val title: String = "Fetching details…",
    val uploader: String = "",
    val extractor: String = "",
    val durationSeconds: Int? = null,
    val formats: List<ClipFormat> = emptyList(),
    val selectedFormatId: String? = null,
    val status: ClipStatus = ClipStatus.LOADING_INFO,
    val errorMessage: String? = null,
    val progress: Int = 0,
    val statusLine: String? = null,
    val downloadedPath: String? = null,
    val workId: UUID? = null,
)

data class ClipDetails(
    val title: String,
    val uploader: String,
    val extractor: String,
    val durationSeconds: Int?,
    val formats: List<ClipFormat>,
)

data class MainUiState(
    val mode: DownloadMode = DownloadMode.VIDEO,
    val isFetching: Boolean = false,
    val clips: List<ClipItem> = emptyList(),
)
