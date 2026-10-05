package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope

/** The signed-in account's library: the cached snapshot, write-through of saves to the mirror playlists, and
 * the account copies of local playlists. The local library stays the source of truth. */
internal class PipedSavedLibrary(
    httpClient: HttpClientAPI,
    store: EntityStore,
    library: LocalLibrary,
    albumLookup: AlbumLookup,
    instanceSource: InstanceSource,
    // Background refreshes run here; the plugin passes its main scope, tests their own.
    refreshScope: CoroutineScope,
    // The plugin passes the instance its roles write to, because the write lock is per instance.
    history: PlayHistory = PlayHistory(store),
    sessionProvider: suspend () -> PipedAccount?,
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
        historySync, refreshScope, sessionProvider,
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

    suspend fun cachedState(): AccountCacheState? = cache.cachedState()

    suspend fun accountPlaylist(id: String): CachedAccountPlaylist? = cache.accountPlaylist(id)

    suspend fun allSavedTrackIds(): List<String> = cache.allSavedIds(SavedKind.TRACK)

    suspend fun allSavedAlbumIds(): List<String> = cache.allSavedIds(SavedKind.ALBUM)

    suspend fun allSavedArtistIds(): List<String> = cache.allSavedIds(SavedKind.ARTIST)

    suspend fun isSavedTracks(ids: List<String>): List<Boolean> = cache.isSaved(SavedKind.TRACK, ids)

    suspend fun isSavedAlbums(ids: List<String>): List<Boolean> = cache.isSaved(SavedKind.ALBUM, ids)

    suspend fun isSavedArtists(ids: List<String>): List<Boolean> = cache.isSaved(SavedKind.ARTIST, ids)

    suspend fun maybeRefresh() = cache.maybeRefresh()

    suspend fun refreshCache() = cache.refreshCache()

    suspend fun rowsFor(uuid: String, minRows: Int = 0): CachedRows = rows.rowsFor(uuid, minRows)

    /** A play the host scrobbled, queued for the account's history log. */
    suspend fun played(track: MetadataTrack) = historySync.onPlay(track)

    suspend fun save(kind: SavedKind, ids: List<String>) = writer.save(kind, ids)

    suspend fun remove(kind: SavedKind, ids: List<String>) = writer.remove(kind, ids)

    suspend fun removeLibraryEntries(kind: SavedKind, ids: List<String>) = writer.removeLibraryEntries(kind, ids)

    suspend fun mirrorCreatePlaylist(localId: String, name: String, videoIds: List<String>): String? =
        playlists.create(localId, name, videoIds)

    suspend fun mirrorAddTracks(localId: String, videoIds: List<String>): Boolean = playlists.addTracks(localId, videoIds)

    suspend fun mirrorRemoveTracks(localId: String, videoIds: List<String>): Boolean = playlists.removeTracks(localId, videoIds)

    suspend fun mirrorRenamePlaylist(localId: String, name: String): Boolean = playlists.rename(localId, name)

    suspend fun mirrorDeletePlaylist(localId: String): Boolean = playlists.delete(localId)
}
