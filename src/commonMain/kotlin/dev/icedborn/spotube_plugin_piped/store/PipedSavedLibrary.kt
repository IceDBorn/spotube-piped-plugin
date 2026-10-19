package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import dev.icedborn.spotube_plugin_piped.sync.StorageSync
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope

/** The signed-in account's library: the cached snapshot, write-through of saves to the mirror playlists, and
 * the account copies of local playlists. The local library stays the source of truth. */
internal class PipedSavedLibrary(
    httpClient: HttpClientAPI,
    store: EntityStore,
    private val library: LocalLibrary,
    albumLookup: AlbumLookup,
    instanceSource: InstanceSource,
    // Background refreshes run here; the plugin passes its main scope, tests their own.
    refreshScope: CoroutineScope,
    // The plugin passes the instance its roles write to, because the write lock is per instance.
    history: PlayHistory = PlayHistory(store),
    private val sync: StorageSync? = null,
    private val sessionProvider: suspend () -> PipedAccount?,
) {
    private val http = AccountHttp(httpClient)
    private val bindings = SavedBindings(store)
    private val representatives = Representatives(http, store, instanceSource)
    private val rows = AccountRows(http, store, refreshScope, sessionProvider)
    private val playlists = PlaylistMirror(http, store, library, bindings, sessionProvider)
    private val historySync = HistorySync(http, store, bindings, history, refreshScope, sessionProvider)
    private val cache = AccountCache(
        http, store, library, instanceSource, bindings, rows, playlists,
        SavedSetResolver(store, bindings, albumLookup, representatives),
        historySync, refreshScope, sessionProvider, sync,
    )
    private val writer = MirrorWriter(
        http,
        library,
        bindings,
        cache,
        representatives,
        MirrorRemoval(http, store, bindings, cache, representatives),
        sessionProvider,
    )

    suspend fun account(): PipedAccount? = sessionProvider()

    suspend fun cachedState(): AccountCacheState? = cache.cachedState()

    suspend fun accountPlaylist(id: String): CachedAccountPlaylist? = cache.accountPlaylist(id)

    private suspend fun storageOn(): Boolean = sync?.activeAccount() != null

    private suspend fun localSaved(kind: SavedKind): List<String> = when (kind) {
        SavedKind.TRACK -> library.savedTracks()
        SavedKind.ALBUM -> library.savedAlbums()
        SavedKind.ARTIST -> library.savedArtists().map(::canonicalArtistId)
    }

    private suspend fun savedIds(kind: SavedKind): List<String> = if (storageOn()) localSaved(kind) else cache.allSavedIds(kind)

    private suspend fun isSaved(kind: SavedKind, ids: List<String>): List<Boolean> {
        val saved = savedIds(kind).toHashSet()
        return ids.map { kind.canonical(it) in saved }
    }

    suspend fun allSavedTrackIds(): List<String> = savedIds(SavedKind.TRACK)

    suspend fun allSavedAlbumIds(): List<String> = savedIds(SavedKind.ALBUM)

    suspend fun allSavedArtistIds(): List<String> = savedIds(SavedKind.ARTIST)

    suspend fun isSavedTracks(ids: List<String>): List<Boolean> = isSaved(SavedKind.TRACK, ids)

    suspend fun isSavedAlbums(ids: List<String>): List<Boolean> = isSaved(SavedKind.ALBUM, ids)

    suspend fun isSavedArtists(ids: List<String>): List<Boolean> = isSaved(SavedKind.ARTIST, ids)

    suspend fun maybeRefresh() = cache.maybeRefresh()

    suspend fun refreshCache() = cache.refreshCache()

    suspend fun rowsFor(uuid: String, minRows: Int = 0): CachedRows = rows.rowsFor(uuid, minRows)

    /** A play the host scrobbled, for storage when it is active and otherwise for the account's history log. */
    suspend fun played(track: MetadataTrack) {
        if (sync?.played(track) == true) return
        historySync.onPlay(track)
    }

    suspend fun save(kind: SavedKind, ids: List<String>) {
        if (sync?.librarySaved(kind, ids, saved = true) == true) return
        writer.save(kind, ids)
    }

    suspend fun remove(kind: SavedKind, ids: List<String>) {
        if (sync?.librarySaved(kind, ids, saved = false) == true) return
        writer.remove(kind, ids)
    }

    suspend fun savedPlaylistsChanged(ids: List<String>, saved: Boolean) {
        sync?.savedPlaylistsChanged(ids, saved)
    }

    /** With storage active no mirror row decides ownership, so the unsave always reaches the local library. */
    suspend fun removeLibraryEntries(kind: SavedKind, ids: List<String>) {
        if (!storageOn()) return writer.removeLibraryEntries(kind, ids)
        when (kind) {
            SavedKind.TRACK -> library.removeTracks(ids)
            SavedKind.ALBUM -> library.removeAlbums(ids)
            SavedKind.ARTIST -> library.removeArtists(ids)
        }
    }

    suspend fun mirrorCreatePlaylist(localId: String, name: String, videoIds: List<String>): String? {
        if (sync?.playlistTouched(localId) == true) return null
        return playlists.create(localId, name, videoIds)
    }

    suspend fun mirrorAddTracks(localId: String, videoIds: List<String>): Boolean =
        sync?.playlistTouched(localId) == true || playlists.addTracks(localId, videoIds)

    suspend fun mirrorRemoveTracks(localId: String, videoIds: List<String>): Boolean =
        sync?.playlistTouched(localId) == true || playlists.removeTracks(localId, videoIds)

    suspend fun mirrorRenamePlaylist(localId: String, name: String): Boolean =
        sync?.playlistTouched(localId) == true || playlists.rename(localId, name)

    suspend fun mirrorDeletePlaylist(localId: String): Boolean = sync?.playlistTouched(localId) == true || playlists.delete(localId)

    /** Marks an edit with no Piped counterpart, such as the description or cover, for storage. */
    suspend fun touchPlaylist(localId: String) {
        sync?.playlistTouched(localId)
    }
}
