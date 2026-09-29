package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.PipedChannelInfo
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedPlaylistPage
import dev.icedborn.spotube_plugin_piped.client.PipedStreamsInfo
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.AccountIdentity
import dev.icedborn.spotube_plugin_piped.core.distinctWindow
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.offsetPage
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

private const val TRACK_KEY = "track:"
private const val LIST_KEY = "list:"
private const val CHANNEL_KEY = "channel:"
private const val CHANNEL_AT_KEY = "channel.at:"
private const val STREAMS_KEY = "streams:"
private const val TRACK_ALBUM_KEY = "track-album:"
private const val LIBRARY_KEY = "piped.library"
private const val PLAYLISTS_KEY = "piped.playlists"
private const val ACCOUNT_STATE_KEY = "acct.state"

/** Channels (top tracks, uploads, avatar) are re-fetched after this age; a failed re-fetch serves the stale copy. */
internal const val CHANNEL_CACHE_TTL_MS = 12 * 60 * 60_000L

/** In-memory cap in JSON characters; the least recently used entries fall back to host storage. */
private const val MEMORY_CHAR_LIMIT = 8_000_000L

/** Cap on remembered misses, so a long session of lookups cannot grow the set without bound. */
private const val MISSING_LIMIT = 10_000

/** Access order of evictable cache entries (a counter per touch), stored as one object. */
private const val ACCESS_INDEX_KEY = "cache.access"
private val EVICTABLE_PREFIXES = listOf(TRACK_KEY, STREAMS_KEY, LIST_KEY, PLAYLIST_ROWS_PREFIX, ALBUM_ROWS_PREFIX, ALBUM_CHAIN_PREFIX)

/** The access index is written after this many new touches, so reads do not each cost a write. */
private const val ACCESS_FLUSH_EVERY = 100

/** Idle row locks are dropped past this many lists. */
private const val ROWS_LOCK_LIMIT = 200

/** A track-album verdict for a video proven to be on no album. */
internal const val NO_ALBUM = "none"
private const val NO_ALBUM_STAMP = "none@"

/** A proven "no album" is trusted for this long, then the video is looked up again. */
internal const val NO_ALBUM_TTL_MS = 7 * 24 * 60 * 60_000L

/** Row cache prefix shared with PlaylistRows so account playlists load offline. */
internal const val PLAYLIST_ROWS_PREFIX = "playlist.rows:"
internal const val ALBUM_ROWS_PREFIX = "album.rows:"

/** A Piped account playlist as cached after a refresh. */
@Serializable
internal data class CachedAccountPlaylist(val id: String, val name: String, val trackCount: Int = -1)

/** Locally cached account snapshot: playlists, saved sets from the "Spotube - Albums/Artists/Favorites"
 * mirrors, and the epoch-millis time of the last successful refresh. */
@Serializable
internal data class AccountCacheState(
    val playlists: List<CachedAccountPlaylist> = emptyList(),
    val savedTracks: List<String> = emptyList(),
    val savedAlbums: List<String> = emptyList(),
    val savedArtists: List<String> = emptyList(),
    val refreshedAt: Long = 0L,
    /** Account identity this snapshot belongs to (instance + username). */
    override val instance: String = "",
    override val username: String = "",
) : AccountIdentity

/** Small JSON cache over the host persistence API. Raw responses live under typed keys,
 * so converted entities and revisit pages cost zero network calls. */
internal class EntityStore(private val storage: PersistedStorageAPI, private val memoryCharLimit: Long = MEMORY_CHAR_LIMIT) {

    private val memory = LinkedHashMap<String, JsonElement>()
    private val memorySizes = HashMap<String, Int>()
    private var memoryChars = 0L

    // Keys known to be absent from host storage, so repeated misses skip the host call.
    private val missing = HashSet<String>()

    // Last decoded value per key, reused while the stored element is the same instance.
    private val decoded = HashMap<String, Pair<JsonElement, Any?>>()

    private fun remember(key: String, value: JsonElement, chars: Int) {
        forget(key)
        memory[key] = value
        memorySizes[key] = chars
        memoryChars += chars
        missing.remove(key)
        while (memoryChars > memoryCharLimit && memory.size > 1) forget(memory.keys.first())
    }

    private fun forget(key: String) {
        memory.remove(key)
        decoded.remove(key)
        memorySizes.remove(key)?.let { memoryChars -= it }
    }

    private var access: HashMap<String, Long>? = null
    private var accessTick = 0L

    /** Writes and removes that failed and were only logged. */
    var writeFailures = 0
        private set
    private var unflushedTouches = 0

