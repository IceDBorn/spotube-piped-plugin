package dev.icedborn.spotube_plugin_piped_metadata

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
        val paging = pagination.getOffsetOrDefault()
        val ids = mirror.allSavedTrackIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapConcurrently { id ->
            orNull { store.cachedTrack(id) ?: getTrack(id) }
        }.filterNotNull()
        // One line for the page, never one per id: a throttled instance fails every item at once.
        if (items.size < slice.size) {
            trackLog.w { "saved tracks page: ${slice.size - items.size} of ${slice.size} ids failed (offset ${paging.offset})" }
        }
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            // Local saved-set ids are AUTHORITATIVE (the list terminates): page by the fixed limit so failures
            // never hide tail ids nor re-request failing ids. Account lists keep the slice guard (stale totals).
            nextPagination = if (paging.offset + paging.limit < ids.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedTracks(ids: List<String>): List<Boolean> = mirror.isSavedTracks(ids)

    override suspend fun saveTracks(ids: List<String>) {
            // Pass the FULL id set: membership filtering would permanently suppress an id whose save committed
            // locally but never reached the mirror (repKey unwritten). save() rebinds/dedupes — cheap no-ops.
            library.saveTracks(ids)
            mirror.save(SavedKind.TRACK, ids)
        }

    override suspend fun removeSavedTracks(ids: List<String>) {

        mirror.remove(SavedKind.TRACK, ids)

        mirror.removeLibraryEntries(SavedKind.TRACK, ids)

    }
    override suspend fun recommendationsBasedOnTracks(
        seedTrackIds: List<String>,
        limit: Int,
    ): List<MetadataTrack> {
        if (seedTrackIds.isEmpty() || limit <= 0) return emptyList()
        return orNull("radio queue from ${seedTrackIds.first()}") {
            val out = LinkedHashMap<String, MetadataTrack>()
            // The endless queue must stay YouTube-Music-only; /streams related lists are plain YouTube (lives,
            // TV clips, mixes). Use the YT Music radio mix instead — resolved as a "RDAMVM<videoId>" playlist.
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

    /** The seed artist's catalog from a music_songs search; YTM-only. */
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