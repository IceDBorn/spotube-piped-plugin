package dev.icedborn.spotube_plugin_piped_metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Piped returns null for fields declared non-nullable (title, uploaderUrl, views,
// duration, description); coerceInputValues maps those nulls to the declared defaults.
internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** The search filter values the Piped API accepts (its /search "filter" parameter). */
object PipedSearchFilter {
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
data class PipedSearchPage(
    val items: List<PipedSearchItem> = emptyList(),
    val nextpage: String? = null,
)

/** One Piped search / trending / related-stream row. Music results: "url", "type",
 * and either "title" (streams) or "name" (albums/artists/playlists). */
@Serializable
data class PipedSearchItem(
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
data class PipedStreamsInfo(
    val title: String = "",
    val description: String? = null,
    val duration: Int = -1,
    val uploader: String = "",
    val uploaderUrl: String = "",
    val uploaderAvatar: String? = null,
    val thumbnailUrl: String? = null,
    val relatedStreams: List<PipedSearchItem> = emptyList(),
)

/** A YouTube playlist page; YouTube Music albums arrive as OLAK5uy_* playlists. */
@Serializable
data class PipedPlaylistPage(
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
data class PipedChannelInfo(
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

    /** Cached ordered rows of a playlist/album, extended lazily through nextpage. [resumeIndex] is where
     * the stored [nextpage] token resumes inside [tracks] (recovery merges INSIDE the fresh prefix; -1 = legacy). */
@Serializable
data class CachedRows(
    val tracks: List<dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack> = emptyList(),
    val nextpage: String? = null,
    val complete: Boolean = false,
    val resumeIndex: Int = -1,
    // epochMillis() of the last LIVE full re-verify of a COMPLETE cache
    // (Store.page's TTL arm); 0 = never verified this session/upgrade.
    val lastVerifiedAt: Long = 0,
)