    private suspend fun accessIndex(): HashMap<String, Long> = access ?: HashMap<String, Long>().also { index ->
        runCatching { storage.getString(ACCESS_INDEX_KEY)?.let { json.decodeFromString<Map<String, Long>>(it) } }
            .getOrNull()?.let(index::putAll)
        accessTick = index.values.maxOrNull() ?: 0L
        access = index
    }

    private suspend fun touch(key: String) {
        if (EVICTABLE_PREFIXES.none { key.startsWith(it) }) return
        val index = accessIndex()
        if (index[key] == accessTick && accessTick > 0) return
        index[key] = ++accessTick
        if (++unflushedTouches >= ACCESS_FLUSH_EVERY) flushAccess()
    }

    private suspend fun flushAccess() {
        val index = access ?: return
        unflushedTouches = 0
        runCatching { storage.putString(ACCESS_INDEX_KEY, json.encodeToString(index as Map<String, Long>)) }
    }

    /** Every key in host storage. */
    suspend fun keys(): List<String> = storage.getKeys()

    /** Deletes the least recently used entries of each prefix in [caps] beyond its cap. [keep] is never evicted
     * and does not count toward a cap. */
    suspend fun evict(caps: Map<String, Int>, keep: Set<String> = emptySet()) {
        val keys = keys()
        val index = accessIndex()
        for ((prefix, cap) in caps) {
            val entries = keys.filter { it.startsWith(prefix) && it !in keep }
            // An entry without a stamp predates the index or lost an unflushed touch; it ages from now on.
            entries.filter { it !in index }.forEach { index[it] = ++accessTick }
            if (entries.size <= cap) continue
            entries.sortedBy { index.getValue(it) }.take(entries.size - cap).forEach { remove(it) }
        }
        index.keys.retainAll(keys.toHashSet())
        flushAccess()
    }

    suspend fun get(key: String): JsonElement? {
        touch(key)
        memory.remove(key)?.let { hit ->
            // Re-insert at the tail, so reads keep hot entries in memory.
            memory[key] = hit
            return hit
        }
        if (key in missing) return null
        val raw = storage.getString(key)
        if (raw == null) {
            if (missing.size > MISSING_LIMIT) missing.clear()
            missing += key
            return null
        }
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()?.also { remember(key, it, raw.length) }
    }

    /** [get] plus decoding, memoized so hot readers do not re-decode large blobs on every call. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> getDecoded(key: String, serializer: KSerializer<T>): T? {
        val raw = get(key) ?: return null
        decoded[key]?.let { (element, value) -> if (element === raw) return value as T? }
        val value = runCatching { json.decodeFromJsonElement(serializer, raw) }.getOrNull()
        decoded[key] = raw to value
        return value
    }

    /** False when the host write failed. Memory then drops the key, so reads and retries see what storage holds. */
    suspend fun put(key: String, value: JsonElement): Boolean {
        touch(key)
        if (memory[key] == value) return true
        val text = value.toString()
        remember(key, value, text.length)
        return try {
            storage.putString(key, text)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writeFailures++
            forget(key)
            storeLog.w { "storage write of ${key.substringBefore(':')} failed: ${e.message}" }
            false
        }
    }

