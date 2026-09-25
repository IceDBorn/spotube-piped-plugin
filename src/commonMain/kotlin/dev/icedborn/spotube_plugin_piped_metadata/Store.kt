package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
private const val CHANNEL_AT_KEY = "channel.at:"
private const val STREAMS_KEY = "streams:"
private const val TRACK_ALBUM_KEY = "track-album:"
private const val LIBRARY_KEY = "piped.library"
private const val PLAYLISTS_KEY = "piped.playlists"
private const val ACCOUNT_STATE_KEY = "acct.state"

/** COMPLETE playlists/albums are re-verified at most once per this window: they have no refresh path,
 * so a web-side edit after the EOF would stay invisible forever. */
private const val COMPLETE_CACHE_VERIFY_TTL_MS = 15 * 60_000L

/** Channels (top tracks, uploads, avatar) are re-fetched after this age; a failed re-fetch serves the stale copy. */
internal const val CHANNEL_CACHE_TTL_MS = 12 * 60 * 60_000L

/** In-memory entry cap; older entries fall back to host storage. */
private const val MEMORY_LIMIT = 2_000

/** Row cache prefix shared with PlaylistRows so account playlists load offline. */
internal const val PLAYLIST_ROWS_PREFIX = "playlist.rows:"

/** A Piped account playlist as cached after a refresh. */
@Serializable
data class CachedAccountPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int = -1,
)

    /** Locally cached account snapshot: playlists, saved sets from the "Spotube - Albums/Artists/Favorites"
     * mirrors, and the epoch-millis time of the last successful refresh. */
@Serializable
data class AccountCacheState(
    val playlists: List<CachedAccountPlaylist> = emptyList(),
    val savedTracks: List<String> = emptyList(),
    val savedAlbums: List<String> = emptyList(),
    val savedArtists: List<String> = emptyList(),
    val refreshedAt: Long = 0L,
    /** Account identity this snapshot belongs to (instance + username). */
    val instance: String = "",
    val username: String = "",
)

/** Small JSON cache over the host persistence API. Raw responses live under typed keys,
 * so converted entities and revisit pages cost zero network calls. */
class EntityStore(private val storage: PersistedStorageAPI) {

    private val memory = LinkedHashMap<String, JsonElement>()
    // Keys known to be absent from host storage, so repeated misses skip the host call.
    private val missing = HashSet<String>()
    // Last decoded value per key, reused while the stored element is the same instance.
    private val decoded = HashMap<String, Pair<JsonElement, Any?>>()

    private fun remember(key: String, value: JsonElement) {
        memory.remove(key)
        memory[key] = value
        missing.remove(key)
        if (memory.size > MEMORY_LIMIT) {
            val oldest = memory.keys.first()
            memory.remove(oldest)
            decoded.remove(oldest)
        }
    }

