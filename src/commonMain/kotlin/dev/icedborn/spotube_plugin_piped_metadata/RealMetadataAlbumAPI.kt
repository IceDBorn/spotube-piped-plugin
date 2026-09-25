package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumType
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

internal class RealMetadataAlbumAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    private val albumLookup: AlbumLookup,
) : MetadataAlbumAPI {

    override suspend fun getAlbum(id: String): MetadataAlbum.Detailed {
        val realId = if (id.startsWith(ALBUM_LOOKUP_PREFIX)) resolveAlbumId(id) else id
        val cached = store.cachedAlbumPlaylist(realId)
        val page = if (cached != null && (cached.relatedStreams.isNotEmpty() || !cached.nextpage.isNullOrBlank())) cached else {
            // playlist() nulls BLANK bodies (throttles); a decodable page — even EMPTY — is authoritative: cache it.
            // Re-fetch failures degrade to the cached page (real header) — the unwrapped caller must not hard-fail.
            val fetched = client.playlist(realId)
            if (fetched != null) {
                store.cacheAlbumPlaylist(realId, fetched)
                fetched
            } else if (cached != null) {
                cached
            } else {
                return MetadataAlbum.Detailed(
                    releaseDate = null,
                    genres = emptyList(),
                    trackCount = 0,
                    id = realId,
                    title = "",
                    description = null,
                    thumbnails = emptyList(),
                    albumType = MetadataAlbumType.Album,
                    artists = emptyList(),
                    externalUri = null,
                )
            }
        }
        return page.toAlbum(realId)
    }

    /** Maps a "ytm-album:<videoId>" lookup id to the real album playlist id via a music_albums search. */
    private suspend fun resolveAlbumId(lookupId: String): String {
        val videoId = lookupId.removePrefix(ALBUM_LOOKUP_PREFIX)
        store.cachedTrackAlbum(videoId)?.let { cached ->
            if (cached == "none") throw IllegalStateException("No album found for track $videoId")
            return cached
        }
        val albumId = resolveAlbumIdForVideo(videoId)
            ?: throw IllegalStateException("No album found for track $videoId")
        store.cacheTrackAlbum(videoId, albumId)
        return albumId
    }

    private suspend fun resolveAlbumIdForVideo(videoId: String): String? =
        albumLookup.resolveAlbumForVideo(videoId).albumId

    private suspend fun resolveAlbumIdFor(track: MetadataTrack): String? {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        return runCatching { albumLookup.resolveForNames(track.title, artist, track.id) }.getOrNull()
    }

    override suspend fun getTrackAlbum(track: MetadataTrack): MetadataAlbum.Detailed {
        track.album?.let { album ->
            if (album.id.startsWith(ALBUM_LOOKUP_PREFIX)) {
                return getAlbum(album.id)
            }
            return album
        }
        // One resolved album id per track, cached so repeated lookups are free.
        store.cachedTrackAlbum(track.id)?.let { albumId ->
            if (albumId == "none") throw IllegalStateException("No album found for track ${track.id}")
            return getAlbum(albumId)
        }
        val albumId = resolveAlbumIdFor(track)
            ?: throw IllegalStateException("No album found for track ${track.id}")
        store.cacheTrackAlbum(track.id, albumId)
        return getAlbum(albumId)
    }



    override suspend fun getAlbumTracks(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataTrack> {
        val paging = pagination.getOffsetOrDefault()
        val realId = if (id.startsWith(ALBUM_LOOKUP_PREFIX)) resolveAlbumId(id) else id
        val album = getAlbum(realId)
        return PlaylistRows(client, store).page(
            id = realId,
            isAlbum = true,
            album = album,
            paging = paging,
            knownTotal = album.trackCount,
        )
    }

    override suspend fun savedAlbums(pagination: PaginationStrategy?): PaginationResult<MetadataAlbum.Detailed> {
        mirror.maybeRefresh()
        val paging = pagination.getOffsetOrDefault()
        val ids = mirror.allSavedAlbumIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapConcurrently { id -> runCatching { getAlbum(id) }.getOrNull() }.filterNotNull()
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            // Local saved-set ids are AUTHORITATIVE (the list terminates): page by the fixed limit, so a
            // transiently-failed page never hides tail ids nor re-requests failing ids (account lists vary).
            nextPagination = if (paging.offset + paging.limit < ids.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedAlbums(ids: List<String>): List<Boolean> = mirror.isSavedAlbums(ids)

    override suspend fun saveAlbums(ids: List<String>) {
            // Pass the FULL id set, not just library-fresh ids: an id whose save committed locally but never reached
            // the mirror must keep retrying (already-saved ids are cheap no-ops: rebind + dedupe).
            library.saveAlbums(ids)
            mirror.save(SavedKind.ALBUM, ids)
        }

    override suspend fun removeSavedAlbums(ids: List<String>) {

        mirror.remove(SavedKind.ALBUM, ids)

        mirror.removeLibraryEntries(SavedKind.ALBUM, ids)

    }
}