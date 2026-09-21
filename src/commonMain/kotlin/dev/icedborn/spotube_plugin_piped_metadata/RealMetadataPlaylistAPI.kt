package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Real playlists come from Piped; created playlists and the saved list live in
 * the plugin's storage because Piped has no public playlist API.
 */
private const val LIKED_SONGS_ID = "piped-liked-songs"

internal class RealMetadataPlaylistAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
) : MetadataPlaylistAPI {

    override suspend fun getPlaylist(id: String): MetadataPlaylist {
        if (id == LIKED_SONGS_ID) return likedSongsPlaylist()
        storedLocalPlaylist(id)?.let { return it.toEntity() }
        mirror.accountPlaylist(id)?.let { return it.toEntity() }
        val cached = store.cachedAlbumPlaylist(id)
        val page = cached ?: run {
            val fetched = client.playlist(id)
            store.cacheAlbumPlaylist(id, fetched)
            fetched
        }
        return page.toPlaylist(id)
    }

    override suspend fun getPlaylistTracks(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataTrack> {
        val paging = pagination.getOffsetOrDefault()
        if (id == LIKED_SONGS_ID) return likedSongsTracks(paging)
        storedLocalPlaylist(id)?.let { record ->
            return localTracksPage(record, paging)
        }
        mirror.accountPlaylist(id)?.let { account ->
            return accountTracksPage(account, paging)
        }
        val playlist = getPlaylist(id)
        return PlaylistRows(client, store).page(
            id = id,
            isAlbum = false,
            album = null,
            paging = paging,
            knownTotal = playlist.trackCount,
        )
    }

    override suspend fun savedPlaylists(pagination: PaginationStrategy?): PaginationResult<MetadataPlaylist> {
        mirror.maybeRefresh()
        val paging = pagination.getOffsetOrDefault()
        // Only user-visible playlists: created ones, account playlists from the
        // cache, and bookmarked ones. The internal sync mirrors ("Spotube -
        // Albums/Artists/Favorites") stay hidden.
        val created = library.storedPlaylists().map { it.toEntity() }
        val account = mirror.cachedState()?.playlists.orEmpty().map { it.toEntity() }
        val saved = library.savedPlaylists().mapNotNull { id -> runCatching { getPlaylist(id) }.getOrNull() }
        val all = (created + account + saved).distinctBy { it.id }
        if (all.isEmpty() && mirror.allSavedTrackIds().isNotEmpty()) {
            // The host only shows its "Liked Tracks" card when the playlist
            // list is non-empty, so keep the tab usable with a synthetic
            // saved-tracks playlist instead of an empty state.
            val liked = runCatching { likedSongsPlaylist() }.getOrNull()
            if (liked != null) {
                return PaginationResult(
                    items = listOf(liked),
                    totalCount = 1,
                    nextPagination = null,
                )
            }
        }
        val slice = all.drop(paging.offset).take(paging.limit)
        return PaginationResult(
            items = slice,
            totalCount = all.size,
            nextPagination = if (paging.offset + slice.size < all.size) {
                PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
            } else {
                null
            },
        )
    }

    /** Synthetic saved-tracks playlist; keeps the Playlists tab non-empty. */
    private suspend fun likedSongsPlaylist(): MetadataPlaylist {
        val count = mirror.allSavedTrackIds().size
        return MetadataPlaylist(
            id = LIKED_SONGS_ID,
            title = "Liked Songs",
            description = "Your saved tracks",
            thumbnails = emptyList(),
            trackCount = count,
            externalUri = null,
            owner = null,
        )
    }

    private suspend fun likedSongsTracks(paging: PaginationStrategy.Offset): PaginationResult<MetadataTrack> {
        val ids = mirror.allSavedTrackIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapNotNull { id ->
            runCatching {
                store.cachedTrack(id) ?: run {
                    val info = client.streams(id)
                    store.cacheStreams(id, info)
                    val track = info.toTrack(id)
                    store.rememberTrack(track)
                    track
                }
            }.getOrNull()
        }
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            nextPagination = if (paging.offset + slice.size < ids.size) {
                PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedPlaylists(ids: List<String>): List<Boolean> = library.isSavedPlaylists(ids)

    override suspend fun savePlaylists(ids: List<String>) = library.savePlaylists(ids)

    override suspend fun removeSavedPlaylists(ids: List<String>) = library.removePlaylists(ids)

    override suspend fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborating: Boolean,
        imageBase64: String,
        trackIds: List<String>,
    ): MetadataPlaylist {
        val record = StoredPlaylist(
            id = newLocalId(name),
            name = name,
            description = description,
            isPublic = isPublic,
            isCollaborating = isCollaborating,
            imageBase64 = imageBase64,
            trackIds = trackIds,
        )
        library.upsertPlaylist(record)
        // Newly created playlists must surface in the library's Playlists tab,
        // which lists savedPlaylists() (LocalLibrary.savedPlaylists) only.
        library.savePlaylists(listOf(record.id))
        mirror.mirrorCreatePlaylist(record.id, name, trackIds)
        return record.toEntity()
    }

    override suspend fun updatePlaylist(
        id: String,
        name: String?,
        description: String?,
        isPublic: Boolean?,
        isCollaborating: Boolean?,
        imageBase64: String?,
        trackIds: List<String>?,
    ): MetadataPlaylist {
        val existing = storedLocalPlaylist(id)
            ?: throw IllegalStateException("Only locally created playlists can be edited: $id")
        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            isPublic = isPublic ?: existing.isPublic,
            isCollaborating = isCollaborating ?: existing.isCollaborating,
            imageBase64 = imageBase64 ?: existing.imageBase64,
            trackIds = trackIds ?: existing.trackIds,
        )
        library.upsertPlaylist(updated)
        if (updated.name != existing.name) mirror.mirrorRenamePlaylist(id, updated.name)
        return updated.toEntity()
    }

    override suspend fun deletePlaylist(id: String) {
        mirror.mirrorDeletePlaylist(id)
        library.deleteStoredPlaylist(id)
        library.removePlaylists(listOf(id))
    }

    override suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId)
            ?: throw IllegalStateException("Only locally created playlists can be edited: $playlistId")
        val updated = existing.copy(trackIds = (existing.trackIds + trackIds).distinct())
        library.upsertPlaylist(updated)
        mirror.mirrorAddTracks(playlistId, trackIds)
    }

    override suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId)
            ?: throw IllegalStateException("Only locally created playlists can be edited: $playlistId")
        val updated = existing.copy(trackIds = existing.trackIds.filterNot { it in trackIds })
        library.upsertPlaylist(updated)
        mirror.mirrorRemoveTracks(playlistId, trackIds)
    }

    private suspend fun storedLocalPlaylist(id: String): StoredPlaylist? =
        if (id.startsWith("piped-")) library.storedPlaylist(id) else null

    private suspend fun localTracksPage(
        record: StoredPlaylist,
        paging: PaginationStrategy.Offset,
    ): PaginationResult<MetadataTrack> {
        val slice = record.trackIds.drop(paging.offset).take(paging.limit)
        val items = slice.map { id -> resolveLocalTrack(id) }
        return PaginationResult(
            items = items.filterNotNull(),
            totalCount = record.trackIds.size,
            nextPagination = if (paging.offset + paging.limit < record.trackIds.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    private suspend fun resolveLocalTrack(id: String): MetadataTrack? = trackSemaphore.withPermit {
        runCatching {
            store.cachedTrack(id)?.let { return@withPermit it }
            val info = store.cachedStreams(id) ?: run {
                val fetched = client.streams(id)
                store.cacheStreams(id, fetched)
                fetched
            }
            val track = info.toTrack(id)
            store.rememberTrack(track)
            track
        }.getOrNull()
    }

    /** Account playlist tracks: cached rows (offline) or a bounded live walk. */
    private suspend fun accountTracksPage(
        account: CachedAccountPlaylist,
        paging: PaginationStrategy.Offset,
    ): PaginationResult<MetadataTrack> {
        val rows = mirror.rowsFor(account.id)
        val total = if (account.trackCount > 0) account.trackCount else rows.tracks.size
        val slice = rows.tracks.drop(paging.offset).take(paging.limit)
        return PaginationResult(
            items = slice,
            totalCount = total,
            nextPagination = if (paging.offset + slice.size < total && slice.isNotEmpty()) {
                PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
            } else {
                null
            },
        )
    }

    companion object {
        private val trackSemaphore = Semaphore(4)

        private fun newLocalId(name: String): String {
            val base = (name.hashCode() and 0x7FFFFFFF).toString(36)
            val salt = (kotlin.random.Random.nextInt(0, 0x7FFFFFFF)).toString(36)
            return "piped-$base-$salt"
        }
    }
}

private fun StoredPlaylist.toEntity(): MetadataPlaylist = MetadataPlaylist(
    id = id,
    title = name,
    description = description,
    thumbnails = emptyList(),
    trackCount = trackIds.size,
    externalUri = null,
    owner = null,
)

private fun CachedAccountPlaylist.toEntity(): MetadataPlaylist = MetadataPlaylist(
    id = id,
    title = name,
    description = "Playlist on your Piped account",
    thumbnails = emptyList(),
    trackCount = trackCount.coerceAtLeast(0),
    externalUri = null,
    owner = null,
)