    suspend fun get(key: String): JsonElement? {
        memory[key]?.let { return it }
        if (key in missing) return null
        val raw = storage.getString(key)
        if (raw == null) {
            if (missing.size > MEMORY_LIMIT * 5) missing.clear()
            missing += key
            return null
        }
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()?.also { remember(key, it) }
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

    suspend fun put(key: String, value: JsonElement) {
        if (memory[key] == value) return
        remember(key, value)
        runCatching { storage.putString(key, value.toString()) }
    }

    suspend fun remove(key: String) {
        memory.remove(key)
        decoded.remove(key)
        missing += key
        runCatching { storage.remove(key) }
    }

    suspend fun rememberTrack(track: MetadataTrack) {
        put(TRACK_KEY + track.id, json.encodeToJsonElement(track))
    }

    suspend fun cachedTrack(id: String): MetadataTrack? = getDecoded(TRACK_KEY + id, MetadataTrack.serializer())

    suspend fun cachedAlbumPlaylist(id: String): PipedPlaylistPage? =
        getDecoded(LIST_KEY + id, PipedPlaylistPage.serializer())

    // Page-1 fetch times from this process; the row pager skips its page-1 anchor right after one.
    private val listFetchedAt = HashMap<String, Long>()

    suspend fun cacheAlbumPlaylist(id: String, page: PipedPlaylistPage) {
        listFetchedAt[id] = epochMillis()
        put(LIST_KEY + id, json.encodeToJsonElement(page))
    }

    fun albumPlaylistFetchedWithin(id: String, windowMs: Long): Boolean =
        listFetchedAt[id]?.let { epochMillis() - it < windowMs } == true

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

    // relatedStreams (about 20 rows) is never read back from this cache.
    suspend fun cacheStreams(id: String, info: PipedStreamsInfo) {
        put(STREAMS_KEY + id, json.encodeToJsonElement(info.copy(relatedStreams = emptyList())))
    }

    /** Album id resolved for a track id; "none" marks a failed lookup so it is not retried. */
    suspend fun cachedTrackAlbum(trackId: String): String? {
        val raw = get(TRACK_ALBUM_KEY + trackId) as? JsonPrimitive ?: return null
        return raw.contentOrNull
    }

    suspend fun cacheTrackAlbum(trackId: String, albumId: String) {
        put(TRACK_ALBUM_KEY + trackId, JsonPrimitive(albumId))
    }

    suspend fun cachedAccountState(): AccountCacheState? = getDecoded(ACCOUNT_STATE_KEY, AccountCacheState.serializer())

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

/** Saved tracks/albums/artists/playlists and created playlists stay in plugin storage:
 * Piped has no public account API for them. */
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

// Last page-1 anchor + verify per rows key. Shared by PlaylistRows (created per call) and rowsFor.
internal val openCacheVerifiedAt = HashMap<String, Long>()

internal fun openCacheRecentlyVerified(key: String): Boolean =
    openCacheVerifiedAt[key]?.let { epochMillis() - it < OPEN_CACHE_VERIFY_WINDOW_MS } == true

// REVERIFY_LIMIT is shared from PipedSavedLibrary.kt (same package): one
// budget for both cache-extender twins over the shared rows keyspace.

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
        val stored = store.getDecoded(key, CachedRows.serializer())
        var rows = stored ?: seedFromAlbumCache(id, album)
        // A seed from a page 1 fetched moments ago is already anchored: skip the re-fetch below.
        // A token-less open cache still needs the anchor, which is its only live chain.
        val recentlyVerified = !rows.nextpage.isNullOrBlank() && (openCacheRecentlyVerified(key) ||
            (stored == null && store.albumPlaylistFetchedWithin(id, OPEN_CACHE_VERIFY_WINDOW_MS)))

        // Live continuation healed from this call's anchor/verify chain, used by the extension loop below
        // (declared here: the loop runs after the anchor/verify block closes).
        var liveToken: String? = null
        var liveResume = 0
        // RAW-position counterpart of the live re-point: the raw extent of the live chain (anchor or verify);
        // after an interior re-point, appended pages must number from the LIVE position, not the stale tail's.
        var liveRawResume: Int? = null
        // Anchor chain's RAW extent, applied to the numbering base only at a true re-point (the heal sites
        // below): a healthy stored continuation keeps numbering from the last cached row, not from page 1.
        var anchorLiveRaw: Int? = null
        // Extend until the requested page is covered or the playlist is exhausted. COMPLETE caches have no
        // refresh: re-verify at most once per TTL from page 1 (growth re-opens; a failed refetch keeps the old cache).
        if (rows.complete && epochMillis() - rows.lastVerifiedAt > COMPLETE_CACHE_VERIFY_TTL_MS) {
            // Degrade-not-throw like the blank-fallback doctrine: PipedClient GETs throw on non-2xx (429/5xx
            // throttle); a thrown re-verify must behave like a failed refetch (keep old cache), never crash the screen.
            val vpage = runCatching { client.playlist(id) }.getOrNull()
            if (vpage != null) {
                store.cacheAlbumPlaylist(id, vpage)
                val vv = mutableListOf<MetadataTrack>()
                var vpos = 0
                vpage.relatedStreams.forEach { item ->
                    item.toTrack(album, if (isAlbum) vpos + 1 else null)?.let { vv += it }
                    vpos++
                }
                var vtoken = vpage.nextpage
                var vrows = vpage.relatedStreams.size
                // Growth cap: a playlist past the old span + REVERIFY_LIMIT re-opens at the cap token (later
                // extension fetches the rest) — never walk unbounded.
                val vCap = (rows.tracks.size + REVERIFY_LIMIT).coerceAtLeast(1)
                while (vv.size < vCap) {
                    val vt = vtoken ?: break
                    if (vt.isBlank()) break
                    val vp = runCatching { client.playlistNextPage(id, vt) }.getOrNull() ?: break
                    if (vp.relatedStreams.isEmpty()) break
                    vrows = vp.relatedStreams.size
                    vp.relatedStreams.forEach { item ->
                        item.toTrack(album, if (isAlbum) vpos + 1 else null)?.let { vv += it }
                        vpos++
                    }
                    vtoken = vp.nextpage
                }
                val nowTs = epochMillis()
                val liveEnd = vtoken == null && vrows > 0
                when {
                    // An all-dead re-walk (raw final page, ZERO convertible items) proves nothing; liveEnd
                    // would freeze an empty replacement. Keep the old cache unstamped; the next TTL re-verifies.
                    vv.isEmpty() && vrows > 0 -> {}
                    // Authoritative empty page 1 (emptied on the web): open-empty cache (complete=false) so the
                    // next call re-anchors. A DECODED BLANK token here is the throttle shape, never EOF (round-102).
                    vpage.relatedStreams.isEmpty() && vtoken == null ->
                        rows = CachedRows(emptyList(), null, false, 0, nowTs)
                    liveEnd -> {
                        val same = vv.size == rows.tracks.size &&
                            vv.map { it.id } == rows.tracks.map { it.id }
                        if (!same) {
                            // Shrink: truncate to the live span (complete). Growth within the cap: append and
                            // RE-OPEN (complete=false) so the pager offers the new tail despite the stale total.
                            rows = CachedRows(vv, null, complete = vv.size <= rows.tracks.size, resumeIndex = vv.size, lastVerifiedAt = nowTs)
                        } else {
                            rows = rows.copy(lastVerifiedAt = nowTs)
                        }
                    }
                    vv.size >= vCap ->
                        // Cap reached without EOF: the playlist grew past the budget — re-open at the cap token;
                        // later extensions fetch the rest.
                        rows = CachedRows(vv, vtoken, false, vv.size, nowTs)
                    // Ambiguous/blank mid-walk or throttle: no verdict — keep
                    // the old cache and retry on the next call (no stamp).
                }
            }
        }
        // The extension gate ALSO opens for IN-SPAN windows on over-budget caches: the old budget-only verify
        // never saw a shrunken playlist, so in-span requests served ghost rows forever. Window-granular verifying.
        if (!rows.complete && !recentlyVerified &&
            (rows.tracks.size < paging.offset + paging.limit || rows.tracks.size > REVERIFY_LIMIT)
        ) {
            // First-page generation anchor (mirrors rowsFor): an out-of-band edit before the cursor shifts every
            // continuation silently; a page-1 change invalidates the whole cache — re-seed from a FRESH fetch.
            val anchor = runCatching { client.playlist(id) }.getOrNull()
            val anchorIds = anchor?.relatedStreams.orEmpty().mapNotNull { videoIdOf(it.url) }.filter { it.isNotEmpty() }
            if (anchor != null && anchor.relatedStreams.isEmpty() && anchor.nextpage == null) {
                // Authoritative empty page 1: replace the stale span with an OPEN empty cache (complete=false,
                // re-anchors next call). A DECODED BLANK token is the throttle shape, never EOF — keep it (round-102).
                rows = CachedRows(emptyList(), null, complete = false, resumeIndex = 0)
            } else if (anchor != null && anchor.relatedStreams.isNotEmpty()) {
                var anchorOk = true
                for (i in 0 until minOf(anchorIds.size, rows.tracks.size)) {
                    if (anchorIds[i] != rows.tracks[i].id) { anchorOk = false; break }
                }
                if (!anchorOk || rows.tracks.isEmpty()) {
                    // Mismatch OR an EMPTY cache: zero comparisons prove nothing — continuing from the stored
                    // token would append page-2+ rows at position 0. The fresh page 1 is the authoritative seed.
                    store.cacheAlbumPlaylist(id, anchor)
                    val freshConverted = anchor.relatedStreams.mapIndexedNotNull { index, item ->
                        item.toTrack(album, if (isAlbum) index + 1 else null)
                    }
                    val anchorProven = anchor.nextpage == null && freshConverted.isNotEmpty()
                    rows = CachedRows(
                        tracks = freshConverted,
                        nextpage = anchor.nextpage,
                        complete = anchorProven,
                        resumeIndex = freshConverted.size,
                        lastVerifiedAt = if (anchorProven) epochMillis() else 0,
                    )
                }
            }
            // Exact-span re-verify (mirrors rowsFor): re-walk from the anchor's token (any difference replaces the
            // cache with the LIVE walk); window-bounded, no size budget, mismatch widens to the full span (incl. page 1).
            val verifyTrack = mutableListOf<MetadataTrack>()
            // Track numbers use RAW PAGE POSITION (index+1, gaps for dead/odd URLs), not the converted count,
            // like every other writer. A null/blank anchor seeds nothing — the replace gate needs a non-empty verifyTrack.
            var verifyPos = 0
            // Final page's converted count: replace/complete gates must not stamp a proven end on an all-dead
            // final page (converted-row doctrine); the anchor seeds the count when no continuation was fetched.
            var lastVerifyConverted = 0
            anchor?.relatedStreams.orEmpty().forEach { item ->
                item.toTrack(album, if (isAlbum) verifyPos + 1 else null)?.let { verifyTrack += it; lastVerifyConverted++ }
                verifyPos++
            }
            var verifyToken = anchor?.nextpage
            var lastVerifyRows = anchor?.relatedStreams?.size ?: 0
            val verifyEnd = minOf(rows.tracks.size, paging.offset + paging.limit)
            var windowOk = true
            // walkEnd grows to the FULL span when the window mismatches
            // (repair mode); otherwise the walk stops at the window.
            while (verifyTrack.size < (if (windowOk) verifyEnd else rows.tracks.size)) {
                val vt = verifyToken ?: break
                if (vt.isBlank()) break
                // A null continuation (blank/{} throttle) breaks the verify chain without completing it — the
                // same keep-open stance as an empty page below.
                val vpage = client.playlistNextPage(id, vt) ?: break
                if (vpage.relatedStreams.isEmpty()) break
                lastVerifyRows = vpage.relatedStreams.size
                lastVerifyConverted = 0
                vpage.relatedStreams.forEach { item ->
                    item.toTrack(album, if (isAlbum) verifyPos + 1 else null)?.let { verifyTrack += it; lastVerifyConverted++ }
                    verifyPos++
                }
                verifyToken = vpage.nextpage
                if (verifyTrack.size >= verifyEnd && windowOk) {
                    // A mid-window change is evidence the whole cached tail is
                    // stale: keep walking to the span end for a proven verdict.
                    windowOk = verifyTrack.take(verifyEnd).map { it.id } == rows.tracks.take(verifyEnd).map { it.id }
                }
            }
            // FULL-SPAN equality decides the repair: an EOF-proven fuller walk must replace a stale SHORT open
            // cache — otherwise the short span matches its own rows and the pager serves it forever.
            val verifyOk = verifyTrack.size == rows.tracks.size &&
                verifyTrack.take(rows.tracks.size).map { it.id } == rows.tracks.map { it.id }
            // Replace ONLY on proven spans (full coverage, or a genuine EOF with rows on the final page); an
            // EMPTY-page break proves neither — keep the stored cache untouched.
            val spanCovered = verifyTrack.size >= rows.tracks.size
            // EOF parity with rowsFor: only this gate truncates a shrink-within-page-1 (blank-token anchor);
            // eofProven also needs CONVERTED final-page rows — an all-dead end would freeze a truncation.
            val eofProven = verifyToken == null && lastVerifyRows > 0 && lastVerifyConverted > 0
            if (verifyTrack.isNotEmpty() && !verifyOk && (spanCovered || eofProven)) {
                rows = CachedRows(
                    tracks = verifyTrack,
                    nextpage = verifyToken,
                    complete = verifyToken == null && lastVerifyConverted > 0,
                    resumeIndex = verifyTrack.size,
                    lastVerifiedAt = if (verifyToken == null && lastVerifyConverted > 0) epochMillis() else 0,
                )
            }
            if (spanCovered && !verifyToken.isNullOrBlank()) {
                // An INTERIOR stored resume double-counts with a verifyPos base (71..80 vs 41..50): when
                // verifyOk, re-base the cache onto the VERIFY chain, which ends exactly at the span end.
                if (rows.resumeIndex < rows.tracks.size && verifyOk) {
                    rows = rows.copy(nextpage = verifyToken, resumeIndex = rows.tracks.size)
                    store.put(key, json.encodeToJsonElement(rows))
                }
                liveToken = verifyToken
                liveResume = rows.tracks.size
                // Only a resume AT the cache end makes verifyPos the correct post-span base; an interior
                // resume that could not be re-based keeps the STORED chain's own raw alignment (double-count guard).
                if (rows.resumeIndex >= rows.tracks.size) liveRawResume = verifyPos
            }
            if (liveToken == null && !anchor?.nextpage.isNullOrBlank()) {
                // Verify broke early: resume at the anchor's own span end. resumeIndex is CONVERTED — count
                // anchor raw items via the same toTrack(), or a dead page-1 row re-points inside the span.
                val anchorConverted = anchor.relatedStreams.count { it.toTrack() != null }
                liveToken = anchor.nextpage
                // The anchor token resumes after the anchor's WHOLE converted span — never clamp to cache size
                // (rowsFor twin): a shorter cache cannot continue; the re-point guards refuse, next call re-anchors.
                liveResume = anchorConverted
                anchorLiveRaw = anchor.relatedStreams.size
            }
            if (anchor != null) openCacheVerifiedAt[key] = epochMillis()
        }
        while (rows.tracks.size < paging.offset + paging.limit && !rows.complete) {
            // Same recovery policy as the rowsFor twin: a NULL-token open cache means "re-anchor from page 1",
            // never resume at the interior index. Computed before the token check so the null arm is reachable.
            val resume = when {
                rows.nextpage == null -> 0
                rows.resumeIndex in 0..rows.tracks.size -> rows.resumeIndex
                else -> rows.tracks.size
            }
            val token = rows.nextpage
            if (token.isNullOrBlank()) {
            // A null-token open cache is deliberate (recovery arms drop unverified tails): use THIS call's live
            // chain instead of freezing complete=true, which would permanently truncate both readers.
            val live = liveToken
            if (live != null && rows.tracks.size >= liveResume) {
                if (liveRawResume == null) liveRawResume = anchorLiveRaw
                rows = rows.copy(nextpage = live, resumeIndex = liveResume)
                liveToken = null
                continue
            }
            // No live chain available: leave the cache open (never trust "null token" as EOF when complete was
            // never proven); the next call re-anchors and retries.
            break
            }
            // A null continuation is a FAILED fetch, NOT a proven end; a dead stored non-blank token must heal
            // onto this call's LIVE chain like the decoded-empty arm — breaking here stalls extension forever.
            val page = client.playlistNextPage(id, token) ?: run {
            val live = liveToken
            if (live != null && live != token && rows.tracks.size >= liveResume) {
                if (liveRawResume == null) liveRawResume = anchorLiveRaw
                rows = rows.copy(nextpage = live, resumeIndex = liveResume)
                liveToken = null
                continue
            }
            break
            }
            // Track numbers are RAW LIVE positions (with gaps): resume from the last cached row's number, or
            // the LIVE re-point's raw extent when healed; advance by each page's raw size to stay aligned.
            val baseIndex = if (isAlbum) liveRawResume ?: rows.tracks.lastOrNull()?.trackNumber ?: resume else resume
            var converted = page.relatedStreams.mapIndexedNotNull { index, item ->
            item.toTrack(album, if (isAlbum) baseIndex + index + 1 else null)
            }
            // Pre-drop size: the resumeIndex math counts the WHOLE fetched page (including the span that gets
            // deduped away), so this must be captured BEFORE `converted.drop(overlap)`.
            val convertedFetched = converted.size
            liveRawResume = (liveRawResume ?: rows.tracks.lastOrNull()?.trackNumber ?: resume) + page.relatedStreams.size
            // rowsFor()'s recovery merge can leave a token resuming INSIDE tracks: drop exactly that span
            // (same generation, verified); a changed playlist invalidates the cache — re-seed from page 1.
            val overlap = minOf(converted.size, rows.tracks.size - resume)
            if (overlap > 0) {
            var mismatch = false
            for (i in 0 until overlap) {
                if (converted[i].id != rows.tracks[resume + i].id) {
                    mismatch = true
                    break
                }
            }
            if (mismatch) {
                // The token chain is stale (out-of-band mutation): re-seed from a FRESH page-1 fetch — reusing
                // the cached page would continue the stale token and re-append old-generation rows.
                val fresh = client.playlist(id) ?: break
                // EMPTY fresh page-1 + blank token = the authoritative emptied shape: replace the stale span
                // with an OPEN empty cache (re-anchors next call). DECODED BLANK = throttle, never EOF — keep.
                if (fresh.relatedStreams.isEmpty() && fresh.nextpage == null) {
                    rows = CachedRows(emptyList(), null, complete = false, resumeIndex = 0)
                    break
                }
                // A DECODED-BLANK continuation token is the throttle shape — NEVER a verdict (replacing the
                // cache on it would truncate rows beyond page 1 on no evidence). Keep the old cache; re-anchor next visit.
                if (fresh.nextpage?.isBlank() == true) break
                store.cacheAlbumPlaylist(id, fresh)
                val freshConverted = fresh.relatedStreams.mapIndexedNotNull { index, item ->
                    item.toTrack(album, if (isAlbum) index + 1 else null)
                }
                // Same converted-row requirement as the anchor re-seed: an all-dead fresh page 1 stays OPEN —
                // complete=true would freeze it empty (later writers are gated on !rows.complete; TTL refuses all-dead).
                val freshProven = fresh.nextpage == null && freshConverted.isNotEmpty()
                rows = CachedRows(
                    tracks = freshConverted,
                    nextpage = fresh.nextpage,
                    complete = freshProven,
                    resumeIndex = freshConverted.size,
                    lastVerifiedAt = if (freshProven) epochMillis() else 0,
                )
                // The fresh chain restarts numbering at raw position 1: the next continuation must advance from
                // the FRESH page-1 extent — liveRawResume still holds the OLD chain's (stale base numbering).
                liveRawResume = fresh.relatedStreams.size
                // The fresh chain is a NEW generation: the ambiguous-page heal must re-point onto THIS chain or
                // not at all — never splice the old generation's continuation into the new prefix (live == rows.nextpage now).
                liveToken = fresh.nextpage
                liveResume = freshConverted.size
                continue
            }
            converted = converted.drop(overlap)
            }
            if (page.relatedStreams.isEmpty()) {
            // An empty continuation is ambiguous (EOF == throttle): break unmarked rather than freeze. Heal an
            // expired stored token once via this call's LIVE chain — the overlap machinery dedupes that span.
            val live = liveToken
            if (live != null && live != token && rows.tracks.size >= liveResume) {
                rows = rows.copy(nextpage = live, resumeIndex = liveResume)
                liveToken = null
                continue
            }
            break
            }
            // A continuation ending INSIDE the span proves the tail is gone: truncate (keepPrefix parity); only
            // NULL is a proven end — DECODED BLANK/all-dead pages append, healing the token onto the LIVE chain.
            if (page.nextpage == null && page.relatedStreams.isNotEmpty() && convertedFetched == 0) {
                val live = liveToken
                if (live != null && live != token && rows.tracks.size >= liveResume) {
                    rows = rows.copy(nextpage = live, resumeIndex = liveResume)
                    liveToken = null
                    continue
                }
                // The extension loop only runs on a live stored token, so rows.nextpage is guaranteed non-null
                // here (the compiler proves it): drop it so the next visit re-anchors.
                rows = rows.copy(nextpage = null)
                break
            }
            val keepPrefix = if (page.nextpage == null) resume + overlap else rows.tracks.size
            rows = rows.copy(
            tracks = rows.tracks.take(keepPrefix) + converted,
            nextpage = page.nextpage,
            complete = page.nextpage == null,
            // An EOF-proven completion stamps the TTL re-verify base (0 would
            // trigger an immediate complete-cache re-walk next call).
            lastVerifiedAt = if (page.nextpage == null) epochMillis() else rows.lastVerifiedAt,
            resumeIndex = if (page.nextpage == null) keepPrefix
            else if (convertedFetched <= overlap) resume + convertedFetched else rows.tracks.size + convertedFetched - overlap,
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
            // Degrade-not-throw like the blank fallback: GETs throw on non-2xx and a first visit has no cached
            // rows — an unwrapped throw would hard-fail the screens. Treat it exactly like a null fetch.
            val page = runCatching { client.playlist(id) }.getOrNull()
                ?: return CachedRows(emptyList(), null, false, 0)
            // Empty page-1 + blank token = throttled shape, NOT a proven EOF: neither cache raw nor complete —
            // the open empty cache re-anchors and heals next visit instead of freezing an empty album forever.
            if (page.relatedStreams.isEmpty() && page.nextpage.isNullOrBlank()) {
            return CachedRows(emptyList(), null, false, 0)
            }
            store.cacheAlbumPlaylist(id, page)
            page
        }
        val converted = cached.relatedStreams.mapIndexedNotNull { index, item ->
            // Same convention as the anchor re-seed / verify / extension writers: albums number RAW page
            // positions (with gaps), public playlists carry no trackNumber at all (album == null).
            item.toTrack(album, if (album != null) index + 1 else null)
        }
        // A page-1 END also requires CONVERTED rows: an all-dead page-1 proves nothing — complete/TTL base
        // would freeze it empty (all paths are gated on !rows.complete). Keep it OPEN, re-anchor next visit.
        val provenEnd = cached.nextpage == null && cached.relatedStreams.isNotEmpty() && converted.isNotEmpty()
        return CachedRows(
            tracks = converted,
            nextpage = cached.nextpage,
            complete = provenEnd,
            resumeIndex = converted.size,
            lastVerifiedAt = if (provenEnd) epochMillis() else 0,
        )
    }
}
