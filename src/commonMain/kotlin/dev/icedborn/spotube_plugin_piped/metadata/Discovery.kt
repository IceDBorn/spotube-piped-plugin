package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.core.mapConcurrently
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CompletableDeferred

/** A radio or an artist set is reused by the sections built within this long of its fetch. */
private const val RADIO_REUSE_MS = 2 * 60_000L
private const val DISCOVERY_ITEMS = 20
private const val DISCOVERY_ARTISTS = 4
private const val ITEMS_PER_ARTIST = 4
private const val SEARCH_ITEMS_PER_ARTIST = 10
internal const val SEARCH_ARTISTS = 2

/** Home discovery: the radio artists the user does not play yet, and their songs, albums and playlists. Every
 * loader failure reads as empty, so nothing here throws. */
internal class Discovery(
    private val radio: suspend (seedId: String) -> List<MetadataTrack>,
    private val match: suspend (candidates: List<MetadataArtist.Basic>, exclude: Set<String>) -> List<MetadataArtist.Basic>,
    private val albumsOf: suspend (artistId: String) -> List<MetadataAlbum.Basic>,
    private val playlistsOf: suspend (artistId: String) -> List<MetadataPlaylist>,
    private val searchPlaylists: suspend (query: String) -> List<MetadataPlaylist>,
    private val now: () -> Long,
) {
    private val radios = HashMap<String, Pair<Long, List<MetadataTrack>>>()
    private val artistSets = HashMap<String, Pair<Long, CompletableDeferred<List<MetadataArtist.Basic>>>>()

    // A failed radio reads as empty and is not kept, so the next section asks again.
    suspend fun radioFor(seed: MetadataTrack): List<MetadataTrack> {
        radios.entries.removeAll { now() - it.value.first >= RADIO_REUSE_MS }
        radios[seed.id]?.let { return it.second }
        val tracks = orNull { radio(seed.id) }.orEmpty()
        if (tracks.isNotEmpty()) radios[seed.id] = now() to tracks
        return tracks
    }

    /** The radio artists of [seeds] minus [known], as YouTube Music artists. Sections share one build, and an empty
     * one is kept too, so a failed radio is not fetched again by every discovery section. */
    suspend fun newArtists(seeds: List<MetadataTrack>, known: Collection<String>): List<MetadataArtist.Basic> {
        if (seeds.isEmpty()) return emptyList()
        val exclude = known.toHashSet()
        val key = seeds.joinToString(",") { it.id } + "|" + exclude.sorted().joinToString(",")
        artistSets.entries.removeAll { now() - it.value.first >= RADIO_REUSE_MS }
        artistSets[key]?.let { return it.second.await() }
        val pending = CompletableDeferred<List<MetadataArtist.Basic>>()
        artistSets[key] = now() to pending
        try {
            val found = orNull("new artists") { resolveNewArtists(seeds, exclude) }
            val artists = found.orEmpty()
            pending.complete(artists)
            return artists
        } finally {
            // A cancelled build leaves no entry, so the next call builds again.
            if (!pending.isCompleted) {
                artistSets.remove(key)
                pending.complete(emptyList())
            }
        }
    }

    /** Radio tracks of [seeds] by [artists], alternating seeds. The name check catches second channels. */
    suspend fun newSongs(seeds: List<MetadataTrack>, artists: List<MetadataArtist.Basic>): List<MetadataTrack> {
        // Nothing can match, so the radios are not asked for.
        if (artists.isEmpty()) return emptyList()
        val ids = artists.map { it.id }.toHashSet()
        val names = artists.map { it.name.lowercase() }.toHashSet()
        val perSeed = seeds.map { seed ->
            radioFor(seed).filter { track ->
                val artist = track.artists.firstOrNull() ?: return@filter false
                canonicalArtistId(artist.id) in ids || cleanArtistName(artist.name).lowercase() in names
            }
        }
        return roundRobin(perSeed).distinctBy { it.id }.take(DISCOVERY_ITEMS)
    }

    suspend fun albumsBy(artists: List<MetadataArtist.Basic>): List<MetadataAlbum.Basic> =
        perArtist(artists.take(DISCOVERY_ARTISTS), ITEMS_PER_ARTIST) { albumsOf(it.id) }
            .distinctBy { it.id }
            .take(DISCOVERY_ITEMS)

    suspend fun playlistsBy(artists: List<MetadataArtist.Basic>): List<MetadataPlaylist> =
        perArtist(artists.take(DISCOVERY_ARTISTS), ITEMS_PER_ARTIST) { playlistsOf(it.id) }
            .distinctBy { it.id }
            .take(DISCOVERY_ITEMS)

    suspend fun playlistsSearchedFor(artists: List<MetadataArtist.Basic>): List<MetadataPlaylist> =
        perArtist(artists.take(SEARCH_ARTISTS), SEARCH_ITEMS_PER_ARTIST) { searchPlaylists(it.name) }
            .distinctBy { it.id }
            .take(DISCOVERY_ITEMS)

    private suspend fun resolveNewArtists(seeds: List<MetadataTrack>, exclude: Set<String>): List<MetadataArtist.Basic> {
        val radioTracks = seeds.flatMap { radioFor(it) }
        val candidates = rankRadioArtists(radioTracks, exclude, RADIO_CANDIDATES)
        return match(candidates, exclude)
    }

    private suspend fun <T : Any> perArtist(
        artists: List<MetadataArtist.Basic>,
        each: Int,
        load: suspend (MetadataArtist.Basic) -> List<T>,
    ): List<T> = roundRobin(artists.mapConcurrently { artist -> orNull { load(artist) }.orEmpty().take(each) })
}

/** The first item of each list, then the second of each, and so on. */
internal fun <T : Any> roundRobin(lists: List<List<T>>): List<T> {
    val out = ArrayList<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
    return out
}
