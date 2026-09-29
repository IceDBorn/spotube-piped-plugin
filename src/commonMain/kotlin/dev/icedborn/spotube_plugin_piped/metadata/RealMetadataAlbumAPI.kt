package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.ALBUM_LOOKUP_PREFIX
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.toAlbum
import dev.icedborn.spotube_plugin_piped.core.emptyPagination
import dev.icedborn.spotube_plugin_piped.core.getOffsetOrDefault
import dev.icedborn.spotube_plugin_piped.core.savedPage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.NO_ALBUM
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.PlaylistRows
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumType
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** How long a screen waits for an album lookup before it gets a stub; the lookup keeps running and fills the cache. */
private const val ALBUM_WAIT_MS = 4_000L

internal class RealMetadataAlbumAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    private val albumLookup: AlbumLookup,
    // Lookups that outlast the wait continue here; without a scope they run inline.
    private val scope: CoroutineScope? = null,
) : MetadataAlbumAPI {

    private val lookups = HashMap<String, Deferred<String?>>()

    override suspend fun getAlbum(id: String): MetadataAlbum.Detailed {
        val realId = if (id.startsWith(ALBUM_LOOKUP_PREFIX)) resolveAlbumId(id) ?: return stubAlbum(id) else id
        val cached = store.cachedAlbumPlaylist(realId)
        val page = if (cached != null && (cached.relatedStreams.isNotEmpty() || !cached.nextpage.isNullOrBlank())) {
            cached
        } else {
            // A decodable page, even an empty one, is cached. A failed fetch falls back to the cached page.
            val fetched = orNull { client.playlist(realId) }
            if (fetched != null) {
                store.cacheAlbumPlaylist(realId, fetched)
                fetched
            } else if (cached != null) {
                cached
            } else {
                return stubAlbum(realId)
            }
        }
        return page.toAlbum(realId)
    }

    private fun stubAlbum(id: String) = MetadataAlbum.Detailed(
        releaseDate = null,
        genres = emptyList(),
        trackCount = 0,
        id = id,
        title = "",
        description = null,
        thumbnails = emptyList(),
        albumType = MetadataAlbumType.Album,
        artists = emptyList(),
        externalUri = null,
    )

    /** Maps a "ytm-album:<videoId>" lookup id to the real album playlist id. Null while the lookup outlasts
     * [ALBUM_WAIT_MS]; throws when the video proved to have no album. */
    private suspend fun resolveAlbumId(lookupId: String): String? {
        val videoId = lookupId.removePrefix(ALBUM_LOOKUP_PREFIX)
        return awaitLookup(videoId) { albumLookup.resolveAlbumForVideo(videoId, ALBUM_LOOKUP_BUDGET).albumId }
    }

    /** The album of [videoId] from the cache, else from [lookup] shared by concurrent callers. Null when the lookup
     * outlasts [ALBUM_WAIT_MS]; it keeps running and caches its verdict. Throws when the video has no album. */
    private suspend fun awaitLookup(videoId: String, lookup: suspend () -> String?): String? {
        store.cachedTrackAlbum(videoId)?.let { cached ->
            if (cached == NO_ALBUM) throw IllegalStateException("No album found for track $videoId")
            return cached
        }
        val run = suspend {
            val albumId = lookup()
            store.cacheTrackAlbum(videoId, albumId ?: NO_ALBUM)
            albumId
        }
        val albumId = if (scope == null) {
            run()
        } else {
            val deferred = lookups.getOrPut(videoId) {
                scope.async {
                    try {
                        run()
                    } finally {
                        lookups.remove(videoId)
                    }
                }
            }
            // A lookup that finished in the same tick as the timeout still counts.
            withTimeoutOrNull(ALBUM_WAIT_MS) { deferred.await() } ?: if (deferred.isCompleted) deferred.await() else return null
        }
        return albumId ?: throw IllegalStateException("No album found for track $videoId")
    }

    override suspend fun getTrackAlbum(track: MetadataTrack): MetadataAlbum.Detailed {
        track.album?.let { album ->
            if (album.id.startsWith(ALBUM_LOOKUP_PREFIX)) {
                return getAlbum(album.id)
            }
            return album
        }
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val albumId = awaitLookup(track.id) {
            albumLookup.resolveForNames(track.title, artist, track.id, AlbumLookup.RequestCounter(ALBUM_LOOKUP_BUDGET))
        } ?: return stubAlbum(ALBUM_LOOKUP_PREFIX + track.id)
        return getAlbum(albumId)
    }

    override suspend fun getAlbumTracks(id: String, pagination: PaginationStrategy?): PaginationResult<MetadataTrack> {
        val paging = pagination.getOffsetOrDefault()
        val realId = if (id.startsWith(ALBUM_LOOKUP_PREFIX)) resolveAlbumId(id) ?: return emptyPagination() else id
        val album = getAlbum(realId)
        return PlaylistRows(client, store, scope).page(
            id = realId,
            isAlbum = true,
            album = album,
            paging = paging,
            knownTotal = album.trackCount,
        )
    }

    override suspend fun savedAlbums(pagination: PaginationStrategy?): PaginationResult<MetadataAlbum.Detailed> {
        mirror.maybeRefresh()
        return savedPage(mirror.allSavedAlbumIds(), pagination) { id -> orNull { getAlbum(id) } }
    }

    override suspend fun isSavedAlbums(ids: List<String>): List<Boolean> = mirror.isSavedAlbums(ids)

    override suspend fun saveAlbums(ids: List<String>) {
        // Every id goes to the mirror, so one saved locally whose mirror write failed is retried.
        library.saveAlbums(ids)
        mirror.save(SavedKind.ALBUM, ids)
    }

    override suspend fun removeSavedAlbums(ids: List<String>) {
        mirror.remove(SavedKind.ALBUM, ids)

        mirror.removeLibraryEntries(SavedKind.ALBUM, ids)
    }
}
