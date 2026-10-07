package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.channelIdOf
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.client.videoIdOf
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

private val VIDEO_MARKERS = listOf(
    "official video", "music video", "official hd video", "lyric video", "lyrics video", "visualizer", "visualiser",
    "official audio", "(audio)", "[audio]", "(lyrics)", "[lyrics]",
)

private const val SONG_CACHE_SIZE = 200

internal fun nameKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

// A key shorter than 3 characters would be contained in most names, so it has to match whole.
internal fun namesMatch(a: String, b: String): Boolean {
    val x = nameKey(a)
    val y = nameKey(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return x == y || (minOf(x.length, y.length) >= 3 && (x in y || y in x))
}

/** True for a music video, lyric video or audio upload, as opposed to a YouTube Music song. Songs carry the bare
 * title, while uploads add a marker or an "Artist - " prefix that names their [uploader]. */
internal fun looksLikeVideo(title: String, uploader: String): Boolean {
    val lower = title.lowercase()
    if (VIDEO_MARKERS.any { it in lower }) return true
    val prefix = lower.split(" - ", " – ", limit = 2).takeIf { it.size == 2 }?.first() ?: return false
    return namesMatch(prefix, cleanArtistName(uploader))
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

private const val CHANNEL_CACHE_SIZE = 200

/** A mix row names the YouTube channel of its video, which can be a fan or band channel rather than the YouTube
 * Music artist. Rows whose song is cached under another artist take that artist, and teach it to their channel. */
internal class MixArtists(private val store: EntityStore) {

    private val byChannel = LinkedHashMap<String, MetadataArtist.Basic>()

    suspend fun tracks(items: List<PipedSearchItem>): List<MetadataTrack> {
        val cached = HashMap<String, MetadataTrack>()
        for (item in items) {
            val id = videoIdOf(item.url)
            val track = store.cachedTrack(id) ?: continue
            val artist = track.artists.firstOrNull() ?: continue
            if (artist.id == item.channel()) continue
            cached[id] = track
            learn(item, artist)
        }
        val tracks = items.mapNotNull { item ->
            cached[videoIdOf(item.url)] ?: byChannel[item.channel()]?.let { artist ->
                item.copy(uploaderName = artist.name, uploaderUrl = "/channel/${artist.id}", uploaderAvatar = null).toTrack()
            } ?: item.toTrack()
        }
        // A label or fan upload titled "Artist - Song" repeats a song the mix also lists under that artist.
        val names = tracks.flatMap { it.artists }.map { it.name }.toSet()
        return tracks.filter { track -> names.none { looksLikeVideo(track.title, it) } }
    }

    // Only a channel named after the artist is mapped, since a label channel uploads many artists.
    private fun learn(item: PipedSearchItem, artist: MetadataArtist.Basic) {
        val channel = item.channel()
        if (channel.isEmpty() || channelIdOf("/channel/${artist.id}").isEmpty()) return
        if (!namesMatch(cleanArtistName(item.uploaderName), artist.name)) return
        byChannel.remove(channel)
        byChannel[channel] = artist
        if (byChannel.size > CHANNEL_CACHE_SIZE) byChannel.remove(byChannel.keys.first())
    }

    private fun PipedSearchItem.channel() = channelIdOf(uploaderUrl)
}
