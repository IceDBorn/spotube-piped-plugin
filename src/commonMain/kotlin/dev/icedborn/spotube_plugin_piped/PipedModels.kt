package dev.icedborn.spotube_plugin_piped

import kotlinx.serialization.Serializable

@Serializable
data class PipedSearchPage(
    val items: List<PipedSearchItem> = emptyList(),
)

@Serializable
data class PipedSearchItem(
    val type: String = "",
    val title: String = "",
    val url: String = "",
    val duration: Int = -1,
    val uploaderName: String = "",
    val thumbnail: String = "",
)

@Serializable
data class PipedStreamInfo(
    val title: String = "",
    val audioStreams: List<PipedAudioStream> = emptyList(),
)

@Serializable
data class PipedAudioStream(
    val url: String = "",
    val format: String = "",
    val quality: String = "",
    val mimeType: String = "",
    val itag: Int = -1,
    val bitrate: Int = -1,
)
