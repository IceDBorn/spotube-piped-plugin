package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.toPlaylist
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.distinctWindow
import dev.icedborn.spotube_plugin_piped.core.getOffsetOrDefault
import dev.icedborn.spotube_plugin_piped.core.mapConcurrently
import dev.icedborn.spotube_plugin_piped.core.offsetPage
import dev.icedborn.spotube_plugin_piped.core.savedPage
import dev.icedborn.spotube_plugin_piped.settings.LIKED_SONGS_ID
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylist
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RECENTLY_PLAYED_ID
import dev.icedborn.spotube_plugin_piped.settings.isSynthetic
import dev.icedborn.spotube_plugin_piped.settings.syntheticOrigin
import dev.icedborn.spotube_plugin_piped.store.CachedAccountPlaylist
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlaylistRows
import dev.icedborn.spotube_plugin_piped.store.StoredPlaylist
import dev.icedborn.spotube_plugin_piped.store.offsetPage
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Real playlists come from Piped; created playlists and the saved list live in plugin storage — Piped has no
 * public playlist API. */
internal class RealMetadataPlaylistAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    private val history: PlayHistory,
    private val libraryPlaylist: LibraryPlaylistSetting,
    // Row cache anchors run here while cached rows are served.
    private val scope: CoroutineScope? = null,
) : MetadataPlaylistAPI {

    override suspend fun getPlaylist(id: String): MetadataPlaylist = when (id) {
        // Both synthetic ids resolve whatever the setting says, so an open page survives a setting change.
        RECENTLY_PLAYED_ID -> recentlyPlayedPlaylist() ?: syntheticEntity(id)

        LIKED_SONGS_ID -> likedSongsPlaylist() ?: syntheticEntity(id)

        else -> storedPlaylist(id)
    }

    private suspend fun storedPlaylist(id: String): MetadataPlaylist {
        storedLocalPlaylist(id)?.let { return it.toEntity() }
        mirror.accountPlaylist(id)?.let { return it.toEntity() }
        val cached = store.cachedAlbumPlaylist(id)
        val page = if (cached != null && (cached.relatedStreams.isNotEmpty() || !cached.nextpage.isNullOrBlank())) {
            cached
        } else {
            // A decodable page, even an empty one, is cached. A failed fetch falls back to the cached page.
            val fetched = orNull { client.playlist(id) }
            if (fetched != null) {
                store.cacheAlbumPlaylist(id, fetched)
                fetched
            } else if (cached != null) {
                cached
            } else {
                return MetadataPlaylist(
                    id = id,
                    title = "",
                    description = null,
                    thumbnails = emptyList(),
                    trackCount = 0,
                    externalUri = null,
                    owner = null,
                )
            }
        }
        return page.toPlaylist(id)
    }

    override suspend fun getPlaylistTracks(id: String, pagination: PaginationStrategy?): PaginationResult<MetadataTrack> {
        val paging = pagination.getOffsetOrDefault()
        if (id == RECENTLY_PLAYED_ID) return recentlyPlayedTracks(paging)
        if (id == LIKED_SONGS_ID) return likedSongsTracks(paging)
        storedLocalPlaylist(id)?.let { record ->
            return localTracksPage(record, paging)
        }
        mirror.accountPlaylist(id)?.let { account ->
            return accountTracksPage(account, paging)
        }
        val playlist = getPlaylist(id)
        return PlaylistRows(client, store, scope).page(
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
        // Only user-visible playlists: created, cached account, and bookmarked. The internal sync playlists
        // ("Spotube - Albums/Artists/Favorites/History") stay hidden.
        val created = library.storedPlaylists().map { it.toEntity() }
        val account = mirror.cachedState()?.playlists.orEmpty().map { it.toEntity() }
        val known = (created + account).distinctBy { it.id }
        val knownIds = known.map { it.id }.toHashSet()
        // Bookmarks come last, so only the ones inside the requested window are fetched.
        // An older build stored the Liked Songs id as a bookmark, and that entry has no title or cover.
        val bookmarkIds = library.savedPlaylists().filterNot { it in knownIds || isSynthetic(it) }.distinct()
        val total = known.size + bookmarkIds.size
        // The synthetic item goes in front of page 0 only, and total/next stay computed from the real list,
        // so the offsets of later pages do not shift.
        val synthetic = when (libraryPlaylist.stored()) {
            LibraryPlaylist.ALWAYS -> if (paging.offset == 0) runCatching { syntheticPlaylist() }.getOrNull() else null
            LibraryPlaylist.WHEN_EMPTY -> if (total == 0) runCatching { syntheticPlaylist() }.getOrNull() else null
            LibraryPlaylist.OFF -> null
        }
        val start = minOf(paging.offset, total)
        val end = minOf(paging.offset + paging.limit, total)
        val knownSlice = known.drop(start).take(paging.limit)
        val bookmarkSlice = bookmarkIds.subList(
            (start - known.size).coerceIn(0, bookmarkIds.size),
            (end - known.size).coerceIn(0, bookmarkIds.size),
        )
        val fetched = bookmarkSlice.mapConcurrently { id -> runCatching { storedPlaylist(id) }.getOrNull() }.filterNotNull()
        val slice = listOfNotNull(synthetic) + knownSlice + fetched
        return PaginationResult(
            items = slice,
            totalCount = total,
            // Advance by the window, not the result: a failed bookmark fetch must not stop paging.
            nextPagination = if (end < total) {
                PaginationStrategy.Offset(end, paging.limit)
            } else {
                null
            },
        )
    }

    /** The generated entity for a synthetic id, without a saved-track cover probe. */
    private suspend fun syntheticEntity(id: String): MetadataPlaylist = when (id) {
        RECENTLY_PLAYED_ID -> recentlyPlayedPlaylist() ?: emptyEntity(id, "Recently played")
        else -> likedSongsPlaylist(withCover = false) ?: emptyEntity(id, "Liked Songs")
    }

    private fun emptyEntity(id: String, title: String) = MetadataPlaylist(
        id = id,
        title = title,
        description = null,
        thumbnails = emptyList(),
        trackCount = 0,
        externalUri = null,
        owner = null,
    )

    /** The item [savedPlaylists] shows when the setting asks for one. */
    private suspend fun syntheticPlaylist(): MetadataPlaylist? = recentlyPlayedPlaylist() ?: likedSongsPlaylist()

    private suspend fun recentlyPlayedPlaylist(): MetadataPlaylist? {
        val recent = history.recentTracks(RECENT_LIMIT)
        if (recent.isEmpty()) return null
        return MetadataPlaylist(
            id = RECENTLY_PLAYED_ID,
            title = "Recently played",
            description = "Last $RECENT_LIMIT tracks you played",
            thumbnails = coverOf(recent),
            trackCount = recent.size,
            externalUri = null,
            owner = null,
        )
    }

    /** Synthetic saved-tracks playlist; keeps the Playlists tab non-empty. [withCover] is off for a write on a
     * generated id, which returns the entity without resolving anything. */
    private suspend fun likedSongsPlaylist(withCover: Boolean = true): MetadataPlaylist? {
        val ids = mirror.allSavedTrackIds()
        if (ids.isEmpty()) return null
        return MetadataPlaylist(
            id = LIKED_SONGS_ID,
            title = "Liked Songs",
            description = "Saved tracks. Create a playlist to hide this.",
            thumbnails = if (withCover) coverOfSavedTrack(ids) else emptyList(),
            trackCount = ids.size,
            externalUri = null,
            owner = null,
        )
    }

    /** Cover of the first saved track that resolves; an empty list is what makes the current card look broken. */
    private suspend fun coverOfSavedTrack(ids: List<String>): List<Thumbnail> =
        coverOf(ids.take(COVER_PROBE_LIMIT).mapConcurrently { resolveLocalTrack(it) }.filterNotNull())

    private fun coverOf(tracks: List<MetadataTrack>): List<Thumbnail> =
        tracks.firstNotNullOfOrNull { it.album?.thumbnails?.firstOrNull() ?: it.thumbnails?.firstOrNull() }
            ?.let(::listOf)
            .orEmpty()

    private suspend fun recentlyPlayedTracks(paging: PaginationStrategy.Offset): PaginationResult<MetadataTrack> {
        // History rows are full tracks, so the page needs no fetch.
        return history.recentTracks(RECENT_LIMIT).offsetPage(paging)
    }

    private suspend fun likedSongsTracks(paging: PaginationStrategy.Offset): PaginationResult<MetadataTrack> {
        // resolveLocalTrack reads cached streams first, so saved rows are served offline.
        return savedPage(mirror.allSavedTrackIds(), paging) { resolveLocalTrack(it) }
    }

    override suspend fun isSavedPlaylists(ids: List<String>): List<Boolean> = library.isSavedPlaylists(ids)

    /** A synthetic id is generated, so it is never stored as a bookmark. */
    override suspend fun savePlaylists(ids: List<String>) = library.savePlaylists(ids.filterNot(::isSynthetic))

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
        // A failed mirror is marked pending and retried by the next account refresh.
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
        // Nothing to write: return the generated entity as it stands.
        if (isSynthetic(id)) return syntheticEntity(id)
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
        // The mirror is written before the local record, so a failure leaves both unchanged and the edit can be retried.
        if (updated.name != existing.name) {
            if (!mirror.mirrorRenamePlaylist(id, updated.name)) {
                throw IllegalStateException("Piped playlist mirror rename failed")
            }
        }
        if (updated.trackIds != existing.trackIds) {
            // A reorder needs a rebuild. Rows of the new list are removed too, or the add skips them in their old place.
            val removed = mirror.mirrorRemoveTracks(id, (existing.trackIds + updated.trackIds).distinct())
            val added = removed && mirror.mirrorAddTracks(id, updated.trackIds)
            if (!added) throw IllegalStateException("Piped playlist mirror rebuild failed")
        }
        library.upsertPlaylist(updated)
        return updated.toEntity()
    }

    override suspend fun deletePlaylist(id: String) {
        // A synthetic id has no mirror and no stored record, so a delete must not reach the mirror at all.
        if (isSynthetic(id)) return
        // Keep the local playlist and its binding when the remote copy survives, so a retry can delete it.
        if (!mirror.mirrorDeletePlaylist(id)) throw IllegalStateException("Piped playlist mirror delete failed")
        library.deleteStoredPlaylist(id)
        library.removePlaylists(listOf(id))
    }

    override suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId) ?: throw notEditable(playlistId)
        val updated = existing.copy(trackIds = (existing.trackIds + trackIds).distinct())
        // The mirror is written first, so a failure keeps the record unchanged.
        if (!mirror.mirrorAddTracks(playlistId, trackIds)) throw IllegalStateException("Piped playlist mirror add failed")
        library.upsertPlaylist(updated)
    }

    override suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId) ?: throw notEditable(playlistId)
        val updated = existing.copy(trackIds = existing.trackIds.filterNot { it in trackIds })
        // The mirror is written first, so a failure keeps the record unchanged.
        if (!mirror.mirrorRemoveTracks(playlistId, trackIds)) throw IllegalStateException("Piped playlist mirror remove failed")
        library.upsertPlaylist(updated)
    }

    private suspend fun storedLocalPlaylist(id: String): StoredPlaylist? = if (id.startsWith("piped-")) library.storedPlaylist(id) else null

    private suspend fun localTracksPage(record: StoredPlaylist, paging: PaginationStrategy.Offset): PaginationResult<MetadataTrack> {
        val slice = record.trackIds.drop(paging.offset).take(paging.limit)
        val items = slice.mapConcurrently { id -> resolveLocalTrack(id) }
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
            val info = store.cachedStreams(id) ?: client.streamsForMetadata(id)
                ?.also { store.cacheStreams(id, it) }
                ?: throw IllegalStateException("streams fetch failed for $id")
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
        val rows = mirror.rowsFor(account.id, paging.offset + paging.limit)
        val total = if (account.trackCount > 0) account.trackCount else rows.tracks.size
        val slice = rows.tracks.drop(paging.offset).take(paging.limit)
        // Paging goes on while the cache is open, since the listed count lags rows added on the web.
        val more = !rows.complete || paging.offset + slice.size < total
        return PaginationResult(
            items = distinctWindow(rows.tracks, paging.offset, slice) { it.id },
            totalCount = total,
            nextPagination = if (more && slice.isNotEmpty()) {
                PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
            } else {
                null
            },
        )
    }

    private fun notEditable(id: String): IllegalStateException = if (isSynthetic(id)) {
        IllegalStateException("${syntheticOrigin(id)} and cannot be edited")
    } else {
        IllegalStateException("Only locally created playlists can be edited: $id")
    }

    companion object {
        private val trackSemaphore = Semaphore(4)
        private const val RECENT_LIMIT = 50

        /** How many saved ids a cover probe resolves before giving up. */
        private const val COVER_PROBE_LIMIT = 5

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
