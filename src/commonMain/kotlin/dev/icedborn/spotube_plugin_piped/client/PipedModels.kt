package dev.icedborn.spotube_plugin_piped.client

import dev.icedborn.spotube_plugin_piped.store.RowCache
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Piped returns null for fields declared non-nullable (title, uploaderUrl, views,
// duration, description); coerceInputValues maps those nulls to the declared defaults.
internal val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** The search filter values the Piped API accepts (its /search "filter" parameter). */
internal object PipedSearchFilter {
    const val ALL = "all"
    const val VIDEOS = "videos" // regular YouTube videos, not YouTube Music
    const val CHANNELS = "channels"
    const val PLAYLISTS = "playlists"
    const val MUSIC_SONGS = "music_songs"
    const val MUSIC_VIDEOS = "music_videos"
    const val MUSIC_ALBUMS = "music_albums"
    const val MUSIC_PLAYLISTS = "music_playlists"
    const val MUSIC_ARTISTS = "music_artists"
}

@Serializable
internal data class PipedSearchPage(val items: List<PipedSearchItem> = emptyList(), val nextpage: String? = null)

/** One Piped search / trending / related-stream row. Music results: "url", "type",
 * and either "title" (streams) or "name" (albums/artists/playlists). */
@Serializable
internal data class PipedSearchItem(
    val type: String = "",
    val url: String = "",
    val title: String = "",
    val name: String = "",
    val thumbnail: String = "",
    val uploaderName: String = "",
    val uploaderUrl: String = "",
    val uploaderAvatar: String? = null,
    val duration: Int = -1,
    val views: Long = -1,
    val subscribers: Long = -1,
    val verified: Boolean = false,
    val description: String? = null,
)

@Serializable
internal data class PipedStreamsInfo(
    val title: String = "",
    val description: String? = null,
    val duration: Int = -1,
    val uploader: String = "",
    val uploaderUrl: String = "",
    val uploaderAvatar: String? = null,
    val thumbnailUrl: String? = null,
    val relatedStreams: List<PipedSearchItem> = emptyList(),
    // Only audio reads it. A metadata fetch requires relatedStreams, an audio fetch requires this.
    val audioStreams: List<PipedAudioStream> = emptyList(),
    // Audio reads only the muxed itag 18 row from here.
    val videoStreams: List<PipedVideoStream> = emptyList(),
)

@Serializable
internal data class PipedAudioStream(
    val url: String = "",
    val format: String = "",
    val quality: String = "",
    val mimeType: String = "",
    val itag: Int = -1,
    val bitrate: Int = -1,
)

@Serializable
internal data class PipedVideoStream(val url: String = "", val itag: Int = -1, val bitrate: Int = -1)

/** A YouTube playlist page; YouTube Music albums arrive as OLAK5uy_* playlists. */
@Serializable
internal data class PipedPlaylistPage(
    val name: String = "",
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val videos: Int = -1,
    val nextpage: String? = null,
    val relatedStreams: List<PipedSearchItem> = emptyList(),
)

@Serializable
internal data class PipedChannelInfo(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String? = null,
    val description: String? = null,
    val subscriberCount: Long = -1,
    val verified: Boolean = false,
    val nextpage: String? = null,
    val relatedStreams: List<PipedSearchItem> = emptyList(),
)

/** Cached ordered rows of a playlist or album, extended through [nextpage] by [RowCache].
 *
 * Rules every writer keeps:
 * - A fetch that returns a blank body or a body without `relatedStreams` failed; it is never an empty list.
 * - A blank [nextpage] is not an end. The cache stays open and the next read restarts from page 1.
 * - An empty page after rows is ambiguous, never an end.
 * - [complete] is set only when the final page (null token) converted at least one row.
 * - Album track numbers are raw page positions, so rows that did not convert leave gaps.
 *
 * [rawCount] counts every row the pages held, converted or not. [lastVerifiedAt] is the last page-1 anchor.
 * [listedTotal] is the row total page 1 or the account listing reported when the cache was written, or -1. */
@Serializable
internal data class CachedRows(
    val tracks: List<MetadataTrack> = emptyList(),
    val nextpage: String? = null,
    val complete: Boolean = false,
    val rawCount: Int = 0,
    val lastVerifiedAt: Long = 0,
    val listedTotal: Int = -1,
)
