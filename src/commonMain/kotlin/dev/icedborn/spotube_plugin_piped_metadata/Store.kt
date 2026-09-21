package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val TRACK_KEY = "track:"
private const val LIST_KEY = "list:"
private const val CHANNEL_KEY = "channel:"
private const val STREAMS_KEY = "streams:"
private const val TRACK_ALBUM_KEY = "track-album:"
private const val LIBRARY_KEY = "piped.library"
private const val PLAYLISTS_KEY = "piped.playlists"
private const val ACCOUNT_STATE_KEY = "acct.state"

/** Row cache prefix shared with PlaylistRows so account playlists load offline. */
internal const val PLAYLIST_ROWS_PREFIX = "playlist.rows:"

/** A Piped account playlist as cached after a refresh. */
@Serializable
data class CachedAccountPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int = -1,
)

/**
 * Locally cached snapshot of the account: its playlists, the saved sets
 * resolved from the "Spotube - Albums/Artists/Favorites" mirror playlists,
 * and the wall-clock time of the last successful refresh (epoch millis).
 */
@Serializable
data class AccountCacheState(
    val playlists: List<CachedAccountPlaylist> = emptyList(),
    val savedTracks: List<String> = emptyList(),
    val savedAlbums: List<String> = emptyList(),
    val savedArtists: List<String> = emptyList(),
    val refreshedAt: Long = 0L,
)

/**
 * Small JSON cache over the host persistence API. Raw responses are kept under
 * typed keys so converted entities and revisit pages cost zero network calls.
 */
class EntityStore(private val storage: PersistedStorageAPI) {

    private val memory = mutableMapOf<String, JsonElement>()

    suspend fun get(key: String): JsonElement? {
        memory[key]?.let { return it }
        val raw = storage.getString(key) ?: return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()?.also { memory[key] = it }
    }

    suspend fun put(key: String, value: JsonElement) {
        memory[key] = value
        runCatching { storage.putString(key, value.toString()) }
    }

    suspend fun remove(key: String) {
        memory.remove(key)
        runCatching { storage.remove(key) }
    }

    suspend fun rememberTrack(track: MetadataTrack) {
        put(TRACK_KEY + track.id, json.encodeToJsonElement(track))
    }

    suspend fun cachedTrack(id: String): MetadataTrack? {
        val raw = get(TRACK_KEY + id) ?: return null
        return runCatching { json.decodeFromJsonElement<MetadataTrack>(raw) }.getOrNull()
    }

    suspend fun cachedAlbumPlaylist(id: String): PipedPlaylistPage? {
        val raw = get(LIST_KEY + id) ?: return null
        return runCatching { json.decodeFromJsonElement<PipedPlaylistPage>(raw) }.getOrNull()
    }

    suspend fun cacheAlbumPlaylist(id: String, page: PipedPlaylistPage) {
        put(LIST_KEY + id, json.encodeToJsonElement(page))
    }

    suspend fun cachedChannel(id: String): PipedChannelInfo? {
        val raw = get(CHANNEL_KEY + id) ?: return null
        return runCatching { json.decodeFromJsonElement<PipedChannelInfo>(raw) }.getOrNull()
    }

    suspend fun cacheChannel(id: String, info: PipedChannelInfo) {
        put(CHANNEL_KEY + id, json.encodeToJsonElement(info))
    }

    suspend fun cachedStreams(id: String): PipedStreamsInfo? {
        val raw = get(STREAMS_KEY + id) ?: return null
        return runCatching { json.decodeFromJsonElement<PipedStreamsInfo>(raw) }.getOrNull()
    }

    suspend fun cacheStreams(id: String, info: PipedStreamsInfo) {
        put(STREAMS_KEY + id, json.encodeToJsonElement(info))
    }

    /** Album id resolved for a track id; "none" marks a failed lookup so it is not retried. */
    suspend fun cachedTrackAlbum(trackId: String): String? {
        val raw = get(TRACK_ALBUM_KEY + trackId) as? JsonPrimitive ?: return null
        return raw.contentOrNull
    }

    suspend fun cacheTrackAlbum(trackId: String, albumId: String) {
        put(TRACK_ALBUM_KEY + trackId, JsonPrimitive(albumId))
    }

    suspend fun cachedAccountState(): AccountCacheState? {
        val raw = get(ACCOUNT_STATE_KEY) ?: return null
        return runCatching { json.decodeFromJsonElement<AccountCacheState>(raw) }.getOrNull()
    }

    suspend fun cacheAccountState(state: AccountCacheState) {
        put(ACCOUNT_STATE_KEY, json.encodeToJsonElement(state))
    }
}

@Serializable
private data class LibraryState(
    val tracks: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<String> = emptyList(),
)

@Serializable
data class StoredPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val isPublic: Boolean = true,
    val isCollaborating: Boolean = false,
    val imageBase64: String = "",
    val trackIds: List<String> = emptyList(),
)

/**
 * Saved tracks/albums/artists/playlists and created playlists are kept in the
 * plugin's own storage: Piped has no public account API for them.
 */
class LocalLibrary(private val store: EntityStore) {

    private var state: LibraryState? = null

    private suspend fun current(): LibraryState {
        state?.let { return it }
        val loaded = store.get(LIBRARY_KEY)?.let { raw ->
            runCatching { json.decodeFromJsonElement<LibraryState>(raw) }.getOrNull()
        } ?: LibraryState()
        state = loaded
        return loaded
    }

    private suspend fun save(newState: LibraryState) {
        state = newState
        store.put(LIBRARY_KEY, json.encodeToJsonElement(newState))
    }

