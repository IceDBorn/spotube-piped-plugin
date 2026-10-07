package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.savedPage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
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
    history: PlayHistory,
) : MetadataTrackAPI {

    private val mixes = RadioMixes(client, store)
    private val endless = EndlessPlayback(mixes, RadioStations(store), store, history) { mirror.allSavedTrackIds() }

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
        return orNull("radio queue from ${seedTrackIds.first()}") { endless.batch(seedTrackIds, limit) } ?: emptyList()
    }

    /** Radio rows of [seedTrackIds] in mix order, without the artist spread, since Home and related artists rank
     * artists by how often a mix lists them. */
    suspend fun radio(seedTrackIds: List<String>, limit: Int): List<MetadataTrack> {
        if (seedTrackIds.isEmpty() || limit <= 0) return emptyList()
        return orNull("radio from ${seedTrackIds.first()}") {
            val out = LinkedHashMap<String, MetadataTrack>()
            for (seed in seedTrackIds.take(2)) {
                if (out.size >= limit) break
                for (track in mixes.mix(seed)?.tracks.orEmpty()) {
                    if (track.id == seed || out.containsKey(track.id)) continue
                    out[track.id] = track
                    store.rememberTrack(track)
                    if (out.size >= limit) break
                }
            }
            // Thin radio: top up with the seed artist's music_songs catalog.
            if (out.size < limit) {
                for (track in mixes.catalogTracks(seedTrackIds.first())) {
                    if (out.containsKey(track.id)) continue
                    out[track.id] = track
                    store.rememberTrack(track)
                    if (out.size >= limit) break
                }
            }
            out.values.toList()
        } ?: emptyList()
    }
}
