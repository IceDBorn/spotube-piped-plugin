package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
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
        val page = if (cached != null && (cached.relatedStreams.isNotEmpty() || !cached.nextpage.isNullOrBlank())) cached else {
            // playlist() nulls BLANK bodies (throttles); a decodable page — even EMPTY — is authoritative: cache it.
            // Re-fetch failures degrade to the cached page (real header) — the unwrapped caller must not hard-fail.
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

    override suspend fun getPlaylistTracks(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataTrack> {
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
        // Only user-visible playlists: created, cached account, and bookmarked. The internal sync mirrors
        // ("Spotube - Albums/Artists/Favorites") stay hidden.
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
        val ids = mirror.allSavedTrackIds()
        // Same cachedTrack -> cachedStreams -> client.streams chain as resolveLocalTrack, so cached streams
        // (AlbumLookup / artistChannelOf writes) are served offline instead of dropped rows.
        // ids come from the authoritative LOCAL saved set (exact count): page by the fixed limit so a dead-id run
        // never truncates the pager at one window and hides the valid tail. The slice guard belongs to account lists.
        return ids.drop(paging.offset).take(paging.limit)
            .mapConcurrently { resolveLocalTrack(it) }
            .filterNotNull()
            .let { window -> PaginationResult(window, ids.size, ids.offsetPage(paging).nextPagination) }
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
        // Remote FIRST, then local commit (fail-soft): a committed local change whose rebuild failed would leave the
        // mirror old with no retry path (identical updates skip this branch); failure keeps the local record OLD.
        if (updated.name != existing.name) {
            if (!mirror.mirrorRenamePlaylist(id, updated.name)) {
                throw IllegalStateException("Piped playlist mirror rename failed")
            }
        }
        if (updated.trackIds != existing.trackIds) {
            // Bulk replace (reorder/clear/re-set): incremental add/remove cannot reorder, so rebuild — and remove
            // NEW-list rows too, else a surviving ghost is skipped by the add and pinned at its old position (round-109).
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
        // Fail-soft like save(): keep the local playlist + binding when the remote mirror could not be deleted, so
        // the retry re-attempts it (a local removal with a surviving mirror orphans the playlist).
        if (!mirror.mirrorDeletePlaylist(id)) return
        library.deleteStoredPlaylist(id)
        library.removePlaylists(listOf(id))
    }

    override suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId) ?: throw notEditable(playlistId)
        val updated = existing.copy(trackIds = (existing.trackIds + trackIds).distinct())
        // Remote FIRST, then local commit (fail-soft like updatePlaylist): committing first applies the edit while
        // the mirror stays old on a transient failure, with nothing reconciling it; failure keeps the record OLD.
        if (!mirror.mirrorAddTracks(playlistId, trackIds)) throw IllegalStateException("Piped playlist mirror add failed")
        library.upsertPlaylist(updated)
    }

    override suspend fun removeTracksFromPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val existing = storedLocalPlaylist(playlistId) ?: throw notEditable(playlistId)
        val updated = existing.copy(trackIds = existing.trackIds.filterNot { it in trackIds })
        // Remote FIRST, then local commit (same discipline as addTracks/updatePlaylist): a failed remove leaves the
        // ghost row in the mirror; committing first diverges with no re-sync. Failure keeps the OLD record listed.
        if (!mirror.mirrorRemoveTracks(playlistId, trackIds)) throw IllegalStateException("Piped playlist mirror remove failed")
        library.upsertPlaylist(updated)
    }

    private suspend fun storedLocalPlaylist(id: String): StoredPlaylist? =
        if (id.startsWith("piped-")) library.storedPlaylist(id) else null

    private suspend fun localTracksPage(
        record: StoredPlaylist,
        paging: PaginationStrategy.Offset,
    ): PaginationResult<MetadataTrack> {
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
        val rows = withRowsLock(PLAYLIST_ROWS_PREFIX + account.id) { mirror.rowsFor(account.id, paging.offset + paging.limit) }
        val total = if (account.trackCount > 0) account.trackCount else rows.tracks.size
        val slice = rows.tracks.drop(paging.offset).take(paging.limit)
        // Keep paging while the row cache is OPEN: the listing trackCount lags the live walk (TTL refresh), and
        // truncating at a stale count hides web-grown rows until the next refresh (Store.page() twin, same escape).
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
