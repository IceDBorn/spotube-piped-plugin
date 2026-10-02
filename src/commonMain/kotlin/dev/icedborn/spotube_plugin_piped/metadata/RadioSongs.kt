package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.videoIdOf
import dev.icedborn.spotube_plugin_piped.store.orNull

private val VIDEO_MARKERS = listOf(
    "official video", "music video", "official hd video", "lyric video", "lyrics video", "visualizer", "visualiser",
    "official audio", "(audio)", "[audio]", "(lyrics)", "[lyrics]",
)

private const val SONG_CACHE_SIZE = 200

/** True for a music video, lyric video or audio upload, as opposed to a YouTube Music song. Songs carry the bare
 * title, while uploads add a marker or an "Artist - " prefix that names their [uploader]. */
internal fun looksLikeVideo(title: String, uploader: String): Boolean {
    val lower = title.lowercase()
    if (VIDEO_MARKERS.any { it in lower }) return true
    val prefix = lower.split(" - ", " – ", limit = 2).takeIf { it.size == 2 }?.first()?.trim() ?: return false
    val channel = cleanArtistName(uploader).lowercase().trim()
    return prefix.isNotEmpty() && channel.isNotEmpty() && (prefix in channel || channel in prefix)
}

/** Maps a video to the YouTube Music song it carries, so a radio seeded from it plays songs and not videos. */
internal class SongMatch(private val client: PipedClient) {

    private val byVideo = LinkedHashMap<String, String?>()

    /** The song id for video [id], or null when no song's title and artist both appear in [title]. */
    suspend fun songFor(id: String, title: String): String? {
        if (byVideo.containsKey(id)) return byVideo[id]
        val page = orNull("song match") { client.search(title, PipedSearchFilter.MUSIC_SONGS) } ?: return null
        val lower = title.lowercase()
        val song = page.items.firstOrNull { item ->
            val artist = cleanArtistName(item.uploaderName).lowercase()
            val name = item.title.ifBlank { item.name }.lowercase()
            artist.isNotBlank() && name.isNotBlank() && artist in lower && name in lower
        }?.let { videoIdOf(it.url) }?.takeIf { it.isNotEmpty() }
        // A failed search returned above, so only a real miss is cached.
        byVideo[id] = song
        if (byVideo.size > SONG_CACHE_SIZE) byVideo.remove(byVideo.keys.first())
        return song
    }
}