    suspend fun remove(key: String) {
        access?.remove(key)
        forget(key)
        missing += key
        try {
            storage.remove(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writeFailures++
            // Storage still holds the value, so reads go back to it.
            missing.remove(key)
            storeLog.w { "storage remove of ${key.substringBefore(':')} failed: ${e.message}" }
        }
    }

    suspend fun rememberTrack(track: MetadataTrack) {
        put(TRACK_KEY + track.id, json.encodeToJsonElement(track))
    }

    /** Stores [tracks] after a walk, skipping each one already cached unchanged. */
    suspend fun rememberTracks(tracks: List<MetadataTrack>) {
        for (track in tracks) if (cachedTrack(track.id) != track) rememberTrack(track)
    }

    suspend fun cachedTrack(id: String): MetadataTrack? = getDecoded(TRACK_KEY + id, MetadataTrack.serializer())

    suspend fun cachedAlbumPlaylist(id: String): PipedPlaylistPage? = getDecoded(LIST_KEY + id, PipedPlaylistPage.serializer())

    // Page-1 fetch times from this process; the row pager skips its page-1 anchor right after one.
    private val listFetchedAt = HashMap<String, Long>()

    suspend fun cacheAlbumPlaylist(id: String, page: PipedPlaylistPage) {
        listFetchedAt[id] = epochMillis()
        put(LIST_KEY + id, json.encodeToJsonElement(page))
    }

    fun albumPlaylistFetchedWithin(id: String, windowMs: Long): Boolean = listFetchedAt[id]?.let { epochMillis() - it < windowMs } == true

    suspend fun cachedChannel(id: String): PipedChannelInfo? = getDecoded(CHANNEL_KEY + id, PipedChannelInfo.serializer())

    /** Entries cached before timestamps existed count as stale. */
    suspend fun channelFresh(id: String): Boolean {
        val at = (get(CHANNEL_AT_KEY + id) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return false
        return epochMillis() - at < CHANNEL_CACHE_TTL_MS
    }

    suspend fun cacheChannel(id: String, info: PipedChannelInfo) {
        put(CHANNEL_KEY + id, json.encodeToJsonElement(info))
        put(CHANNEL_AT_KEY + id, JsonPrimitive(epochMillis().toString()))
    }

    suspend fun cachedStreams(id: String): PipedStreamsInfo? = getDecoded(STREAMS_KEY + id, PipedStreamsInfo.serializer())

    // relatedStreams (about 20 rows) and audioStreams are never read back from this cache, and the
    // signed audio URLs expire within hours, so neither belongs in stored rows.
    suspend fun cacheStreams(id: String, info: PipedStreamsInfo) {
        put(
            STREAMS_KEY + id,
            json.encodeToJsonElement(info.copy(relatedStreams = emptyList(), audioStreams = emptyList())),
        )
    }

    /** Album id resolved for a track id, or [NO_ALBUM] while a proven "no album" for that video is fresh. */
    suspend fun cachedTrackAlbum(trackId: String): String? {
        val value = (get(TRACK_ALBUM_KEY + trackId) as? JsonPrimitive)?.contentOrNull ?: return null
        if (value == NO_ALBUM) return null
        if (!value.startsWith(NO_ALBUM_STAMP)) return value
        val at = value.removePrefix(NO_ALBUM_STAMP).toLongOrNull() ?: return null
        return NO_ALBUM.takeIf { epochMillis() - at < NO_ALBUM_TTL_MS }
    }

    /** Stamps the unstamped "none" verdicts of schema 2 with the current time, so they expire like new ones. */
    suspend fun stampLegacyNoAlbumVerdicts() {
        for (key in keys().filter { it.startsWith(TRACK_ALBUM_KEY) }) {
            if ((get(key) as? JsonPrimitive)?.contentOrNull == NO_ALBUM) put(key, JsonPrimitive(NO_ALBUM_STAMP + epochMillis()))
        }
    }

    /** True when [trackId] holds a "none" verdict of any age, including the unstamped one of schema 2. */
    suspend fun hasNoAlbumVerdict(trackId: String): Boolean {
        val value = (get(TRACK_ALBUM_KEY + trackId) as? JsonPrimitive)?.contentOrNull ?: return false
        return value == NO_ALBUM || value.startsWith(NO_ALBUM_STAMP)
    }

    /** [albumId] may be [NO_ALBUM], which is stored with its time and trusted for [NO_ALBUM_TTL_MS]. */
    suspend fun cacheTrackAlbum(trackId: String, albumId: String) {
        val value = if (albumId == NO_ALBUM) NO_ALBUM_STAMP + epochMillis() else albumId
        put(TRACK_ALBUM_KEY + trackId, JsonPrimitive(value))
    }

    suspend fun cachedAccountState(): AccountCacheState? = getDecoded(ACCOUNT_STATE_KEY, AccountCacheState.serializer())

    suspend fun cacheAccountState(state: AccountCacheState) {
        put(ACCOUNT_STATE_KEY, json.encodeToJsonElement(state))
    }

    suspend fun removeAccountState() = remove(ACCOUNT_STATE_KEY)

    private val rowsLocks = HashMap<String, Mutex>()

    /** One row writer per list key, shared by every reader of the rows keyspace. */
    suspend fun <T> withRowsLock(key: String, block: suspend () -> T): T {
        if (rowsLocks.size > ROWS_LOCK_LIMIT) rowsLocks.entries.removeAll { !it.value.isLocked }
        return rowsLocks.getOrPut(key) { Mutex() }.withLock { block() }
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
internal data class StoredPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val isPublic: Boolean = true,
    val isCollaborating: Boolean = false,
    val imageBase64: String = "",
    val trackIds: List<String> = emptyList(),
)

/** Saved tracks/albums/artists/playlists and created playlists stay in plugin storage:
 * Piped has no public account API for them. */
internal class LocalLibrary(private val store: EntityStore) {

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
        val set = current().artists.map(::canonicalArtistId).toHashSet()
        return ids.map { canonicalArtistId(it) in set }
    }

    suspend fun isSavedPlaylists(ids: List<String>): List<Boolean> {
        val set = current().playlists.toHashSet()
        return ids.map { it in set }
    }

    suspend fun saveTracks(ids: List<String>) = save(current().let { it.copy(tracks = (it.tracks + ids).distinct()) })
    suspend fun removeTracks(ids: List<String>) = save(current().let { it.copy(tracks = it.tracks.filterNot { x -> x in ids }) })
    suspend fun saveAlbums(ids: List<String>) = save(current().let { it.copy(albums = (it.albums + ids).distinct()) })
    suspend fun removeAlbums(ids: List<String>) = save(current().let { it.copy(albums = it.albums.filterNot { x -> x in ids }) })
    suspend fun saveArtists(ids: List<String>) {
        val canonical = ids.map(::canonicalArtistId)
        save(current().let { it.copy(artists = (it.artists.map(::canonicalArtistId) + canonical).distinct()) })
    }
    suspend fun removeArtists(ids: List<String>) {
        val removed = ids.map(::canonicalArtistId).toHashSet()
        save(current().let { it.copy(artists = it.artists.filterNot { canonicalArtistId(it) in removed }) })
    }
    suspend fun savePlaylists(ids: List<String>) = save(current().let { it.copy(playlists = (it.playlists + ids).distinct()) })
    suspend fun removePlaylists(ids: List<String>) = save(current().let { it.copy(playlists = it.playlists.filterNot { x -> x in ids }) })

    // ── locally created playlists ──────────────────────────────────────────

    suspend fun storedPlaylists(): List<StoredPlaylist> =
        store.getDecoded(PLAYLISTS_KEY, ListSerializer(StoredPlaylist.serializer())).orEmpty()

    /** The stored playlists, or null when the key holds something that does not decode, so a caller that
     * deletes from it can tell a corrupt value from an empty one. */
    suspend fun storedPlaylistsOrNull(): List<StoredPlaylist>? {
        val raw = store.get(PLAYLISTS_KEY) ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(ListSerializer(StoredPlaylist.serializer()), raw) }.getOrNull()
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

private val storeLog = Logger("PipedStore")

/** [block]'s result, or null when it throws; cancellation still propagates. A [label] names the call in the log. */
internal suspend fun <T> orNull(label: String? = null, block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (label != null) storeLog.w { "$label failed: ${e.message}" }
    null
}

/** Serves offset pages of a public playlist or album from its [RowCache]. */
internal class PlaylistRows(
    private val client: PipedClient,
    private val store: EntityStore,
    private val background: CoroutineScope? = null,
) {

    suspend fun page(
        id: String,
        isAlbum: Boolean,
        album: MetadataAlbum.Detailed?,
        paging: PaginationStrategy.Offset,
        knownTotal: Int,
    ): PaginationResult<MetadataTrack> {
        val cache = RowCache(
            store = store,
            key = (if (isAlbum) ALBUM_ROWS_PREFIX else PLAYLIST_ROWS_PREFIX) + id,
            fetchFirst = { client.playlist(id)?.also { store.cacheAlbumPlaylist(id, it) } },
            fetchNext = { token -> client.playlistNextPage(id, token) },
            // Public playlists carry no track numbers.
            convert = { item, position -> item.toTrack(album, if (isAlbum) position else null) },
            background = background,
        )
        val rows = cache.rows(
            minRows = paging.offset + paging.limit,
            seed = store.cachedAlbumPlaylist(id),
            seedFresh = store.albumPlaylistFetchedWithin(id, OPEN_ANCHOR_TTL_MS),
        )
        return rows.offsetPage(paging, knownTotal)
    }
}

/** One offset page of [CachedRows]; another page is offered only when this one came back full. */
internal fun CachedRows.offsetPage(paging: PaginationStrategy.Offset, knownTotal: Int = -1): PaginationResult<MetadataTrack> {
    val slice = tracks.drop(paging.offset).take(paging.limit)
    val total = if (knownTotal > 0) knownTotal else tracks.size
    val more = !complete || paging.offset + slice.size < total
    return PaginationResult(
        // Pagination math stays on the raw slice; only the served items drop repeats.
        items = distinctWindow(tracks, paging.offset, slice) { it.id },
        totalCount = total,
        nextPagination = if (more &&
            slice.size >= paging.limit
        ) {
            PaginationStrategy.Offset(paging.offset + slice.size, paging.limit)
        } else {
            null
        },
    )
}
