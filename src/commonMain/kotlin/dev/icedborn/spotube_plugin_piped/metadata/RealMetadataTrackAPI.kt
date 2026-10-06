package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.savedPage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrackAPI

private val trackLog = Logger("PipedTracks")

internal class RealMetadataTrackAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
) : MetadataTrackAPI {

    private val songs = SongMatch(client)
    private val mixArtists = MixArtists(store)

    override suspend fun getTrack(id: String): MetadataTrack {
        store.cachedTrack(id)?.let { return it }
        val info = store.cachedStreams(id) ?: client.streamsForMetadata(id)
            ?.also { store.cacheStreams(id, it) }
            ?: throw IllegalStateException("streams fetch failed for $id")
        val track = info.toTrack(id)
        store.rememberTrack(track)
        return track
    }

    override suspend fun savedTracks(pagination: PaginationStrategy?): PaginationResult<MetadataTrack> {
        mirror.maybeRefresh()
        var failed = 0
        val page = savedPage(mirror.allSavedTrackIds(), pagination) { id ->
            orNull { store.cachedTrack(id) ?: getTrack(id) } ?: null.also { failed++ }
        }
        // One line for the page, never one per id: a throttled instance fails every item at once.
        if (failed > 0) trackLog.w { "saved tracks page: $failed ids failed" }
        return page
    }

    override suspend fun isSavedTracks(ids: List<String>): List<Boolean> = mirror.isSavedTracks(ids)

    override suspend fun saveTracks(ids: List<String>) {
        // Every id goes to the mirror, so one saved locally whose mirror write failed is retried.
        library.saveTracks(ids)
        mirror.save(SavedKind.TRACK, ids)
    }

    override suspend fun removeSavedTracks(ids: List<String>) {
        mirror.remove(SavedKind.TRACK, ids)

        mirror.removeLibraryEntries(SavedKind.TRACK, ids)
    }
    override suspend fun recommendationsBasedOnTracks(seedTrackIds: List<String>, limit: Int): List<MetadataTrack> {
        if (seedTrackIds.isEmpty() || limit <= 0) return emptyList()
        return orNull("radio queue from ${seedTrackIds.first()}") {
            val seeds = seedTrackIds.take(2)
            val mixes = seeds.map { mix(it) }
            val queue = ArtistSpread(limit, RADIO_PER_ARTIST, seeds + mixes.mapNotNull { it.seed?.id })
            mixes.forEach { queue.offer(it.tracks) }
            // A mix keeps close to its seed's artist, so the mixes of other artists' songs in it widen the queue.
            val seedArtists = mixes.mapNotNullTo(HashSet()) { it.seed?.artistKey() }
            val hops = mixes.flatMap { it.tracks }.filter { it.artistKey() !in seedArtists }.distinctBy { it.artistKey() }
            for (hop in hops.take(RADIO_HOPS)) {
                if (queue.full) break
                queue.offer(mix(hop.id).tracks)
            }
            if (!queue.filled) queue.offer(ytmCatalogTracks(seeds.first()))
            queue.result().onEach { store.rememberTrack(it) }
        } ?: emptyList()
    }

    /** Radio rows of [seedTrackIds] in mix order, without the artist spread, since Home and related artists rank
     * artists by how often a mix lists them. */
    suspend fun radio(seedTrackIds: List<String>, limit: Int): List<MetadataTrack> {
        if (seedTrackIds.isEmpty() || limit <= 0) return emptyList()
        return orNull("radio from ${seedTrackIds.first()}") {
            val out = LinkedHashMap<String, MetadataTrack>()
            for (seed in seedTrackIds.take(2)) {
                if (out.size >= limit) break
                for (track in mix(seed).tracks) {
                    if (track.id == seed || out.containsKey(track.id)) continue
                    out[track.id] = track
                    store.rememberTrack(track)
                    if (out.size >= limit) break
                }
            }
            // Thin radio: top up with the seed artist's music_songs catalog.
            if (out.size < limit) {
                for (track in ytmCatalogTracks(seedTrackIds.first())) {
                    if (out.containsKey(track.id)) continue
                    out[track.id] = track
                    store.rememberTrack(track)
                    if (out.size >= limit) break
                }
            }
            out.values.toList()
        } ?: emptyList()
    }

    private class Mix(val seed: MetadataTrack?, val tracks: List<MetadataTrack>)

    // The queue stays on YouTube Music, so it reads the radio mix playlist, not the plain related list.
    private suspend fun mix(seed: String): Mix {
        val mixSeed = songSeed(seed)
        val tracks = mixArtists.tracks(ytmRadioItems(mixSeed))
        return Mix(tracks.firstOrNull { it.id == mixSeed }, tracks.filter { it.id != mixSeed && it.id != seed })
    }

    /** A mix seeded from a video lists videos, so a seed that looks like one is swapped for its song. Only a
     * cached seed is checked, so a song seed costs no extra request. */
    private suspend fun songSeed(seed: String): String {
        val track = store.cachedTrack(seed) ?: return seed
        if (!looksLikeVideo(track.title, track.artists.firstOrNull()?.name.orEmpty())) return seed
        return songs.songFor(seed, track.title) ?: seed
    }

    /** YT Music radio mix rows, minus lives, long mixes and uploads that are videos rather than songs. */
    private suspend fun ytmRadioItems(seedVideoId: String): List<PipedSearchItem> {
        val mix = orNull("radio mix $seedVideoId") { client.playlist("RDAMVM$seedVideoId") }
            ?: return emptyList()
        return mix.relatedStreams.filter { item ->
            item.type == "stream" && item.duration in 20..900 && !looksLikeVideo(item.title, item.uploaderName)
        }
    }

    /** The seed artist's catalog from a music_songs search. */
    private suspend fun ytmCatalogTracks(seedVideoId: String): List<MetadataTrack> {
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
}
