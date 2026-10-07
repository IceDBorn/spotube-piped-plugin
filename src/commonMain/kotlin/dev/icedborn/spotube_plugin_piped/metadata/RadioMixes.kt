package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** Reads YouTube Music radio mixes and the seed artist's catalog, for endless playback, Home and related artists. */
internal class RadioMixes(private val client: PipedClient, private val store: EntityStore) {

    private val songs = SongMatch(client)
    private val mixArtists = MixArtists(store)

    /** A mix read from [songSeed], the seed's song when the seed looks like a video. [seed] is the mix's own row
     * for it, null when the mix does not list it. */
    class Mix(val seed: MetadataTrack?, val songSeed: String, val tracks: List<MetadataTrack>)

    /** The radio mix playlist of [seed], not its plain related list, so the queue stays on YouTube Music. Null when
     * the fetch failed, so endless playback can read the seed again later. */
    suspend fun mix(seed: String): Mix? {
        val mixSeed = songSeed(seed)
        val items = ytmRadioItems(mixSeed) ?: return null
        val tracks = mixArtists.tracks(items)
        return Mix(tracks.firstOrNull { it.id == mixSeed }, mixSeed, tracks.filter { it.id != mixSeed && it.id != seed })
    }

    /** The seed artist's catalog from a music_songs search. Throws when the seed's /streams fetch fails. */
    suspend fun catalogTracks(seedVideoId: String): List<MetadataTrack> {
        val info = store.cachedStreams(seedVideoId) ?: client.streamsForMetadata(seedVideoId)
            ?.also { store.cacheStreams(seedVideoId, it) }
            ?: throw IllegalStateException("streams fetch failed for $seedVideoId")
        val artist = cleanArtistName(info.uploader)
        if (artist.isBlank()) return emptyList()
        val page = client.search(artist, PipedSearchFilter.MUSIC_SONGS)
        return page?.items.orEmpty()
            .filter { it.type == "stream" || it.type == "video" }
            .mapNotNull { it.toTrack() }
            .distinctBy { it.id }
    }

    /** A mix seeded from a video lists videos, so a seed that looks like one is swapped for its song. Only a
     * cached seed is checked, so a song seed costs no extra request. */
    private suspend fun songSeed(seed: String): String {
        val track = store.cachedTrack(seed) ?: return seed
        if (!looksLikeVideo(track.title, track.artists.firstOrNull()?.name.orEmpty())) return seed
        return songs.songFor(seed, track.title) ?: seed
    }

    /** YT Music radio mix rows, minus lives, long mixes and uploads that are videos rather than songs. Null when
     * the fetch failed. */
    private suspend fun ytmRadioItems(seedVideoId: String): List<PipedSearchItem>? {
        val mix = orNull("radio mix $seedVideoId") { client.playlist("RDAMVM$seedVideoId") } ?: return null
        return mix.relatedStreams.filter { item ->
            item.type == "stream" && item.duration in 20..900 && !looksLikeVideo(item.title, item.uploaderName)
        }
    }
}
