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
            val out = LinkedHashMap<String, MetadataTrack>()
            // The queue stays on YouTube Music, so it reads the radio mix playlist, not the plain related list.
            for (seed in seedTrackIds.take(2)) {
                if (out.size >= limit) break
                for (item in ytmRadioItems(seed)) {
                    val track = item.toTrack() ?: continue
                    if (track.id == seed || out.containsKey(track.id)) continue
                    out[track.id] = track
                    store.rememberTrack(track)
                    if (out.size >= limit) break
                }
            }
            // Thin radio: top up with the seed artist's music_songs catalog.
            if (out.size < limit && seedTrackIds.isNotEmpty()) {
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

    /** YT Music radio mix rows, minus lives and long mixes. */
    private suspend fun ytmRadioItems(seedVideoId: String): List<PipedSearchItem> {
        val mix = orNull("radio mix $seedVideoId") { client.playlist("RDAMVM$seedVideoId") }
            ?: return emptyList()
        return mix.relatedStreams.filter { item ->
            item.type == "stream" && item.duration in 20..900
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
