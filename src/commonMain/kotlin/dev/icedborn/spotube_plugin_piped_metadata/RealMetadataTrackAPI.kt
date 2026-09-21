package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrackAPI

internal class RealMetadataTrackAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
) : MetadataTrackAPI {

    override suspend fun getTrack(id: String): MetadataTrack {
        store.cachedTrack(id)?.let { return it }
        val info = store.cachedStreams(id) ?: run {
            val fetched = client.streams(id)
            store.cacheStreams(id, fetched)
            fetched
        }
        val track = info.toTrack(id)
        store.rememberTrack(track)
        return track
    }

    override suspend fun savedTracks(pagination: PaginationStrategy?): PaginationResult<MetadataTrack> {
        mirror.maybeRefresh()
        val paging = pagination.getOffsetOrDefault()
        val ids = mirror.allSavedTrackIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapNotNull { id ->
            runCatching { store.cachedTrack(id) ?: getTrack(id) }.getOrNull()
        }
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            nextPagination = if (paging.offset + paging.limit < ids.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedTracks(ids: List<String>): List<Boolean> = mirror.isSavedTracks(ids)

    override suspend fun saveTracks(ids: List<String>) {
            val already = library.isSavedTracks(ids)
            val fresh = ids.filterIndexed { i, _ -> !already[i] }
            library.saveTracks(ids)
            mirror.save(SavedKind.TRACK, fresh)
        }

    override suspend fun removeSavedTracks(ids: List<String>) {

        mirror.remove(SavedKind.TRACK, ids)

        library.removeTracks(ids)

    }
    override suspend fun recommendationsBasedOnTracks(
        seedTrackIds: List<String>,
        limit: Int,
    ): List<MetadataTrack> {
        if (seedTrackIds.isEmpty() || limit <= 0) return emptyList()
        return runCatching {
            val out = LinkedHashMap<String, MetadataTrack>()
            // The endless queue must stay YouTube-Music-only. /streams related
            // streams are plain YouTube (lives, TV clips, hour-long mixes), so
            // use the YT Music radio mix instead, which Piped resolves as a
            // "RDAMVM<videoId>" playlist.
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
        }.getOrDefault(emptyList())
    }

    /** YT Music radio mix rows, minus lives and long mixes. */
    private suspend fun ytmRadioItems(seedVideoId: String): List<PipedSearchItem> {
        val mix = runCatching {
            client.playlist("RDAMVM$seedVideoId")
        }.getOrNull() ?: return emptyList()
        return mix.relatedStreams.filter { item ->
            item.type == "stream" && item.duration in 20..900
        }
    }

    /** The seed artist's catalog from a music_songs search; YTM-only. */
    private suspend fun ytmCatalogTracks(seedVideoId: String): List<MetadataTrack> {
        val info = store.cachedStreams(seedVideoId) ?: run {
            val fetched = client.streams(seedVideoId)
            store.cacheStreams(seedVideoId, fetched)
            fetched
        }
        val artist = cleanArtistName(info.uploader)
        if (artist.isBlank()) return emptyList()
        val page = client.search(artist, PipedSearchFilter.MUSIC_SONGS)
        return page.items
            .filter { it.type == "stream" || it.type == "video" }
            .mapNotNull { it.toTrack() }
            .distinctBy { it.id }
    }
}