    suspend fun savedTracks(): List<String> = current().tracks
    suspend fun savedAlbums(): List<String> = current().albums
    suspend fun savedArtists(): List<String> = current().artists
    suspend fun savedPlaylists(): List<String> = current().playlists

    suspend fun isSavedTracks(ids: List<String>): List<Boolean> {
        val set = current().tracks.toHashSet()
        return ids.map { it in set }
    }

    suspend fun isSavedAlbums(ids: List<String>): List<Boolean> {
        val set = current().albums.toHashSet()
        return ids.map { it in set }
    }

    suspend fun isSavedArtists(ids: List<String>): List<Boolean> {
        val set = current().artists.toHashSet()
        return ids.map { it in set }
    }

    suspend fun isSavedPlaylists(ids: List<String>): List<Boolean> {
        val set = current().playlists.toHashSet()
        return ids.map { it in set }
    }

    suspend fun saveTracks(ids: List<String>) = save(current().let { it.copy(tracks = (it.tracks + ids).distinct()) })
    suspend fun removeTracks(ids: List<String>) = save(current().let { it.copy(tracks = it.tracks.filterNot { x -> x in ids }) })
    suspend fun saveAlbums(ids: List<String>) = save(current().let { it.copy(albums = (it.albums + ids).distinct()) })
    suspend fun removeAlbums(ids: List<String>) = save(current().let { it.copy(albums = it.albums.filterNot { x -> x in ids }) })
    suspend fun saveArtists(ids: List<String>) = save(current().let { it.copy(artists = (it.artists + ids).distinct()) })
    suspend fun removeArtists(ids: List<String>) = save(current().let { it.copy(artists = it.artists.filterNot { x -> x in ids }) })
    suspend fun savePlaylists(ids: List<String>) = save(current().let { it.copy(playlists = (it.playlists + ids).distinct()) })
    suspend fun removePlaylists(ids: List<String>) = save(current().let { it.copy(playlists = it.playlists.filterNot { x -> x in ids }) })

    // ── locally created playlists ──────────────────────────────────────────

    suspend fun storedPlaylists(): List<StoredPlaylist> {
        val raw = store.get(PLAYLISTS_KEY) ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<StoredPlaylist>>(raw) }.getOrDefault(emptyList())
    }

    private suspend fun saveStoredPlaylists(records: List<StoredPlaylist>) {
        store.put(PLAYLISTS_KEY, json.encodeToJsonElement(records))
    }

    suspend fun storedPlaylist(id: String): StoredPlaylist? = storedPlaylists().firstOrNull { it.id == id }

    suspend fun upsertPlaylist(record: StoredPlaylist) {
        val records = storedPlaylists().filterNot { it.id == record.id } + record
        saveStoredPlaylists(records)
    }

    suspend fun deleteStoredPlaylist(id: String) {
        saveStoredPlaylists(storedPlaylists().filterNot { it.id == id })
    }
}

/** Loads and extends playlist/album rows lazily through the Piped nextpage token. */
class PlaylistRows(
    private val client: PipedClient,
    private val store: EntityStore,
) {

    suspend fun page(
        id: String,
        isAlbum: Boolean,
        album: dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum.Detailed?,
        paging: PaginationStrategy.Offset,
        knownTotal: Int,
    ): PaginationResult<MetadataTrack> {
        val key = (if (isAlbum) "album.rows:" else PLAYLIST_ROWS_PREFIX) + id
        var rows = store.get(key)?.let { raw ->
            runCatching { json.decodeFromJsonElement<CachedRows>(raw) }.getOrNull()
        } ?: seedFromAlbumCache(id, album)

        // Extend until the requested page is covered or the playlist is exhausted.
        while (rows.tracks.size < paging.offset + paging.limit && !rows.complete) {
            val token = rows.nextpage
            if (token.isNullOrBlank()) {
                rows = rows.copy(complete = true)
                break
            }
            val page = client.playlistNextPage(id, token)
            val baseIndex = rows.tracks.size
            val converted = page.relatedStreams.mapIndexedNotNull { index, item ->
                item.toTrack(album, if (isAlbum) baseIndex + index + 1 else null)
            }
            rows = rows.copy(
                tracks = rows.tracks + converted,
                nextpage = page.nextpage,
                complete = page.nextpage.isNullOrBlank() || page.relatedStreams.isEmpty(),
            )
        }
        store.put(key, json.encodeToJsonElement(rows))

        val tracks = rows.tracks
        val slice = tracks.drop(paging.offset).take(paging.limit)
        val total = if (knownTotal > 0) knownTotal else tracks.size
        val more = !rows.complete || paging.offset + slice.size < total
        return PaginationResult(
            items = slice,
            totalCount = total,
            // Only offer another page when this one came back full.
            nextPagination = if (more && slice.size >= paging.limit) {
                PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
            } else {
                null
            },
        )
    }

    /** First rows come from the cached /playlists page, fetching it if this is the first visit. */
    private suspend fun seedFromAlbumCache(
        id: String,
        album: dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum.Detailed?,
    ): CachedRows {
        val cached = store.cachedAlbumPlaylist(id) ?: run {
            val page = client.playlist(id)
            store.cacheAlbumPlaylist(id, page)
            page
        }
        val converted = cached.relatedStreams.mapIndexedNotNull { index, item ->
            item.toTrack(album, index + 1)
        }
        return CachedRows(
            tracks = converted,
            nextpage = cached.nextpage,
            complete = cached.nextpage.isNullOrBlank(),
        )
    }
}
