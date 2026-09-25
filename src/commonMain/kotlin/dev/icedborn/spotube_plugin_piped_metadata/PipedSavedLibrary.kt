package dev.icedborn.spotube_plugin_piped_metadata

import kotlin.coroutines.cancellation.CancellationException

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How often the account cache is refreshed while the app is used. */
internal const val ACCOUNT_REFRESH_TTL_MS = 15 * 60_000L
private const val ACCOUNT_PAGE_LIMIT = 6
/** Exact re-verification budget for a lazy cache span (rows); larger caches rely on the first-page anchor + refresh. */
// Shared by both cache-extender twins (Store.kt PlaylistRows.page() / rowsFor()): a divergence here
// silently splits how the same persisted CachedRows keyspace heals between the two readers.
internal const val REVERIFY_LIMIT = 300
/** Open row caches skip the page-1 anchor + span verify for this long after one ran, so scrolling costs one fetch per page. */
internal const val OPEN_CACHE_VERIFY_WINDOW_MS = 2 * 60_000L
/** Resolution attempts per video before it is permanently marked unresolvable ('none'). */
private const val RESOLUTION_RETRY_LIMIT = 3
// How long an exhaustion latch stays before the video is retried: a transient-outage latch must not be
// permanent data loss — after the cooldown the row re-attempts, re-latching only after RESOLUTION_RETRY_LIMIT fails.
private const val RESOLUTION_RETRY_COOLDOWN_MS = 3_600_000L
private const val ACCOUNT_RESOLVE_LIMIT = 40
private const val ACCOUNT_DERIVED_PREFIX = "piped-acct-"

/** The three playlists saved items are written through to (mirrors the brainz plugin pattern). */
internal enum class SavedKind(val playlistName: String) {
    ALBUM("Spotube - Albums"),
    ARTIST("Spotube - Artists"),
    TRACK("Spotube - Favorites"),
}

/** Account cache + write-through. Reads serve the local snapshot (refreshed online); saves write through when online,
 * and the local library is the source of truth — no mirror to converge. */
internal class PipedSavedLibrary(
    private val httpClient: HttpClientAPI,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val albumLookup: AlbumLookup,
    private val instanceSource: InstanceSource,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    private val refreshMutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun playlistKey(kind: SavedKind) = "saved.playlist:${kind.name.lowercase()}"
    private fun repKey(kind: SavedKind, entityId: String) = "saved.rep:${kind.name.lowercase()}:$entityId"

    /** The mirror row video bound to [entityId], including the pre-upgrade fallback for synthetic artist ids stored
     * under the raw uploader name ("channel:X - Topic") until a refresh migrates them. */
    private suspend fun repVideoOf(kind: SavedKind, entityId: String): String? {
        store.get(repKey(kind, entityId))?.jsonPrimitive?.contentOrNull?.let { return it }
        if (kind == SavedKind.ARTIST && entityId.startsWith("channel:")) {
            store.get(repKey(kind, entityId + " - Topic"))?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return null
    }

    /** Mirror playlist uuid, bound to the account identity that owns it (instance + username). */
    @Serializable
    private data class StoredPlaylistId(
        val instance: String,
        val username: String,
        val playlistId: String,
    )

    // ── cache reads ────────────────────────────────────────────────────────────

    suspend fun cachedState(): AccountCacheState? {
        val state = store.cachedAccountState() ?: return null
        // Pre-identity (legacy) snapshots decode with blank instance/username. Logged out they can never be
        // re-bound (every refresh needs a session), so keep serving them fail-soft; new snapshots stay strict.
        val legacy = state.instance.isBlank() && state.username.isBlank()
        val session = sessionProvider()
        if (session != null) {
            // Logged in: pre-identity snapshots fail-soft (rejecting them would blank every account read until the
            // startup refresh re-stamps); the WRITE side stays locked until then — no foreign destroy (round-101).
            if (legacy) return state
            val sameInstance = state.instance.trim().trimEnd('/') == session.instance.trim().trimEnd('/')
            val sameUser = state.username == session.username
            return if (sameInstance && sameUser) state else null
        }
        // Logged out: serve the last snapshot only for the instance it was captured from (switches must not surface
        // the old account). Pre-identity keeps its semantics: no refresh can re-stamp it logged out.
        val configured = instanceSource.api() ?: return null
        if (legacy) return state
        return if (state.instance.trim().trimEnd('/') == configured.trim().trimEnd('/')) state else null
    }

    suspend fun accountPlaylist(id: String): CachedAccountPlaylist? =
        cachedState()?.playlists?.firstOrNull { it.id == id }

    private suspend fun stateOrEmpty(): AccountCacheState = cachedState() ?: AccountCacheState()

    suspend fun allSavedTrackIds(): List<String> =
        (library.savedTracks() + stateOrEmpty().savedTracks).distinct()

    suspend fun allSavedAlbumIds(): List<String> =
        (library.savedAlbums() + stateOrEmpty().savedAlbums).distinct()

    suspend fun allSavedArtistIds(): List<String> =
        (library.savedArtists().map(::canonicalArtistId) + stateOrEmpty().savedArtists.map(::canonicalArtistId)).distinct()

    private suspend fun allSavedIds(kind: SavedKind): List<String> = when (kind) {
        SavedKind.TRACK -> (library.savedTracks() + stateOrEmpty().savedTracks).distinct()
        SavedKind.ALBUM -> (library.savedAlbums() + stateOrEmpty().savedAlbums).distinct()
        SavedKind.ARTIST -> (library.savedArtists() + stateOrEmpty().savedArtists).map(::canonicalArtistId).distinct()
    }

    suspend fun isSavedTracks(ids: List<String>): List<Boolean> {
        val local = library.savedTracks().toHashSet()
        val cached = stateOrEmpty().savedTracks.toHashSet()
        return ids.map { it in local || it in cached }
    }

    suspend fun isSavedAlbums(ids: List<String>): List<Boolean> {
        val local = library.savedAlbums().toHashSet()
        val cached = stateOrEmpty().savedAlbums.toHashSet()
        return ids.map { it in local || it in cached }
    }

    suspend fun isSavedArtists(ids: List<String>): List<Boolean> {
        val local = library.savedArtists().map(::canonicalArtistId).toHashSet()
        val cached = stateOrEmpty().savedArtists.map(::canonicalArtistId).toHashSet()
        return ids.map { canonicalArtistId(it) in local || canonicalArtistId(it) in cached }
    }

    // ── cache refresh ──────────────────────────────────────────────────────────

    /** Refresh when a session exists, the TTL elapsed, and no refresh is running. Fail-soft. With a snapshot to
     * serve, the refresh runs in the background so library tabs do not wait for a full account walk. */
    suspend fun maybeRefresh() {
        if (sessionProvider() == null) return
        if (!needsRefresh()) return
        if (cachedState() != null) {
            if (!refreshMutex.isLocked) refreshScope.launch { refreshIfStale() }
            return
        }
        refreshIfStale()
    }

    private suspend fun refreshIfStale() {
        refreshMutex.withLock {
            if (!needsRefresh()) return@withLock
            runCatching { doRefresh() }
        }
    }

    /** Force refresh (login / plugin start). Fail-soft: keep the previous cache on error. */
    suspend fun refreshCache() {
        val account = sessionProvider() ?: return
        refreshMutex.withLock {
            runCatching { doRefresh(account) }
        }
    }

    private suspend fun needsRefresh(): Boolean {
        val state = cachedState() ?: return true
        return epochMillis() - state.refreshedAt >= ACCOUNT_REFRESH_TTL_MS
    }

    private suspend fun doRefresh() {
        val account = sessionProvider() ?: return
        runCatching { doRefresh(account) }
    }

    private suspend fun doRefresh(account: PipedAccount) {
        val listing = userGet(account, "/user/playlists") ?: return
        val entries = runCatching { json.parseToJsonElement(listing).jsonArray.map { it.jsonObject } }
            .getOrNull() ?: return

        // Rebuild baseline: accept an identity-stamped-for-THIS-account or pre-identity snapshot (this device's
        // only pre-upgrade one); a DIFFERENT account's stamp stays rejected — never bind another account's ids.
        val previousState = store.cachedAccountState()
        val previous = when {
            previousState == null -> null
            previousState.instance.isBlank() && previousState.username.isBlank() -> previousState
            previousState.instance.trim().trimEnd('/') == account.instance.trim().trimEnd('/') &&
                previousState.username == account.username -> previousState
            else -> null
        }
        // Stamp every entity REBOUND from the mirror this refresh: pre-upgrade saves (row + repKey, no stamp)
        // would become unremovable once their id leaves the identity snapshot — only the stamp survives rewrites.
        val stamp = "${account.instance.trim().trimEnd('/')}|${account.username}"
        val playlists = mutableListOf<CachedAccountPlaylist>()
        val repPages = mutableMapOf<SavedKind, List<MetadataTrack>>()
        // Per-kind walk end-state: proven-end (null token, rows on the final page) / ambiguous (blank token, empty
        // final page) / no mirror. Ambiguous ends keep the PREVIOUS snapshot's set — a partial view must not truncate.
        val mirrorState = mutableMapOf<SavedKind, Boolean?>()
        var resolutions = 0

        // Pass 1: uuid-exact attribution (owned StoredPlaylistId or a pre-upgrade bare-uuid binding). The
        // BOUND playlist is the mirror even when another carries the canonical name — double-walking splits them.
        val kindByUuid = mutableMapOf<String, SavedKind>()
        entries.forEach { obj ->
            val uuid = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            for (k in SavedKind.entries) {
                if (k in kindByUuid.values) continue
                val key = playlistKey(k)
                val raw = store.get(key)
                val owned = raw?.let { runCatching { json.decodeFromJsonElement<StoredPlaylistId>(it) }.getOrNull() }
                val legacy = raw as? JsonPrimitive
                if ((owned != null && owned.instance.trim().trimEnd('/') == account.instance.trim().trimEnd('/') &&
                        owned.username == account.username && owned.playlistId == uuid) ||
                    (owned == null && legacy?.isString == true && legacy.content == uuid)
                ) {
                    kindByUuid[uuid] = k
                    return@forEach
                }
            }
        }
        // Walk every account playlist so its rows are cached for offline reads.
        entries.forEach { obj ->
            val uuid = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val count = obj["videos"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            val kind = kindByUuid[uuid] ?: SavedKind.entries.firstOrNull { k ->
                // The same-name rescue applies only when the kind owns NO live binding (also ensurePlaylist's
                // rule): with a live binding the name-matched playlist is a DIFFERENT one, never displace the mirror.
                k.playlistName == name && k !in kindByUuid.values
            }
            // Mirror playlists are the source of the saved sets, so their rows must be complete; regular account
            // playlists keep the bounded walk and extend lazily through the stored nextpage token.
            val (_, rows, nextpage, lastPageRows, pagesRead, lastPageConverted) = walkPlaylist(
                account, uuid,
                pageLimit = if (kind != null) null else ACCOUNT_PAGE_LIMIT,
                fetch = { path -> userGet(account, path) },
            )
            if (kind != null) {
                repPages[kind] = rows
                // Mirror-end classification: NULL token + rows on the final page = PROVEN; decoded-empty is
                // trusted only when it ENDED at NULL (raw-empty page 1). BLANK/unreadable walks = ambiguous, previous kept.
                mirrorState[kind] = when {
                    // PROVEN only with CONVERTED rows: 'converted empty' as an end wiped every saved id once;
                    // only a single raw-empty page-1 + NULL token is the authoritative empty mirror (longer walks ambiguous).
                    nextpage == null && lastPageRows > 0 && rows.isNotEmpty() && lastPageConverted > 0 -> true
                    rows.isEmpty() && pagesRead == 0 -> false
                    // ... AND the NULL token: a decoded empty page behind a BLANK continuation is the throttled shape
                    // (walkComplete parity) — a blank-token walk would vote PROVEN here and wipe every saved id.
                    rows.isEmpty() && lastPageRows == 0 && pagesRead == 1 && nextpage == null -> true
                    else -> false
                }
                // Bind the mirror playlist uuid to this account, so an account or instance switch re-resolves against
                // the new account instead of reusing the previous account's mirror playlist.
                store.put(playlistKey(kind), json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, uuid)))
                // The mirror playlists themselves stay hidden; the saved sets below
                // are what surface in the app.
            } else {
                cacheRows(uuid, rows, nextpage, lastPageRows, pagesRead, lastPageConverted)
                playlists += CachedAccountPlaylist(uuid, name, count)
            }
        }

        // Rebind legacy bare-uuid mirror bindings whose playlist still exists on this account (uuid-exact, so a
        // different account's playlists stay unbound): pre-upgrade custom playlists keep syncing instead of no-oping.
        val entryUuids = entries.mapNotNull { obj -> obj["id"]?.jsonPrimitive?.contentOrNull }.toHashSet()
        library.storedPlaylists().forEach { record ->
            val raw = store.get("mirror.playlist:${record.id}")
            val legacy = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() && it in entryUuids } ?: return@forEach
            store.put("mirror.playlist:${record.id}", json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, legacy)))
        }

        val savedAlbums = mutableListOf<String>()
        val savedArtists = mutableListOf<String>()
        for (kind in listOf(SavedKind.ALBUM, SavedKind.ARTIST)) {
            val rows = repPages[kind] ?: continue
            // An AMBIGUOUS mirror end (blank token / empty final page) only saw a prefix: re-binding from those
            // partial rows would silently drop the tail entities' saved-set membership — keep the previous set.
            if (mirrorState[kind] == false) continue
            // Shared reps create DUPLICATE mirror rows (one per entity): each occurrence must bind to a
            // distinct known entity — a per-video firstOrNull drops the sibling from the rebuilt set every refresh.
            val seenReps = mutableMapOf<String, Int>()
            // Each saved set gets its own resolution budget (a large album mirror must not starve artists); the
            // HEAD bias used to leave tails permanently unmapped, blocking removals. Rotate the start across refreshes.
            val start = if (rows.size > ACCOUNT_RESOLVE_LIMIT)
                ((epochMillis() / 900_000L) * ACCOUNT_RESOLVE_LIMIT).toInt() % rows.size
            else 0
            resolutions = 0
            // EVERY row is visited every refresh: known/cached rows re-add WITHOUT budget so membership never
            // churns with the rotating window; the budget bounds only the EXPENSIVE fresh searches.
            for (k in 0 until rows.size) {
                val item = rows[(start + k) % rows.size]
                val videoId = item.id
                if (videoId.isEmpty()) continue
                when (kind) {
                    SavedKind.ALBUM -> {
                        // The entity that owns this row wins over the generic track→album mapping (which can
                        // name an already-removed entity). One candidate per row OCCURRENCE, so duplicate reps rebind their own.
                        val knownCandidates = previous?.savedAlbums
                            ?.filter { albumId -> store.get(repKey(kind, albumId))?.jsonPrimitive?.contentOrNull == videoId }
                            .orEmpty()
                        val occ = seenReps[videoId] ?: 0
                        seenReps[videoId] = occ + 1
                        val known = knownCandidates.getOrNull(occ)
                        if (known != null) {
                            addOwnerStamp(kind, known, stamp)
                            savedAlbums += known
                            continue
                        }
                        val cachedAlbum = store.cachedTrackAlbum(videoId)
                        if (cachedAlbum != null) {
                            // A cached verdict (album id or 'none') is FINAL: SKIP resolution, but ownership never
                            // comes from the verdict alone — a stale verdict on a shared rep row resurrects a removed album.
                            if (cachedAlbum != "none" &&
                                cachedAlbum in (previous?.savedAlbums.orEmpty())
                            ) {
                                store.put(repKey(kind, cachedAlbum), JsonPrimitive(videoId))
                                addOwnerStamp(kind, cachedAlbum, stamp)
                                savedAlbums += cachedAlbum
                            }
                            continue
                        }
                        // Kind-specific exhaustion latch: budget exhausted ALBUM resolution only — a cached 'none' says
                        // nothing about ARTIST resolution of the same video, and an artist exhaustion must not poison here.
                        if (albumUnresolvable(account, videoId)) continue
                        // The fresh search/walk chain is the only budget consumer (known/cached rows re-add without it);
                        // the unit is a REQUEST — one 'none' verdict can consume ~100 units.
                        if (resolutions >= ACCOUNT_RESOLVE_LIMIT) continue
                        try {
                            val resolved = albumLookup.resolveAlbumForVideo(videoId)
                            // Every DEFINITE outcome consumes the budget ('none' runs the same rewritten chain;
                            // unit = REQUEST) or the not-found population blows one refresh into thousands of requests.
                            resolutions += resolved.requests
                            // Any definite outcome clears the failure counter (latch = FAILURES IN A ROW): a stale
                            // counter + one blip would latch a resolving video; survivorBlocks() then resurrects it later.
                            store.remove("resolutionRetry:album:${latchScope(account)}:$videoId")
                            val albumId = resolved.albumId
                            if (albumId != null) {
                                store.cacheTrackAlbum(videoId, albumId)
                                store.put(repKey(kind, albumId), JsonPrimitive(videoId))
                                addOwnerStamp(kind, albumId, stamp)
                                savedAlbums += albumId
                            } else {
                                // Genuine "not an album" verdict (searches ran):
                                // cache so it is not re-resolved every refresh.
                                store.cacheTrackAlbum(videoId, "none")
                            }
                        } catch (t: Throwable) {
                            // A cancelled refresh is not a resolution failure:
                            // propagate so the latch/counters stay untouched.
                            if (t is CancellationException) throw t
                            // Transient failure: no verdict — retry next refresh. REPEATED failures latch (no budget
                            // burn, no removal blocking); time-boxed, so a merely-long outage recovers.
                            val retryKey = "resolutionRetry:album:${latchScope(account)}:$videoId"
                            val attempts = (store.get(retryKey)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1
                            if (attempts >= RESOLUTION_RETRY_LIMIT) {
                                store.put("unresolvable:album:${latchScope(account)}:$videoId", JsonPrimitive(epochMillis()))
                                store.remove(retryKey)
                            } else {
                                store.put(retryKey, JsonPrimitive(attempts))
                            }
                        }
                    }
                    SavedKind.ARTIST -> {
                        // Migrate legacy synthetic ids (raw uploader names) to the canonical form, moving their
                        // repKey along so remove() finds today's id. Same occurrence-aware consumption as the album arm.
                        val knownCandidates = previous?.savedArtists
                            ?.filter { artistId -> store.get(repKey(kind, artistId))?.jsonPrimitive?.contentOrNull == videoId }
                            .orEmpty()
                        val occ = seenReps[videoId] ?: 0
                        seenReps[videoId] = occ + 1
                        val knownLegacy = knownCandidates.getOrNull(occ)
                        val known = knownLegacy?.let(::canonicalArtistId)
                        // Kind-specific exhaustion latch — NEVER the album 'none' verdict (a channel rep may still
                        // resolve); the album exhaustion must not starve artist resolution. Only the attempt is skipped.
                        if (known == null && artistUnresolvable(account, videoId)) continue
                        // The fresh channel lookup is the only budget consumer
                        // (known rows re-add without it).
                        if (known == null && resolutions >= ACCOUNT_RESOLVE_LIMIT) continue
                        val channelId = known ?: run {
                            val c = artistChannelOf(account, videoId)
                            if (c != null) {
                                store.put(repKey(kind, c), JsonPrimitive(videoId))
                                resolutions++
                                c
                            } else {
                                // ARTIST-specific exhaustion counter: a never-resolving video latches after enough
                                // attempts — no budget burn, no removal blocking — without poisoning the ALBUM arm. Time-boxed.
                                val retryKey = "resolutionRetry:artist:${latchScope(account)}:$videoId"
                                val attempts = (store.get(retryKey)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1
                                if (attempts >= RESOLUTION_RETRY_LIMIT) {
                                    store.put("unresolvable:artist:${latchScope(account)}:$videoId", JsonPrimitive(epochMillis()))
                                    store.remove(retryKey)
                                } else {
                                    store.put(retryKey, JsonPrimitive(attempts))
                                }
                                null
                            }
                        }
                        if (channelId != null) {
                            // Resolution succeeded (known or freshly found): clear the artist failure counter so the
                            // latch only fires on CONSECUTIVE failures.
                            store.remove("resolutionRetry:artist:${latchScope(account)}:$videoId")
                            val canonical = canonicalArtistId(channelId)
                            addOwnerStamp(kind, canonical, stamp)
                            if (known != null) {
                                store.put(repKey(kind, known), JsonPrimitive(videoId))
                                if (knownLegacy != known) store.remove(repKey(kind, knownLegacy))
                            }
                            savedArtists += channelId
                        }
                    }
                    SavedKind.TRACK -> Unit
                }
            }
        }
        // Every id in a saved set needs a repKey so remove() can find it in the
        // mirror playlist; save() writes it, so do so here for synced rows too.
        repPages[SavedKind.TRACK].orEmpty().forEach {
            store.put(repKey(SavedKind.TRACK, it.id), JsonPrimitive(it.id))
            addOwnerStamp(SavedKind.TRACK, it.id, stamp)
        }
        val savedTracks = repPages[SavedKind.TRACK].orEmpty().map { it.id }.distinct()

        // One-time migration: rewrite legacy synthetic artist ids (raw uploader names, "channel:X - Topic") to the
        // canonical cleaned form so the saved-artists page stops showing one artist under two ids.
        val libraryArtists = library.savedArtists()
        val canonicalArtists = libraryArtists.map(::canonicalArtistId).distinct()
        if (canonicalArtists != libraryArtists) {
            library.removeArtists(libraryArtists)
            library.saveArtists(canonicalArtists)
        }

        store.cacheAccountState(
            AccountCacheState(
                playlists = playlists,
                // Ambiguously-ended mirror walks keep the previous snapshot's set (a partial rebuild must not
                // truncate it); proven or absent mirrors use the just-rebuilt sets as before.
                savedTracks = if (mirrorState[SavedKind.TRACK] == false) previous?.savedTracks.orEmpty() else savedTracks,
                savedAlbums = if (mirrorState[SavedKind.ALBUM] == false) previous?.savedAlbums.orEmpty() else savedAlbums.distinct(),
                savedArtists = if (mirrorState[SavedKind.ARTIST] == false) previous?.savedArtists.orEmpty() else savedArtists.distinct(),
                refreshedAt = epochMillis(),
                instance = account.instance,
                username = account.username,
            ),
        )

        // The old mirror pulled account playlists into the local library as
        // "piped-acct-*" records; they are served from the cache now.
        library.storedPlaylists().filter { it.id.startsWith(ACCOUNT_DERIVED_PREFIX) }.forEach { record ->
            library.deleteStoredPlaylist(record.id)
            library.removePlaylists(listOf(record.id))
        }
    }

    /** Walks an account playlist page by page until the end, or until [pageLimit] pages are read when a limit is
     * given. Feeds each page and its leading row offset to [onPage]; returns the trailing nextpage token (null = end). */
    private suspend fun walkPlaylistPages(
        account: PipedAccount,
        playlistId: String,
        pageLimit: Int? = ACCOUNT_PAGE_LIMIT,
        startToken: String? = null,
        stopAfterRows: Int = Int.MAX_VALUE,
        fetch: suspend (path: String) -> String? = { path -> sessionedGet(account.instance, path) },
        // The callback answers "keep walking": a caller whose budget counts CONVERTED rows (dead/odd urls never
        // cached) can stop right after the page covering its span instead of being cut by the raw stopAfterRows bound.
        onPage: suspend (page: PipedPlaylistPage, seen: Int) -> Boolean,
    ): String? {
        if (startToken != null && startToken.isBlank()) return null
        var nextpage: String? = startToken
        var seen = 0
        var pages = 0
        do {
            val path = if (nextpage.isNullOrBlank()) "/playlists/${playlistId.percentEncoded()}"
            else "/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"
            val body = fetch(path) ?: break
            // Same throttle guard as PipedClient.playlist(): a '{}'/'{"error":…}' 200 must NOT read as a
            // proven-empty page — the decoder nulls key-missing shapes, so pagesRead stays 0 -> AMBIGUOUS, previous kept.
            val page = runCatching { decodePlaylistPage(body) }.getOrNull() ?: break
            val keepWalking = onPage(page, seen)
            seen += page.relatedStreams.size
            nextpage = page.nextpage
            pages++
            if (!keepWalking) break
            // A DECODED blank continuation token ends this walk but is NOT a trustworthy EOF (throttled shape):
            // callers' proven-end gates are `nextpage == null`, so the blank stops without completing or confirming.
            if (nextpage != null && nextpage.isBlank()) break
        } while (nextpage != null && (pageLimit == null || pages < pageLimit) && seen < stopAfterRows)
        return nextpage
    }

    /** Walk of an account playlist: ids, cached rows, and the trailing nextpage token (null = end reached, for
     * cache continuation). */
    private data class WalkResult(
        val ids: List<String>,
        val rows: List<MetadataTrack>,
        val nextpage: String?,
        val lastPageRows: Int,
        // Pages actually READ (decoded): 0 = could not fetch a first page at all (per-playlist failure) — not
        // evidence of an empty playlist, unlike a decoded page with zero rows.
        val pages: Int,
        // The FINAL page's CONVERTED row count: an all-dead final page (rows toTrack/videoIdOf drop) proves
        // nothing about the end — the strict per-final-page guard the completion writers apply.
        val lastPageConverted: Int,
    )

    private suspend fun walkPlaylist(
        account: PipedAccount,
        playlistId: String,
        pageLimit: Int? = ACCOUNT_PAGE_LIMIT,
        // Walks of THIS account's own mirrors authenticate: some instances require a token for private playlists
        // (standard instances ignore the header, so this is a strict superset of access).
        fetch: suspend (path: String) -> String? = { path -> sessionedGet(account.instance, path) },
    ): WalkResult {
        val ids = mutableListOf<String>()
        val rows = mutableListOf<MetadataTrack>()
        var lastPageRows = 0
        var lastPageConverted = 0
        var pagesRead = 0
        val nextpage = walkPlaylistPages(account, playlistId, pageLimit = pageLimit, fetch = fetch) { page, _ ->
            pagesRead++
            lastPageRows = page.relatedStreams.size
            lastPageConverted = 0
            page.relatedStreams.forEach { item ->
                videoIdOf(item.url).takeIf { it.isNotEmpty() }?.let { ids += it }
                item.toTrack()?.let { track ->
                    rows += track
                    lastPageConverted++
                    store.rememberTrack(track)
                }
            }
            true
        }
        return WalkResult(ids, rows, nextpage, lastPageRows, pagesRead, lastPageConverted)
    }

    private suspend fun cacheRows(uuid: String, rows: List<MetadataTrack>, nextpage: String?, lastPageRows: Int, pagesRead: Int, lastPageConverted: Int) {
        // complete=true only on a PROVEN EOF (rows on the final page, no continuation); empty/aborted walks
        // and decoded-empty final pages stay OPEN — freezing a truncated span hides tail rows forever (re-anchor heals).
        val key = PLAYLIST_ROWS_PREFIX + uuid
        val existing = runCatching {
            json.decodeFromJsonElement<CachedRows>(store.get(key) ?: return@runCatching null)
        }.getOrNull()
        // complete also requires the FINAL page's CONVERTED rows: an all-dead final page proves nothing about
        // the end (mirrorState parity), and freezing it complete would render an account playlist empty with no re-walk.
        val complete = nextpage == null && lastPageRows > 0 && rows.isNotEmpty() && lastPageConverted > 0
        val tracks: List<MetadataTrack>
        val mergedComplete: Boolean
        when {
            // THIS walk PROVED EOF: authoritative — a shorter/emptied
            // playlist's cached ghosts must not survive it.
            complete -> { tracks = rows; mergedComplete = true }
            // Trustworthy-empty page 1 (one decoded page, zero rows, NULL token): wipe open-empty so the stale
            // span stops serving. DECODED BLANK = throttled shape, never a proven end (round-102).
            rows.isEmpty() && lastPageRows == 0 && pagesRead == 1 && nextpage == null -> {
                tracks = rows; mergedComplete = false
            }
            // A bounded walk with NO rows (ambiguous empty / all-dead / blank continuation): overwriting the
            // cached record with emptiness on unproven evidence would truncate offline depth — keep it; re-anchor next visit.
            rows.isEmpty() && existing != null -> return
            // The bounded prefix is still CURRENT: keep the same-generation tail (browsed rows), but NOT the
            // proven-EOF stamp — this walk proved nothing beyond its prefix and account caches have no TTL re-verify.
            existing != null && existing.tracks.take(rows.size).map { it.id } == rows.map { it.id } -> {
                tracks = rows + existing.tracks.drop(rows.size)
                mergedComplete = false
            }
            // Changed prefix (or first visit): the fresh bounded walk is the
            // new generation — write it open; the next rowsFor call extends.
            else -> { tracks = rows; mergedComplete = false }
        }
        // EOF proven by this walk: stamp so the TTL arm does not re-verify on the next read. resumeIndex =
        // the FRESH walk's converted end, NOT the merged end — the token resumes INSIDE tracks (overlap dedupes).
        val record = CachedRows(tracks = tracks, nextpage = nextpage, complete = mergedComplete, resumeIndex = rows.size, lastVerifiedAt = if (mergedComplete) epochMillis() else 0)
        store.put(key, json.encodeToJsonElement(record))
    }

    /** Rows of an account playlist: cached copy when present, else a live bounded walk cached for later (and
     * offline use). When [minRows] asks beyond the cache, continues through the stored nextpage token. */
    suspend fun rowsFor(uuid: String, minRows: Int = 0, healDepth: Int = 0): CachedRows {
        val key = PLAYLIST_ROWS_PREFIX + uuid
        val cached = store.getDecoded(key, CachedRows.serializer())
        // Non-null by construction so the extension loop below (which REBINDS
        // rows to recovery/heal results) never loses the smart cast.
        var rows: CachedRows = cached ?: run {
            val account = sessionProvider() ?: return CachedRows()
            val (_, tracks, nextpage, lastPageRows, _, lastPageConverted) = walkPlaylist(account, uuid, fetch = { path -> userGet(account, path) })
            // complete=true only on a proven EOF; aborted/throttled walks and DECODED EMPTY final pages stay
            // OPEN (tail may still exist). Also requires CONVERTED final-page rows — all-dead isn't proven (no TTL arm).
            val seededComplete = nextpage == null && lastPageRows > 0 && tracks.isNotEmpty() && lastPageConverted > 0
            val seeded = CachedRows(tracks = tracks, nextpage = nextpage, complete = seededComplete, resumeIndex = tracks.size, lastVerifiedAt = if (seededComplete) epochMillis() else 0)
            store.put(key, json.encodeToJsonElement(seeded))
            seeded
        }
        // Re-verification ALSO opens for IN-SPAN windows on over-budget caches (Store.page() twin): shrunken
        // playlists leave ghost rows and account caches have no TTL arm — IN-SPAN reads run anchor + window verify.
        // A token-less open cache still needs the anchor, which is its only live chain.
        val recentlyVerified = !rows.nextpage.isNullOrBlank() && openCacheRecentlyVerified(key)
        if (!rows.complete && (rows.tracks.size < minRows || (rows.tracks.size > REVERIFY_LIMIT && !recentlyVerified))) {
            // Recovery arms rebuild to the OLD span in one pass; the caller's minRows may still exceed it.
            // Re-enter when the rebuilt cache carries a LIVE chain (bounded; looping instances must not refetch forever).
            var recoveries = 0
            // The page-1 anchor + exact-span verify run ONCE per call (dead/odd rows advance the raw token
            // while the CONVERTED size stays flat; re-running every iteration multiplies the per-scroll cost).
            var anchoredVerify: Boolean = recentlyVerified
            // IN-SPAN pass: an over-budget open cache whose window lies INSIDE the span fails the size gate,
            // but the anchor + WINDOW-capped verify must still run — else every scroll serves the stale span unverified.
            var inSpanPass = rows.tracks.size >= minRows
            while ((rows.tracks.size < minRows || inSpanPass) && !rows.complete) {
            val account = sessionProvider() ?: return rows
            // Consume the in-span flag on the FIRST iteration (anchor + window verify ARE the pass; the
            // extension walk is stopAfterRows == 0). A verify replacement that re-enters has consumed it and extends normally.
            val inSpanThisPass = inSpanPass
            inSpanPass = false
            // A LIVE continuation chain discovered this call: a dead STORED token stalls every future extension,
            // so the ambiguous-empty guard below re-points at this chain once (Store.page() heals the same shape).
            var verifyLiveToken: String? = null
            var verifyLiveResume = 0
            // Where the stored token resumes: the cache end (normal), the fresh-prefix end INSIDE tracks
            // (recovery-merged), or 0 (token-less legacy cache).
            val resume = when {
                rows.nextpage == null -> 0
                rows.resumeIndex in 0..rows.tracks.size -> rows.resumeIndex
                else -> rows.tracks.size
            }
            val extra = mutableListOf<MetadataTrack>()
            var lastPageRows = 0
            var lastPageConverted = 0
            var lastFreshRows = 0
            var lastFreshConverted = 0
            // Bounded re-walk of the first ACCOUNT_PAGE_LIMIT pages; null when the
            // instance served nothing (offline: keep the cached rows untouched).
            suspend fun boundedFresh(): Triple<Int, List<MetadataTrack>, String?>? {
                var pagesRead = 0
                val freshTracks = mutableListOf<MetadataTrack>()
                val freshToken = walkPlaylistPages(account, uuid, pageLimit = ACCOUNT_PAGE_LIMIT, fetch = { path -> userGet(account, path) }) { page, _ ->
                    pagesRead++
                    lastFreshRows = page.relatedStreams.size
                    lastFreshConverted = 0
                    page.relatedStreams.forEach { item ->
                        item.toTrack()?.let { track -> freshTracks += track; store.rememberTrack(track); lastFreshConverted++ }
                    }
                    true
                }
                return if (pagesRead == 0) null else Triple(pagesRead, freshTracks, freshToken)
            }

            // The stored token failed (instance restart, expiry, offline):
            // re-walk from page one to refresh the token.
            suspend fun recoverFromPageOne(): CachedRows {
                val fresh = boundedFresh() ?: return rows
                val (freshPages, freshTracks, freshNext) = fresh
                if (freshNext == null && lastFreshRows == 0) {
                    // A walk that READ page 1 and decoded ZERO rows with a null token is AUTHORITATIVE-EMPTY
                    // (emptied on the web): the stale span stops serving — open-empty (complete=false), re-anchor next visit.
                    if (freshPages == 1) {
                        val emptyR = CachedRows(emptyList(), null, complete = false, resumeIndex = 0)
                        store.put(key, json.encodeToJsonElement(emptyR))
                        return emptyR
                    }
                    // Page 1 READ rows then an EMPTY continuation page ended the walk (pagesRead > 1): an ambiguous
                    // mid-walk empty, never a verdict — never truncate the working cache; keep open, next call re-derives.
                    return rows
                }
                if (freshNext == null && lastFreshRows > 0 && lastFreshConverted == 0) {
                    // An all-dead fresh re-walk (ZERO convertible items) proves nothing: the fresh-alone arm would
                    // write complete=true and wipe the healthy cache. Keep the stale-but-real cache; retry next extension.
                    return rows
                }
                // Mixing a stale tail behind a fresh prefix is safe only for the SAME generation (any insert/delete
                // before the fetched span shifts every later row): prove it by comparing the re-walked prefix row-for-row.
                val prefixMatches = freshTracks.map { it.id } == rows.tracks.take(freshTracks.size).map { it.id }
                val rebuilt = if (freshNext == null || freshTracks.size >= rows.tracks.size || !prefixMatches) {
                    // The re-walk reached the end, covers the old cache's rows, or the playlist changed: the
                    // fresh prefix alone is authoritative — a changed generation must not splice onto stale rows.
                    CachedRows(tracks = freshTracks, nextpage = freshNext, complete = freshNext == null, resumeIndex = freshTracks.size, lastVerifiedAt = if (freshNext == null) epochMillis() else 0)
                } else {
                    // The instance is reachable (this re-walk succeeded): continue from the fresh token LIVE
                    // to replace the unverified old tail; keep it only on a mid-walk failure (interior heal marker).
                    val liveTail = mutableListOf<MetadataTrack>()
                    var lastTailRows = 0
                    var lastTailConverted = 0
                    val tailToken = walkPlaylistPages(
                        account, uuid,
                        pageLimit = null,
                        fetch = { path -> userGet(account, path) },
                        startToken = freshNext,
                    ) { page, _ ->
                        lastTailRows = page.relatedStreams.size
                        lastTailConverted = 0
                        page.relatedStreams.forEach { item ->
                            item.toTrack()?.let { track -> liveTail += track; store.rememberTrack(track); lastTailConverted++ }
                        }
                        // Stop on the CONVERTED target, like the exact-span verify: a RAW stopAfterRows budget trips
                        // one page early over dead/odd rows, persisting a short tail that re-runs the whole recovery each time.
                        liveTail.size < rows.tracks.size - freshTracks.size
                    }
                    if (tailToken.isNullOrBlank() && liveTail.isEmpty()) {
                        // An EMPTY tail continuation + null token is a transient quirk (the fresh prefix walk
                        // just proved the instance serves data): keep the tail for offline display, interior-marked.
                        CachedRows(
                            tracks = freshTracks + rows.tracks.drop(freshTracks.size),
                            nextpage = freshNext,
                            complete = false,
                            resumeIndex = freshTracks.size,
                        )
                    } else if (liveTail.isNotEmpty() || tailToken == null || tailToken.isBlank()) {
                        // A live tail ending on an EMPTY page + null token is the same quirk, not a proven end: keep
                        // the cache open (complete=false, no token) so the next call re-anchors instead of freezing short.
                        CachedRows(
                            tracks = freshTracks + liveTail,
                            nextpage = tailToken,
                            complete = tailToken == null && lastTailRows > 0 && lastTailConverted > 0,
                            resumeIndex = freshTracks.size + liveTail.size,
                            lastVerifiedAt = if (tailToken == null && lastTailRows > 0 && lastTailConverted > 0) epochMillis() else 0,
                        )
                    } else {
                        // Continuation unavailable: keep the old tail at its positions (offline display); the interior
                        // token marks it for the next successful extension's mismatch heal.
                        CachedRows(
                            tracks = freshTracks + rows.tracks.drop(freshTracks.size),
                            nextpage = freshNext,
                            complete = false,
                            resumeIndex = freshTracks.size,
                        )
                    }
                }
                store.put(key, json.encodeToJsonElement(rebuilt))
                return rebuilt
            }
            if (!anchoredVerify && rows.tracks.isNotEmpty()) {
                // Generation anchor: the lazy extension re-fetches only [resume, ...) — out-of-band edits
                // before the cursor are invisible. Page 1 is the cheapest high-signal check; a mutation re-seeds fresh.
                val firstBody = userGet(account, "/playlists/${uuid.percentEncoded()}")
                var firstIds: List<String>? = null
                var firstNext: String? = null
                // The anchor page-1's CONVERTED span (toTrack successes over the raw items), computed in the parse
                // block for the anchor-chain fallback's resume (resumeIndex lives in the converted domain).
                var anchorConverted: Int? = null
                if (firstBody != null) {
                    // A FAILED anchor fetch (dead session token) must NOT discard the continuation walk that
                    // already succeeded (stalls extension forever) — the overlap comparison still guards the splice.
                    val firstPage = runCatching { decodePlaylistPage(firstBody) }.getOrNull()
                    firstIds = firstPage?.relatedStreams
                        ?.mapNotNull { videoIdOf(it.url) }
                        ?.filter { it.isNotEmpty() }
                    // Same converter as every cache writer: an item contributes when toTrack() is non-null —
                    // deriving the count from the converter keeps the resume in the converted domain.
                    anchorConverted = firstPage?.relatedStreams?.count { it.toTrack() != null }
                    firstNext = firstPage?.nextpage
                    if (firstPage?.relatedStreams?.isEmpty() == true && firstNext == null) {
                        // Authoritative empty account playlist (emptied on the web): open-empty cache so the
                        // stale span stops serving and a later recovery re-anchors; DECODED BLANK = throttle, never EOF.
                        val emptyR = CachedRows(emptyList(), null, complete = false, resumeIndex = 0)
                        store.put(key, json.encodeToJsonElement(emptyR))
                        return emptyR
                    }
                    if (firstIds != null && firstIds.isNotEmpty()) {
                        // The whole first page is the anchor (not just 20 rows):
                        // an edit anywhere inside it invalidates the cache.
                        val k = minOf(firstIds.size, rows.tracks.size)
                        var anchorOk = true
                        for (i in 0 until k) {
                            if (firstIds[i] != rows.tracks[i].id) {
                                anchorOk = false
                                break
                            }
                        }
                        if (!anchorOk) {
                            val beforeRows = rows
                            val rebuilt = recoverFromPageOne()
                            rows = rebuilt
                            // Re-enter ONLY on a genuinely new live chain: the recovery's bounded walk may fall
                            // SHORT of minRows with a live token — re-entering covers the span in THIS call.
                            if (rebuilt.complete || rebuilt.nextpage.isNullOrBlank() ||
                                rebuilt === beforeRows || ++recoveries > 2
                            ) return rebuilt
                            // The rebuilt cache is a fresh generation anchored on this very page: no second
                            // anchor/verify pass (the same flag the replaced-cache re-entry sets).
                            anchoredVerify = true
                            continue
                        }
                    }
                }
                // Anchor chain fallback for caches beyond the verify budget: the anchor's own nextpage is
                // live this call. Within-budget caches prefer the verify-walk token (resumes where the cache ends).
                if (verifyLiveToken == null && firstIds != null && !firstNext.isNullOrBlank()) {
                    verifyLiveToken = firstNext
                    // The token resumes after the anchor's WHOLE converted span — never clamp to cache size:
                    // clamping a shorter cache claims a resume inside it while live rows are skipped forever.
                    verifyLiveResume = anchorConverted ?: firstIds.size
                }
            }
            // The anchor covers page 1 only; a mutation between it and the cursor shifts the continuation
            // silently. Re-verify the span from page 1 on EVERY open-cache read (Store.page() twin): mismatch -> live.
            if (!anchoredVerify) {
                val verifyTrack = mutableListOf<MetadataTrack>()
                var lastVerifyRows = 0
                var lastVerifyConverted = 0
                // Stop on the CONVERTED count (Store.kt twin parity): the RAW stopAfterRows bound trips one
                // page early over dead/odd rows (never cached), leaving spanCovered permanently false.
                val verifyTarget = if (rows.tracks.size > REVERIFY_LIMIT) minRows else rows.tracks.size
                var windowOk = rows.tracks.size > REVERIFY_LIMIT && rows.tracks.size >= minRows
                val verifyToken = walkPlaylistPages(account, uuid, pageLimit = null, fetch = { path -> userGet(account, path) }) { page, _ ->
                    lastVerifyRows = page.relatedStreams.size
                    lastVerifyConverted = 0
                    page.relatedStreams.forEach { item ->
                        item.toTrack()?.let { verifyTrack += it; store.rememberTrack(it); lastVerifyConverted++ }
                    }
                    if (windowOk && verifyTrack.size >= verifyTarget) {
                                                windowOk = verifyTrack.take(verifyTarget).map { it.id } == rows.tracks.take(verifyTarget).map { it.id }
                    }
                    verifyTrack.size < (if (windowOk) verifyTarget else rows.tracks.size)
                }
                val verifyOk = verifyTrack.size == rows.tracks.size &&
                    verifyTrack.map { it.id } == rows.tracks.map { it.id }
                // Replace ONLY on proven spans, like Store.page(): full coverage (differences = live wins) or a
                // genuine EOF (rows on the final page). An EMPTY-page break proves neither — keep the stored cache.
                val spanCovered = verifyTrack.size >= rows.tracks.size
                // eofProven also requires CONVERTED final-page rows: an all-dead final page + null token is
                // NOT a proven end — treating it as one truncates the healthy cache and freezes it (no TTL arm).
                val eofProven = verifyToken == null && lastVerifyRows > 0 && verifyTrack.isNotEmpty() && lastVerifyConverted > 0
                if (!verifyOk && verifyTrack.isNotEmpty() && (spanCovered || eofProven)) {
                    // Stale span or the playlist now ends inside it: the LIVE re-walk is authoritative. A
                    // null verifyToken = EOF (shrink, complete); an EMPTY verifyTrack = fetch failed, skip.
                    val cont = mutableListOf<MetadataTrack>()
                    var lastContRows = 0
                    var lastContConverted = 0
                    val contToken = if (verifyToken == null) null else walkPlaylistPages(
                        account, uuid, pageLimit = null,
                        fetch = { path -> userGet(account, path) },
                        startToken = verifyToken,
                        stopAfterRows = (minRows - verifyTrack.size).coerceAtLeast(0),
                    ) { page, _ ->
                        lastContRows = page.relatedStreams.size
                        lastContConverted = 0
                        page.relatedStreams.forEach { item ->
                            item.toTrack()?.let { cont += it; store.rememberTrack(it); lastContConverted++ }
                        }
                        true
                    }
                    // complete only on a PROVEN end: a null token behind an EMPTY final page is the transient
                    // quirk — leave open, never freeze the truncated span (DECODED BLANK = same; converted proof).
                    val replacedComplete = (verifyToken == null && lastVerifyConverted > 0) ||
                        (contToken == null && lastContRows > 0 && lastContConverted > 0)
                    val replaced = CachedRows(
                        tracks = verifyTrack + cont,
                        nextpage = contToken,
                        complete = replacedComplete,
                        resumeIndex = verifyTrack.size + cont.size,
                        lastVerifiedAt = if (replacedComplete) epochMillis() else 0,
                    )
                    store.put(key, json.encodeToJsonElement(replaced))
                    // Same coverage re-entry as the normal extension path: the continuation budget is RAW but
                    // the cache grows only by CONVERTED rows — re-enter from its token if short of minRows.
                    if (replaced.tracks.size < minRows && !replaced.complete) {
                        rows = replaced
                        anchoredVerify = true
                        continue
                    }
                    return replaced
                }
                if (!verifyToken.isNullOrBlank() && spanCovered) {
                    // The verify walk covered the whole cached span with a LIVE
                    // trailing token: it resumes exactly where the cache ends.
                    verifyLiveToken = verifyToken
                    verifyLiveResume = rows.tracks.size
                }
                openCacheVerifiedAt[key] = epochMillis()
            }
            if (inSpanThisPass) {
                // The requested window lies inside the cached span: the page-1 anchor + window-capped verify
                // already were the whole pass — return the (possibly repaired) cache directly (Store.page() parity).
                return rows
            }
            val nextpage = walkPlaylistPages(
                account, uuid,
                pageLimit = null,
                fetch = { path -> userGet(account, path) },
                startToken = rows.nextpage,
                // Re-covered rows do not grow the cache: when the token resumes inside tracks, fetch the whole
                // remaining cached span plus the rows actually needed, so a single scroll reaches past it.
                stopAfterRows = minRows - rows.tracks.size + (rows.tracks.size - resume),
            ) { page, _ ->
                lastPageRows = page.relatedStreams.size
                lastPageConverted = 0
                page.relatedStreams.forEach { item ->
                    item.toTrack()?.let { extra += it; store.rememberTrack(it); lastPageConverted++ }
                }
                true
            }
            if (nextpage == rows.nextpage && extra.isEmpty()) {
                val beforeRows = rows
                val rebuilt = recoverFromPageOne()
                rows = rebuilt
                // Re-enter ONLY on a genuinely new live chain: the failure arms return the OLD rows object
                // untouched (reference identity) — it cannot grow and would refetch the dead token forever.
                if (rebuilt.complete || rebuilt.nextpage.isNullOrBlank() ||
                    rebuilt === beforeRows || ++recoveries > 2
                ) return rebuilt
                continue
            }
            anchoredVerify = true
            // The fetched rows re-cover exactly [resume, resume + overlap); everything beyond is NEW positions
            // and is kept even when its id repeats an earlier row (Piped playlists legitimately repeat tracks).
            var overlap = minOf(extra.size, rows.tracks.size - resume)
            if (overlap > 0) {
                // The cached span [resume, resume + overlap) must be what the fetched rows continue; a
                // mutation invalidates it. Never splice generations or loop the recovery; re-verify the PREFIX.
                var mismatch = false
                for (i in 0 until overlap) {
                    if (extra[i].id != rows.tracks[resume + i].id) {
                        mismatch = true
                        break
                    }
                }
                if (mismatch) {
                    val fresh = boundedFresh() ?: return rows
                    val (_, freshTracks, freshNext) = fresh
                    if (freshNext == null && lastFreshRows == 0) {
                        // The recovery re-walk hit a blank page 1 (the quirk recoverFromPageOne guards): it would
                        // wipe this cache to EMPTY + complete=true. Keep the stale-but-real cache; retry next extension.
                        return rows
                    }
                    if (freshNext == null && lastFreshRows > 0 && lastFreshConverted == 0) {
                        // An all-dead re-walk proves nothing about the END — the fresh-alone arm would write
                        // complete=true and wipe the healthy cache (frozen; save() dedupe re-pushes dupes later).
                        return rows
                    }
                    if (freshNext?.isBlank() == true) {
                        // A DECODED BLANK fresh continuation is the throttled shape — never a verdict: the fresh
                        // PREFIX is live evidence only. Keep the cached same-generation tail (interior resumeIndex).
                        return CachedRows(
                            tracks = freshTracks + rows.tracks.drop(freshTracks.size),
                            nextpage = freshNext,
                            complete = false,
                            resumeIndex = freshTracks.size,
                        )
                    }
                    val prefixMatches = freshTracks.map { it.id } == rows.tracks.take(freshTracks.size).map { it.id }
                    val healed = if (!prefixMatches || freshNext == null || freshTracks.size >= rows.tracks.size || freshTracks.size < resume) {
                        // Prefix changed, fresh alone covers the whole span, or the bounded re-walk stayed SHORTER
                        // than the token's resume point (merge would leave a hole): fresh is authoritative.
                        CachedRows(tracks = freshTracks, nextpage = freshNext, complete = freshNext == null, resumeIndex = freshTracks.size, lastVerifiedAt = if (freshNext == null) epochMillis() else 0)
                    } else {
                        // Prefix current but the stored-token continuation DISAGREES with the cached tail: that
                        // token is from an OLDER generation — re-derive the tail LIVE; complete only on proven end.
                        val liveTail = mutableListOf<MetadataTrack>()
                        var lastTailRows = 0
                        var lastTailConverted = 0
                        val tailToken = walkPlaylistPages(
                            account, uuid,
                            pageLimit = null,
                            fetch = { path -> userGet(account, path) },
                            startToken = freshNext,
                        ) { page, _ ->
                            lastTailRows = page.relatedStreams.size
                            lastTailConverted = 0
                            page.relatedStreams.forEach { item ->
                                item.toTrack()?.let { track -> liveTail += track; store.rememberTrack(track); lastTailConverted++ }
                            }
                            // Converted target (see the recoverFromPageOne twin).
                            liveTail.size < rows.tracks.size - freshTracks.size
                        }
                        if (tailToken.isNullOrBlank() && liveTail.isEmpty()) {
                            // Same quirk guard as the recoverFromPageOne twin: an EMPTY tail continuation after
                            // a fresh-prefix success is transient, not an end — keep the cached tail (interior mark).
                            CachedRows(
                                tracks = freshTracks + rows.tracks.drop(freshTracks.size),
                                nextpage = freshNext,
                                complete = false,
                                resumeIndex = freshTracks.size,
                            )
                        } else if (liveTail.isNotEmpty() || tailToken == null || tailToken.isBlank()) {
                            // A live tail ending on an EMPTY page + null token is the same quirk: keep the cache
                            // open (complete=false, no token) so the next call re-anchors instead of freezing short.
                            CachedRows(
                                tracks = freshTracks + liveTail,
                                nextpage = tailToken,
                                complete = tailToken == null && lastTailRows > 0 && lastTailConverted > 0,
                                resumeIndex = freshTracks.size + liveTail.size,
                                lastVerifiedAt = if (tailToken == null && lastTailRows > 0 && lastTailConverted > 0) epochMillis() else 0,
                            )
                        } else {
                            // Continuation unavailable with a live token: keep the old tail (offline display); the
                            // interior token marks it for the next successful extension's mismatch heal.
                            CachedRows(
                                tracks = freshTracks + rows.tracks.drop(freshTracks.size),
                                nextpage = freshNext,
                                complete = false,
                                resumeIndex = freshTracks.size,
                            )
                        }
                    }
                    store.put(key, json.encodeToJsonElement(healed))
                    // Re-enter only when the healed cache has a LIVE chain DIFFERENT from the one whose
                    // continuation mismatched (an unchanged token would re-trigger the same mismatch heal).
                    if (healed.complete || healed.nextpage.isNullOrBlank() ||
                        healed.nextpage == rows.nextpage || ++recoveries > 2
                    ) return healed
                    rows = healed
                    continue
                }
            }
            // The token advances by everything fetched (re-covered rows too). Mapped into the new tracks:
            // inside the unchanged span while the fetch stayed in it, at the end of the appended rows otherwise.
            val n = rows.tracks.size
            val fetched = extra.size
            val newResume = if (fetched <= overlap) resume + fetched else n + fetched - overlap
            // A 'null' nextpage with an EMPTY final page is ambiguous (throttled/blank 200 looks identical
            // to a proven end): never complete — keep OPEN; null WITH rows on the final page is a genuine EOF.
            if (nextpage == null && lastPageRows == 0) {
                // A dead STORED token would stall every future extension: heal once from the LIVE chain
                // (re-point, persist, re-enter so the span IS covered now). One re-point per call.
                val live = verifyLiveToken
                if (healDepth == 0 && live != null && live != rows.nextpage &&
                    rows.tracks.size >= verifyLiveResume
                ) {
                    store.put(key, json.encodeToJsonElement(rows.copy(nextpage = live, resumeIndex = verifyLiveResume)))
                    return rowsFor(uuid, minRows, healDepth + 1)
                }
                // Rows fetched BEFORE the empty page are real tail data: persist them merged (complete=false);
                // discarding re-fetches + re-discards every scroll (Store.page() twin appends them).
                if (extra.isNotEmpty()) {
                    // Merge WITHOUT truncating: the empty final page is ambiguous, so the cached tail beyond
                    // the fetched span is NOT proven gone (take(resume+overlap) would spuriously shrink).
                    rows = rows.copy(
                        tracks = rows.tracks.take(resume) + extra + rows.tracks.drop(resume + extra.size),
                        nextpage = null,
                        complete = false,
                        resumeIndex = resume + extra.size,
                    )
                    store.put(key, json.encodeToJsonElement(rows))
                }
                return rows
            }
            // A continuation walk reaching EOF while the requested span sits inside cached rows PROVES the
            // cached positions beyond the EOF are gone: drop them, never freeze (all-dead finals stay OPEN).
            if (nextpage == null && lastPageRows > 0 && lastPageConverted == 0) {
                if (extra.isNotEmpty()) {
                    rows = rows.copy(
                        tracks = rows.tracks.take(resume) + extra + rows.tracks.drop(resume + extra.size),
                        nextpage = null,
                        complete = false,
                        resumeIndex = resume + extra.size,
                    )
                    store.put(key, json.encodeToJsonElement(rows))
                } else {
                    // The dead chain consumed to EOF produced NO rows: the STORED token must not survive (next
                    // visit re-walks the same degenerate chain forever). Heal onto the LIVE chain or drop the token.
                    val live = verifyLiveToken
                    if (healDepth == 0 && live != null && live != rows.nextpage &&
                        rows.tracks.size >= verifyLiveResume
                    ) {
                        store.put(key, json.encodeToJsonElement(rows.copy(nextpage = live, resumeIndex = verifyLiveResume)))
                        return rowsFor(uuid, minRows, healDepth + 1)
                    }
                    if (rows.nextpage != null) {
                        store.put(key, json.encodeToJsonElement(rows.copy(nextpage = null)))
                    }
                }
                return rows
            }
            val keepPrefix = if (nextpage == null) resume + overlap else n
            val extended = rows.copy(
                tracks = rows.tracks.take(keepPrefix) + extra.drop(overlap),
                nextpage = nextpage,
                complete = nextpage == null,
                resumeIndex = if (nextpage == null) keepPrefix else newResume,
                // EOF proven by this walk: stamp so the Store.page() TTL arm
                // does not re-verify the completion on the next read.
                lastVerifiedAt = if (nextpage == null) epochMillis() else rows.lastVerifiedAt,
            )
            store.put(key, json.encodeToJsonElement(extended))
            // stopAfterRows counts RAW items but the cache grows by CONVERTED rows: when conversion dropped
            // items this pass ended short of minRows. Re-enter from the live token (Store.page() twin).
            if (extended.tracks.size < minRows && !extended.complete) {
                rows = extended
                continue
            }
            return extended
            }
            // The loop ended with minRows covered or a complete cache.
            return rows
        }
        return rows
    }

    // ── write-through: saved albums/artists/tracks ─────────────────────────────

    suspend fun save(kind: SavedKind, ids: List<String>) {
        val account = sessionProvider() ?: return
        if (ids.isEmpty()) return
        // Ids whose repKey was written this call (rebind OR fresh push) — needs
        // to outlive the runCatching below for the snapshot/stamp gates.
        val boundIds = mutableListOf<String>()
        val mirrorOk = runCatching {
            val playlistId = ensurePlaylist(account, kind)
            // Every saved entity must own a distinct mirror row (shared reps blur remove()); skip already-
            // mirrored videos per entity. PARTIAL mirror views fail the save, or dedupe re-pushes owning ids.
            val used = readPlaylistVideoIds(account, playlistId)?.toHashSet()
                ?: throw IllegalStateException("mirror membership walk ambiguous")
            // Snapshot membership BEFORE the batch loop: the loop mutates 'used', and for TRACK the rep IS
            // the id — filtering against the mutated set would drop every to-be-pushed id and no add POST fires.
            val mirrorIds = used.toHashSet()
            val fresh = mutableListOf<String>()
            // Fresh reps' repKeys are written ONLY after the batch push succeeds: a leaked key (never-pushed
            // row) makes the id look saved without a mirror row, and an ABSENT-verdict unsave never passes.
            val pendingReps = mutableListOf<Pair<String, String>>()
            // The snapshot extension + ownership stamp cover ONLY boundIds: an id without a rep (flaky fetch)
            // would be claimed with no mirror row — unconfirmable, stuck for the TTL. Input ids are deduped.
            for (id in ids.distinct()) {
                // Reuse the entity's own row when it is already mirrored (a different video would push a second
                // row surviving remove() as a stale orphan). Same lookup as repVideoOf(), incl. legacy -Topic.
                val existingRep = repVideoOf(kind, id)
                    ?.takeIf { it.isNotEmpty() && it in used }
                // A null representative is a HARD save failure, never a silent skip: the caller already wrote the
                // id locally and must not see success with no mirror row/repKey/stamp. Fail the batch; round-76 retry.
                val rep = existingRep ?: representativeVideo(account, kind, id, used)
                    ?: throw IllegalStateException("Piped mirror save: no representative video")
                boundIds += id
                if (existingRep != null) {
                    // The entity's own row is already mirrored: rebind it, no
                    // push needed.
                    store.put(repKey(kind, id), JsonPrimitive(rep))
                } else {
                    // Fresh rep — including the share-fallback (every candidate is another entity's row): push it
                    // as THIS entity's own row; a same-kind shared binding can never be removed for one entity alone.
                    used += rep
                    fresh += rep
                    pendingReps += id to rep
                }
            }
            if (fresh.isNotEmpty()) {
                // TRACK rows ARE the ids: membership is an EXACT per-entity test, so an already-owned id
                // must not re-push (add endpoint doesn't dedupe). ALBUM/ARTIST reps are VIDEO ids — nothing proven.
                val existing = if (kind == SavedKind.TRACK) mirrorIds else null
                val toPush = if (existing != null) fresh.filterNot { it in existing } else fresh
                if (toPush.isNotEmpty()) {
                    // userPost returns null for non-2xx and postConfirmed rejects throttle/error 2xx shapes:
                    // a failed add must fail the save like an exception — else snapshot+stamp report unwritten.
                    val posted = userPost(account, "/user/playlists/add", buildJsonObject {
                        put("playlistId", playlistId)
                        putJsonArray("videoIds") { toPush.forEach { add(it) } }
                    }.toString())
                    if (!postConfirmed(posted)) throw IllegalStateException("mirror add failed")
                }
                // Bind every pending id: the ids filtered out above ALREADY own
                // their row in the mirror, so their repKey is equally valid.
                pendingReps.forEach { (id, rep) -> store.put(repKey(kind, id), JsonPrimitive(rep)) }
            }
        true
        }.getOrElse { t ->
            // A cancelled save must PROPAGATE, never mask as a generic mirror-write failure: the add POST may
            // already have appended rows and the host must know the operation was ABORTED (twin remove() same rule, round-104).
            if (t is CancellationException) throw t
            false
        }
        if (!mirrorOk) {
            // The mirror write failed: recording ids saved would pin them saved-but-unremovable (no
            // DELETED/ABSENT can confirm a rep-less id). The failure ESCAPES; round-76 retry re-attempts.
            throw IllegalStateException("Piped mirror save failed")
        }
        // Keep the account snapshot's saved sets in sync so remove()'s ownership gate sees ids saved this
        // session before the next refresh rebuilds the snapshot from the mirror. Only the session's OWN snapshot is extended.
        val state = store.cachedAccountState()
        if (state != null &&
            state.instance.trim().trimEnd('/') == account.instance.trim().trimEnd('/') &&
            state.username == account.username
        ) {
            val updated = when (kind) {
                SavedKind.TRACK -> state.copy(savedTracks = (state.savedTracks + boundIds).distinct())
                SavedKind.ALBUM -> state.copy(savedAlbums = (state.savedAlbums + boundIds).distinct())
                SavedKind.ARTIST -> state.copy(savedArtists = (state.savedArtists + boundIds.map(::canonicalArtistId)).distinct())
            }
            store.cacheAccountState(updated)
        }
        // Ownership stamp on the device save: the mirror may later vanish without the id; without the stamp
        // the no-mirror removal could never confirm a repKeyed id. The stamp survives rewrites; bound only.
        val stamp = "${account.instance.trim().trimEnd('/')}|${account.username}"
        boundIds.forEach { id ->
            val canonical = if (kind == SavedKind.ARTIST) canonicalArtistId(id) else id
            addOwnerStamp(kind, canonical, stamp)
        }
    }

    /** Account qualifier for per-video resolution latches: one account's outage must not latch a video for the
     * others (mirror rows and save state are account-scoped; the latch was the one key that was not). */
    private fun latchScope(account: PipedAccount): String = "${account.instance.trim().trimEnd('/')}|${account.username}"

    /** Exhaustion latches: 3 failed resolution attempts mark the video unresolvable PER KIND AND ACCOUNT. */
    private suspend fun albumUnresolvable(account: PipedAccount, videoId: String): Boolean = latched("unresolvable:album:${latchScope(account)}:$videoId")
    private suspend fun artistUnresolvable(account: PipedAccount, videoId: String): Boolean = latched("unresolvable:artist:${latchScope(account)}:$videoId")

    /** True while the key's latch is in its cooldown window; an EXPIRED latch is cleared so the row re-attempts. */
    private suspend fun latched(key: String): Boolean {
        val raw = store.get(key)
        val at = raw?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return false
        val remaining = at + RESOLUTION_RETRY_COOLDOWN_MS - epochMillis()
        return if (remaining > 0) true else {
            store.remove(key)
            false
        }
    }

    /** Per-device account stamps ('instance|username') for [id]; a second account APPENDS (round-103: its ABSENT
     * unlock cannot steal the first's). Legacy single strings decode as one-element lists; absence = ownerless. */
    private suspend fun ownerStampsOf(kind: SavedKind, id: String): List<String> {
        val canonical = if (kind == SavedKind.ARTIST) canonicalArtistId(id) else id
        val raw = store.get("saved.owner:${kind.name.lowercase()}:$canonical") ?: return emptyList()
        return if (raw is JsonArray) {
            runCatching { json.decodeFromJsonElement<List<String>>(raw) }.getOrNull().orEmpty()
        } else if (raw is JsonPrimitive) {
            listOfNotNull(raw.contentOrNull)
        } else emptyList()
    }

    /** Appends [stamp] to the owner list for [canonical] (device-global key):
     *  every account that saved the entity keeps its claim. */
    private suspend fun addOwnerStamp(kind: SavedKind, canonical: String, stamp: String) {
        store.put(
            "saved.owner:${kind.name.lowercase()}:$canonical",
            json.encodeToJsonElement((ownerStampsOf(kind, canonical) + stamp).distinct()),
        )
    }

    suspend fun remove(kind: SavedKind, ids: List<String>) {
        if (ids.isEmpty()) return
        val account = sessionProvider()
        if (account == null) {
            // Logged out: no session to verify/delete remote rows, and the device-global bookkeeping is identity-
            // gated — mutating it could destroy another account's state. Unsave completes once a session exists.
            return
        }
        runCatching {
            // Resolve the mirror playlist for THIS account. A pre-upgrade bare-uuid binding has no owner and is
            // rejected by the gate; rescue it here (ensurePlaylist's order), without creating a playlist.
            var playlistId = storedPlaylistId(account, kind)
            var listingReached = true
            // The owned-binding verification's decoded listing when it PROVED the binding gone: the no-binding
            // branch reuses this proof instead of re-fetching the same listing (round-103 corr-1).
            var provenAbsent: List<JsonObject>? = null
            if (playlistId != null) {
                // Verify-then-trust (ensurePlaylist's rule): an OWNED binding can be STALE (mirror deleted on
                // the web) — walking a dead uuid silently no-ops every retry. Gone -> rescue the uuid-exact legacy.
                val listing = userGet(account, "/user/playlists")
                if (listing == null) {
                    // Listing unreachable: cannot verify — keep the last known binding and walk it (a transient
                    // failure must not block removals that would otherwise succeed).
                } else {
                    val parsed = runCatching {
                        json.parseToJsonElement(listing).jsonArray.map { it.jsonObject }
                    }.getOrNull()
                    if (parsed == null) {
                        listingReached = false
                    } else if (parsed.none { it["id"]?.jsonPrimitive?.contentOrNull == playlistId }) {
                        // The bound mirror is gone: rescue ONLY the uuid-exact legacy binding (rename keeps the
                        // uuid; missing = deleted). NO same-name adoption on the DELETE side (round-101).
                        playlistId = legacyOwnedUuid(account, playlistKey(kind), parsed)
                        if (playlistId != null) {
                            store.put(playlistKey(kind), json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, playlistId)))
                        }
                        // The decoded listing PROVED the binding gone: keep the decode so the no-binding branch
                        // below is not re-fetched (round-103 corr-1).
                        provenAbsent = parsed
                    }
                    // else: the binding is live in the listing — use it as-is.
                }
            }
            if (playlistId == null) {
                // The owned-binding branch may already have decoded and PROVEN the mirror absent: reuse that
                // decode — a transient second failure would discard the proven absence (round-103 corr-1).
                var parsed = provenAbsent
                if (parsed == null) {
                    val listing = userGet(account, "/user/playlists")
                    if (listing == null) {
                        // Listing unreachable: cannot tell "no mirror exists" from "rows unreachable", so
                        // nothing is confirmed removed.
                        listingReached = false
                    } else {
                        parsed = runCatching {
                            json.parseToJsonElement(listing).jsonArray.map { it.jsonObject }
                        }.getOrNull()
                        if (parsed == null) {
                            // A 2xx body that is not a playlist listing (proxy HTML, {"error":…}) proves nothing:
                            // the mirror may still exist with rows — nothing is confirmed removed.
                            listingReached = false
                        }
                    }
                }
                if (parsed != null) {
                    // A renamed mirror is still the account's playlist: the legacy bare-uuid binding is the ONLY
                    // admissible delete-side rescue; same-name adoption is forbidden (round-101).
                    playlistId = legacyOwnedUuid(account, playlistKey(kind), parsed)
                    if (playlistId != null) {
                        store.put(playlistKey(kind), json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, playlistId)))
                    }
                }
            }
            val outcome = if (playlistId == null) {
                // No mirror playlist on this account: nothing survives remotely, so local bookkeeping is final
                // (unreachable listing -> empty). This ABSENT is NOT walk-backed (round-108): unstamped ids kept.
                if (listingReached) RemovalOutcome(ids, emptySet(), walkBacked = false)
                else RemovalOutcome(emptyList(), emptySet(), walkBacked = false)
            } else {
                remoteRemoveRows(account, kind, ids, playlistId)
            }
            // An ABSENT verdict proves only "no row in THIS account's mirror" — the id may belong to another
            // account's device save on this shared library; deleted mirror rows are owned by definition.
            val owned = ownedSnapshotIds(kind)
            val sessionStamp = "${account.instance.trim().trimEnd('/')}|${account.username}"
            // ABSENT unlocks (round 62): STAMPED ids whose snapshot a wiped-mirror refresh erased; UNSTAMPED ids
            // only when WALK-BACKED (round-108). Both need identity-CLAIMED snapshots (round-101; gated read 107).
            val claimed = cachedState()?.let {
                !(it.instance.isBlank() && it.username.isBlank())
            } ?: false
            val localConfirmed = outcome.confirmed.filter { id ->
                val ownerStamps = ownerStampsOf(kind, id)
                id in outcome.deleted || repVideoOf(kind, id) == null ||
                    (claimed && (id in owned ||
                        // round-108: the ownerless-unstamped unlock needs WALK-BACKED ABSENT evidence (round 62's
                        // proven-empty escape) — the no-mirror shortcut proves nothing about another account's mirror.
                        (outcome.walkBacked && ownerStamps.isEmpty()) ||
                        sessionStamp in ownerStamps))
            }
            localConfirmed.forEach { id ->
                // A DELETED row proves only THIS account's mirror row is gone: the device-global repKey can still
                // map ANOTHER account's save, so deleted ids keep rep/stamp. Only ABSENT (owner-gated) drops global.
                if (id !in outcome.deleted) {
                    store.remove(repKey(kind, id))
                    if (kind == SavedKind.ARTIST && id.startsWith("channel:")) {
                        store.remove(repKey(kind, id + " - Topic"))
                    }
                }
            }
            // Only ids whose mirror rows were actually deleted leave the cached saved set: surviving rows
            // would resurrect the item at the next refresh, so their state stays truthful until a retry completes.
            dropFromCache(kind, localConfirmed)
            // DELETED-confirmed ids leave the DEVICE library too: their row was really removed, so the local
            // 'saved' marker must not linger (round-102). The ownership filter already ran; nothing unconfirmed.
            when (kind) {
                SavedKind.TRACK -> library.removeTracks(localConfirmed)
                SavedKind.ALBUM -> library.removeAlbums(localConfirmed)
                SavedKind.ARTIST -> {
                    // round-105: legacy raw uploader shapes ARE the id until canonicalized; an exact-id prune
                    // would miss them. Prune every library form whose CANONICAL form is confirmed.
                    val confirmedIds = localConfirmed.toHashSet()
                    library.removeArtists(library.savedArtists().filter { canonicalArtistId(it) in confirmedIds })
                }
            }
        }.getOrElse { t ->
            // A cancelled unsave must not silently complete: it already deleted mirror rows / wrote
            // bookkeeping, and the host must know the operation was aborted.
            if (t is CancellationException) throw t
        }
    }

    /** Ids the current session's account owns (its identity-gated snapshot). */
    private suspend fun ownedSnapshotIds(kind: SavedKind): Set<String> = when (kind) {
        SavedKind.TRACK -> stateOrEmpty().savedTracks.toHashSet()
        SavedKind.ALBUM -> stateOrEmpty().savedAlbums.toHashSet()
        SavedKind.ARTIST -> stateOrEmpty().savedArtists.map(::canonicalArtistId).toHashSet()
    }

    /** Deletes [ids] from the shared DEVICE library. Logged out every device save is local and removable;
     * logged in, ids owned by ANOTHER account must survive (an unsave by B would otherwise permanently erase A's). */
    suspend fun removeLibraryEntries(kind: SavedKind, ids: List<String>) {
        if (ids.isEmpty()) return
        val session = sessionProvider()
        if (session == null) {
            // Logged out: every save is local and removable, but prune ARTIST entries by canonical form like
            // the logged-in twin (round-105/109): raw pre-upgrade entries survive exact-id removal; no refresh.
            when (kind) {
                SavedKind.TRACK -> library.removeTracks(ids)
                SavedKind.ALBUM -> library.removeAlbums(ids)
                SavedKind.ARTIST -> {
                    val canonicalIds = ids.map(::canonicalArtistId).toHashSet()
                    library.removeArtists(library.savedArtists().filter { canonicalArtistId(it) in canonicalIds })
                }
            }
            return
        }
        // Logged in: drop THIS account's snapshot ids, its STAMPED ids, and ownerless device saves (logged-
        // out: never mirrored, no repKey, no refresh absorbs — an unsave must still remove them). Others kept.
        val owned = ownedSnapshotIds(kind)
        val sessionStamp = "${session.instance.trim().trimEnd('/')}|${session.username}"
        // ... plus UNSTAMPED repKeyed ids (round-62 legacy saves; post-upgrade always stamped). Other
        // accounts' stamps stay protected; same claim gate as remove(): the CURRENT session's snapshot only.
        val claimed = cachedState()?.let {
            !(it.instance.isBlank() && it.username.isBlank())
        } ?: false
        // The ownerless-unstamped arm is NOT repeated here: it needs the WALK-BACKED ABSENT evidence only remove()
        // holds (its own prune cleared those ids); granting it here lets a foreign session destroy saves (round-108).
        val local = ids.filter { id ->
            val ownerStamps = ownerStampsOf(kind, id)
            repVideoOf(kind, id) == null ||
                (claimed && (id in owned || sessionStamp in ownerStamps))
        }
        when (kind) {
            SavedKind.TRACK -> library.removeTracks(local)
            SavedKind.ALBUM -> library.removeAlbums(local)
            SavedKind.ARTIST -> {
                // round-105: same legacy-shape issue as remove()'s prune — confirmed ids are canonical while
                // the library may hold the pre-upgrade raw form, and isSavedArtists unions the library. Prune canonically.
                val confirmedIds = local.toHashSet()
                library.removeArtists(library.savedArtists().filter { canonicalArtistId(it) in confirmedIds })
            }
        }
    }

    /** Deletes the mirror rows of [ids] from [playlistId] and reports which ids are CONFIRMED removed (their
     * representative rows and any stale duplicate rows resolving back to them were all deleted from the walked pages). */
    private class RemovalOutcome(
        val confirmed: List<String>,
        val deleted: Set<String>,
        // ABSENT verdicts are evidence only when a mirror playlist was actually walked: the no-mirror shortcut
        // proves nothing about ANOTHER account's mirror, so the ownerless-unstamped unlock must never ride it (round-108).
        val walkBacked: Boolean,
    )

    private suspend fun remoteRemoveRows(
        account: PipedAccount,
        kind: SavedKind,
        ids: List<String>,
        playlistId: String,
    ): RemovalOutcome {
        val wanted = ids.mapNotNull { repVideoOf(kind, it) }.toHashSet()
        // A row that another still-saved entity of the SAME kind uses as its representative must survive
        // (deleting it silently unsyncs the sibling). Each kind owns its mirror; cross-kind guards wedge removals.
        val shared = allSavedIds(kind).filterNot { it in ids }
            .mapNotNull { id -> repVideoOf(kind, id) }
            .toHashSet()
        val removable = wanted.filterNot { it in shared }
        val removedIds = ids.toHashSet()
        val rowIndexes = mutableListOf<Pair<Int, String>>()
        // Orphan rows (cache mapping resolves to a REMOVED id) delete without any repKey: attribute each to
        // its resolved owner so a rep-LESS id can be confirmed once walked — the rep-only gate left stuck.
        val orphanRows = mutableListOf<Pair<String, String>>()
        // Every row video and its positions: re-pointing a shared rep must pick rows that do not clash with
        // anything already in the mirror, and a row that becomes deletable later needs its position.
        val positionsOf = mutableMapOf<String, MutableList<Int>>()
        var walkPages = 0
        var lastWalkRows = 0
        var lastPageConverted = 0
        val finalToken = walkPlaylistPages(account, playlistId, pageLimit = null, fetch = { path -> userGet(account, path) }) { page, seen ->
            walkPages++
            lastWalkRows = page.relatedStreams.size
            lastPageConverted = 0
            page.relatedStreams.forEachIndexed { position, item ->
                val videoId = videoIdOf(item.url)
                if (videoId.isEmpty()) return@forEachIndexed
                lastPageConverted++
                positionsOf.getOrPut(videoId) { mutableListOf() } += seen + position
                // Also drop stale duplicate rows that still resolve to a removed entity (added by a re-save
                // that picked a fresh rep); otherwise the next refresh resurrects the item.
                val isOrphan = videoId !in shared && videoId !in removable && when (kind) {
                    SavedKind.ALBUM -> store.cachedTrackAlbum(videoId)?.let { it in removedIds } == true
                    SavedKind.ARTIST -> cachedArtistIdOf(videoId)?.let { canonicalArtistId(it) in removedIds } == true
                    SavedKind.TRACK -> false
                }
                if (isOrphan) {
                    val owner = when (kind) {
                        SavedKind.ALBUM -> store.cachedTrackAlbum(videoId)
                        SavedKind.ARTIST -> cachedArtistIdOf(videoId)?.let(::canonicalArtistId)
                        SavedKind.TRACK -> null
                    }
                    if (owner != null) orphanRows += owner to videoId
                }
                if (videoId in removable || isOrphan) rowIndexes += (seen + position) to videoId
            }
            true
        }
        // A zero-page walk proves nothing and is NOT complete (54/56). One decoded page, zero rows + NULL token
        // = PROVEN empty mirror (unlocks ABSENT); NULL behind a MID-WALK empty final page or a BLANK is ambiguous.
        val walkedKeys = positionsOf.keys
        // Same completion doctrine as the mirrorState twin: PROVEN only with CONVERTED rows — 'converted empty'
        // as an end would confirm every id ABSENT; refresh re-binds them. One decoded empty page is PROVEN.
        val walkComplete = finalToken == null && (
            (walkPages == 1 && lastWalkRows == 0) ||
                (lastWalkRows > 0 && walkedKeys.isNotEmpty() && lastPageConverted > 0)
            )
        // A row shared with a still-saved sibling cannot be deleted: re-point every sibling to a fresh distinct
        // row first, then delete. Re-point needs only the walk ENDED (finalToken == null), NOT walkComplete (round-109).
        val walkEnded = finalToken == null && walkPages > 0 && walkedKeys.isNotEmpty()
        var finalShared = shared
        if (walkEnded && shared.isNotEmpty() && shared.any { it in wanted }) {
            val used = (positionsOf.keys).toMutableSet()
            // Every video scheduled for deletion this round (removable + orphan rows). A re-pointed sibling's rep
            // must never be one of these or any other shared rep — release deletes EVERY occurrence of that video.
            val rowIndexVideos = rowIndexes.map { it.second }.toHashSet()
            val stillNeeded = mutableSetOf<String>()
            val siblingsByRep = mutableMapOf<String, MutableList<String>>()
            for (sibling in allSavedIds(kind).filterNot { it in ids }) {
                val rep = repVideoOf(kind, sibling) ?: continue
                if (rep in shared) siblingsByRep.getOrPut(rep) { mutableListOf() } += sibling
            }
            for ((video, siblings) in siblingsByRep) {
                if (video !in wanted) continue
                for (sibling in siblings) {
                    val newRep = representativeVideo(account, kind, sibling, used)
                    // Never re-point onto a video scheduled for deletion, NOR any row already in the mirror (round-104):
                    // an in-used row belongs to another saved entity — a duplicate makes its unsave delete the copy too.
                    if (newRep == null || newRep in used || newRep in shared || newRep in rowIndexVideos) {
                        stillNeeded += video
                        continue
                    }
                    val pushed = postConfirmed(userPost(account, "/user/playlists/add", buildJsonObject {
                        put("playlistId", playlistId)
                        putJsonArray("videoIds") { add(newRep) }
                    }.toString()))
                    if (!pushed) { stillNeeded += video; continue }
                    used += newRep
                    store.put(repKey(kind, sibling), JsonPrimitive(newRep))
                    if (kind == SavedKind.ARTIST && sibling.startsWith("channel:")) {
                        store.remove(repKey(kind, sibling + " - Topic"))
                    }
                }
            }
            finalShared = shared.filterNot { it in wanted && it !in stillNeeded }.toHashSet()
        }
        if (finalShared != shared) {
            // Rows that became deletable after the re-point were classified shared and are not in rowIndexes yet:
            // schedule exactly those — (wanted - finalShared) would delete the same index twice.
            val releasedShared = (wanted - finalShared).filter { it in shared }
            for (video in releasedShared) {
                positionsOf[video]?.forEach { rowIndexes += it to video }
            }
        }
        // A video scheduled at multiple positions (duplicate mirror rows) counts as removed only when
        // EVERY scheduled position was deleted: one surviving duplicate would resolve back and resurrect the entity.
        val scheduledCount = mutableMapOf<String, Int>()
        for ((_, videoId) in rowIndexes) scheduledCount[videoId] = (scheduledCount[videoId] ?: 0) + 1
        val deletedCount = mutableMapOf<String, Int>()
        for ((index, videoId) in rowIndexes.distinct().sortedByDescending { it.first }) {
            val body = userPost(account, "/user/playlists/remove", buildJsonObject {
                put("playlistId", playlistId)
                put("index", index)
            }.toString())
            if (postConfirmed(body)) deletedCount[videoId] = (deletedCount[videoId] ?: 0) + 1
        }
        val removedVideos = deletedCount.filter { (videoId, n) -> n >= (scheduledCount[videoId] ?: 0) }.keys
        // Rows deleted from walked pages are gone for good: ids are confirmed even when the walk aborted
        // mid-way (a complete-walk gate would wedge the unsave forever). Unwalked pages may hold stale dupes.
        val removed = removedVideos.toHashSet()
        // Only orphan rows that were ACTUALLY removed (every scheduled position
        // deleted) count as evidence for their owner.
        val orphanRemovedByOwner = mutableMapOf<String, MutableList<String>>()
        for ((owner, videoId) in orphanRows) {
            if (videoId in removed) orphanRemovedByOwner.getOrPut(owner) { mutableListOf() } += videoId
        }
        // Which saved entities each walked video represents (reverse of the repKey bindings). A row whose owner is
        // only another entity cannot resurrect THIS one; an UNMAPPED row (web/legacy, beyond the budget) blocks.
        val ownerByVideo = mutableMapOf<String, MutableSet<String>>()
        for (savedId in allSavedIds(kind)) {
            val canonical = if (kind == SavedKind.ARTIST) canonicalArtistId(savedId) else savedId
            repVideoOf(kind, savedId)?.let { v -> ownerByVideo.getOrPut(v) { mutableSetOf() } += canonical }
        }
        // Does a surviving walked row block removing [canonical]? Mapped to the entity -> blocks; mapped to
        // another -> harmless; UNMAPPED -> blocks unless the entity is among its owners. '.none' never blocks.
        suspend fun survivorBlocks(canonical: String, v: String): Boolean {
            if (v in removed) return false
            return when (kind) {
                SavedKind.TRACK -> false
                SavedKind.ALBUM -> {
                    // An exhaustion-latched video never resolves; the genuine 'none' verdict is resolved-to-
                    // nothing. Both are safe as "cannot resurrect this entity".
                    if (albumUnresolvable(account, v)) false
                    else {
                        val mapped = store.cachedTrackAlbum(v)
                        when {
                            mapped == "none" -> false
                            mapped != null -> mapped == canonical
                            else -> {
                                val owners = ownerByVideo[v].orEmpty()
                                owners.isEmpty() || canonical in owners
                            }
                        }
                    }
                }
                SavedKind.ARTIST -> {
                    // Only the ARTIST latch is resolved-to-nothing here: the album 'none' verdict proves no
                    // ALBUM but not no CHANNEL, so it keeps the unknown-mapping behavior below.
                    if (artistUnresolvable(account, v)) false
                    else {
                        val m = cachedArtistIdOf(v)
                        when {
                            m == null -> {
                                val owners = ownerByVideo[v].orEmpty()
                                owners.isEmpty() || canonical in owners
                            }
                            else -> canonicalArtistId(m) == canonical
                        }
                    }
                }
            }
        }
        // Per-id verdict: DELETED (rows deleted, no survivor can resurrect) vs ABSENT (complete walk proves no row).
        // remove() gates ABSENT confirmations by account ownership so an unsave never erases another account's saves.
        val verdicts = ids.associateWith { id ->
            val canonical = if (kind == SavedKind.ARTIST) canonicalArtistId(id) else id
            if (kind == SavedKind.TRACK) {
                // The row IS the track id: deleted this session, or provably absent from a complete walk
                // (removed out-of-band; nothing left to resurrect).
                (id in removed) to (walkComplete && id !in walkedKeys)
            } else {
                val reps = listOfNotNull(repVideoOf(kind, id))
                // A rep-LESS id whose rows were removed as orphans is as deleted as a rep'd id: the orphan pass
                // removes exactly the rows resolving to a removed id, so confirm when own removed rows exist.
                val ownOrphansRemoved = orphanRemovedByOwner[canonical] ?: emptyList()
                // PER-ENTITY survivor scope: a surviving row blocks THIS entity only when it could be THIS
                // entity (survivorBlocks), not when an unrelated unmapped row exists elsewhere in the mirror.
                val deleted = !walkedKeys.any { survivorBlocks(canonical, it) } && (
                    reps.isNotEmpty() && reps.all { it in removed } ||
                        reps.isEmpty() && ownOrphansRemoved.isNotEmpty()
                    )
                // A mirror row can exist without a repKey (beyond the budget, synced from another device): its
                // mapping is unknown until a refresh resolves it, so only an EMPTY walked mirror proves no row.
                val absentOnFullWalk = walkComplete && (
                    reps.isEmpty() && walkedKeys.isEmpty() ||
                        reps.isNotEmpty() && reps.all { it !in walkedKeys } &&
                        walkedKeys.all { !survivorBlocks(canonical, it) }
                    )
                deleted to absentOnFullWalk
            }
        }
        return RemovalOutcome(
            confirmed = ids.filter { id -> val (d, a) = verdicts.getValue(id); d || a },
            deleted = ids.filter { id -> verdicts.getValue(id).first }.toHashSet(),
            walkBacked = true,
        )
    }

    /** Removes the ids from the cached saved set so offline reads match the write. */
    private suspend fun dropFromCache(kind: SavedKind, ids: List<String>) {
        val state = store.cachedAccountState() ?: return
        // Owner gate: only the snapshot the CURRENT context can claim may be mutated — a deletion from this
        // account's mirror proves nothing about another's. Logged in: identity-gated or pre-identity snapshot.
        val legacy = state.instance.isBlank() && state.username.isBlank()
        val session = sessionProvider()
        val ownable = if (session != null) {
            legacy ||
                (state.instance.trim().trimEnd('/') == session.instance.trim().trimEnd('/') &&
                    state.username == session.username)
        } else {
            if (legacy) true
            else {
                val configured = instanceSource.api() ?: return
                state.instance.trim().trimEnd('/') == configured.trim().trimEnd('/')
            }
        }
        if (!ownable) return
        val idSet = ids.toHashSet()
        val updated = when (kind) {
            SavedKind.TRACK -> state.copy(savedTracks = state.savedTracks.filterNot { it in idSet })
            SavedKind.ALBUM -> state.copy(savedAlbums = state.savedAlbums.filterNot { it in idSet })
            SavedKind.ARTIST -> state.copy(savedArtists = state.savedArtists.filterNot { canonicalArtistId(it) in idSet })
        }
        store.cacheAccountState(updated)
    }

    // ── write-through: locally created playlists ───────────────────────────────

    suspend fun mirrorCreatePlaylist(localId: String, name: String, videoIds: List<String>): String? {
        val account = sessionProvider() ?: return null
        // Only a mirror already owned by this account blocks creation: a previous account's binding must
        // not be reused for writes — re-create the mirror for the account logged in now.
        if (mirrorPlaylistId(account, localId) != null) return null
        return runCatching {
            val created = userPost(account, "/user/playlists/create", buildJsonObject {
                put("name", name)
            }.toString())
            val id = runCatching {
                json.parseToJsonElement(created.orEmpty()).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return@runCatching null
            // Bind BEFORE the initial add is checked: a failed add leaves a really-created EMPTY mirror;
            // unbound, it orphans there (never re-synced). The owned binding preserves identity for re-push.
            store.put("mirror.playlist:$localId", json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, id)))
            store.put("mirror.playlistName:$localId", JsonPrimitive(name))
            if (videoIds.isNotEmpty()) {
                val added = postConfirmed(userPost(account, "/user/playlists/add", buildJsonObject {
                    put("playlistId", id)
                    putJsonArray("videoIds") { videoIds.forEach { add(it) } }
                }.toString()))
                if (!added) return@runCatching null
            }
            id
        }.getOrNull()
    }

    /** Returns false when the mirror could not be brought to contain [videoIds]. */
    /** [Uuid] = owned, live remote. [None] = no remote (absent binding, foreign owner, or listing-PROVEN gone):
     * local-only success. [Unverifiable] = logged out / unreachable — fail; the remote may exist (retry). */
    private sealed interface MirrorTarget {
        data class Uuid(val account: PipedAccount, val playlistId: String) : MirrorTarget
        object None : MirrorTarget
        object Unverifiable : MirrorTarget
    }

    private suspend fun mirrorTargetOf(localId: String): MirrorTarget {
        val raw = store.get("mirror.playlist:$localId") ?: return MirrorTarget.None
        val stored = runCatching { json.decodeFromJsonElement<StoredPlaylistId>(raw) }.getOrNull()
        val account = sessionProvider() ?: return MirrorTarget.Unverifiable
        if (stored != null) {
            val sameInstance = stored.instance.trim().trimEnd('/') == account.instance.trim().trimEnd('/')
            val sameUser = stored.username == account.username
            // Foreign-owned binding: the remote copy belongs to another
            // account — this edit must not touch it.
            return if (sameInstance && sameUser) MirrorTarget.Uuid(account, stored.playlistId)
            else MirrorTarget.None
        }
        // Legacy bare-uuid binding: verify uuid-exact against the listing. An unreachable/unparseable listing
        // is NOT 'absent' (the mirror may exist; silently skipping would diverge it) — Unverifiable.
        val listing = runCatching { userGet(account, "/user/playlists") }.getOrNull()
        val parsed = listing?.let {
            runCatching { json.parseToJsonElement(it).jsonArray.map { o -> o.jsonObject } }.getOrNull()
        } ?: return MirrorTarget.Unverifiable
        val uuid = legacyOwnedUuid(account, "mirror.playlist:$localId", parsed)
        return if (uuid == null) MirrorTarget.None else MirrorTarget.Uuid(account, uuid)
    }

    suspend fun mirrorAddTracks(localId: String, videoIds: List<String>): Boolean = when (val t = mirrorTargetOf(localId)) {
        is MirrorTarget.Uuid -> {
            if (videoIds.isEmpty()) true
            else runCatching {
                val known = readPlaylistVideoIds(t.account, t.playlistId)?.toSet() ?: return@runCatching false
                val fresh = videoIds.filterNot { it in known }.distinct()
                if (fresh.isEmpty()) return@runCatching true
                postConfirmed(userPost(t.account, "/user/playlists/add", buildJsonObject {
                    put("playlistId", t.playlistId)
                    putJsonArray("videoIds") { fresh.forEach { add(it) } }
                }.toString()))
            }.getOrDefault(false)
        }
        MirrorTarget.None -> true
        MirrorTarget.Unverifiable -> false
    }

    /** Returns false when the mirror may still contain rows of [videoIds]. */
    suspend fun mirrorRemoveTracks(localId: String, videoIds: List<String>): Boolean = when (val t = mirrorTargetOf(localId)) {
        is MirrorTarget.Uuid -> {
            if (videoIds.isEmpty()) true
            else runCatching {
                val wanted = videoIds.toSet()
                val indexes = mutableListOf<Int>()
                var pages = 0
                var lastWalkRows = 0
                var lastPageConverted = 0
                val token = walkPlaylistPages(t.account, t.playlistId, pageLimit = null, fetch = { path -> userGet(t.account, path) }) { page, seen ->
                    pages++
                    lastWalkRows = page.relatedStreams.size
                    lastPageConverted = 0
                    page.relatedStreams.forEachIndexed { position, item ->
                        val video = videoIdOf(item.url)
                        if (video.isNotEmpty()) {
                            lastPageConverted++
                            if (video in wanted) indexes += seen + position
                        }
                    }
                    true
                }
                // Only a walk that READ a page and ENDED at a NULL token proves the mirror was fully seen;
                // DECODED BLANK (throttle), mid-walk FETCH FAILURE, and failed first fetch all leave it unproven.
                if (token != null || pages == 0 || (pages > 1 && lastWalkRows == 0)) return@runCatching false
                // Same converted-row doctrine as remoteRemoveRows / mirrorState: an all-dead walk is NOT a
                // proven end — reporting done would leave the dead page's rows unremoved while local bookkeeping dropped them.
                if (lastWalkRows > 0 && lastPageConverted == 0) return@runCatching false
                for (index in indexes.sortedDescending()) {
                    val removed = userPost(t.account, "/user/playlists/remove", buildJsonObject {
                        put("playlistId", t.playlistId)
                        put("index", index)
                    }.toString())
                    if (!postConfirmed(removed)) return@runCatching false
                }
                true
            }.getOrDefault(false)
        }
        MirrorTarget.None -> true
        MirrorTarget.Unverifiable -> false
    }

    /** Returns false when the remote rename did not take (non-2xx POST) or could not be verified; true for
     * local-only playlists (no owned remote copy exists — nothing to rename). */
    suspend fun mirrorRenamePlaylist(localId: String, name: String): Boolean = when (val t = mirrorTargetOf(localId)) {
        is MirrorTarget.Uuid -> runCatching {
            postConfirmed(userPost(t.account, "/user/playlists/rename", buildJsonObject {
                put("playlistId", t.playlistId)
                put("newName", name)
            }.toString()))
        }.getOrDefault(false)
        MirrorTarget.None -> true
        MirrorTarget.Unverifiable -> false
    }

    /** Deletes the remote mirror of [localId]; false when it could NOT be deleted (dead token, offline, 5xx)
     * — keep local + binding for retry; dropping the binding orphans a surviving remote. */
    suspend fun mirrorDeletePlaylist(localId: String): Boolean {
        val account = sessionProvider()
        if (account == null) {
            // Logged out: no session to delete/verify the remote. A live binding must not be dropped (the copy
            // orphans, re-appearing after login); keep it so the deletion completes once a session exists.
            if (store.get("mirror.playlist:$localId") != null) return false
            store.remove("mirror.playlist:$localId")
            return true
        }
        // Only delete the remote copy when this account owns it; a foreign
        // binding is dropped locally without touching the other account's data.
        val uuid = mirrorPlaylistId(account, localId)
            // Pre-upgrade bare-uuid binding: the string IS the remote uuid. Verify ownership uuid-exact against
            // THIS account's listing before deleting — a legacy mirror must not orphan on a pre-owner-gate binding.
            ?: legacyOwnedMirrorUuid(account, localId)
        if (uuid == null) {
            // No owned remote copy: drop only a binding whose absence was PROVEN — an unreachable listing is
            // 'cannot verify existence', NOT 'absent' (dropping would orphan a live mirror). Foreign ones drop anyway.
            val raw = store.get("mirror.playlist:$localId")
            val isLegacyBare = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() } != null
            if (isLegacyBare) {
                val listing = userGet(account, "/user/playlists")
                val parsed = listing?.let {
                    runCatching { json.parseToJsonElement(it).jsonArray.map { o -> o.jsonObject } }.getOrNull()
                }
                if (parsed == null) return false
                // Re-verify uuid-exact (also upgrades the binding when the
                // playlist somehow survives) — a live mirror is never dropped.
                if (legacyOwnedUuid(account, "mirror.playlist:$localId", parsed) != null) return false
            }
            store.remove("mirror.playlist:$localId")
            return true
        }
        val deleted = runCatching {
            userPost(account, "/user/playlists/delete", buildJsonObject {
                put("playlistId", uuid)
            }.toString())
        }.getOrNull()
        if (!postConfirmed(deleted)) {
            // The remote delete FAILED (userPost nulls non-2xx; postConfirmed rejects throttle shapes). If the
            // listing PROVES the uuid gone, complete the local removal; otherwise keep the fail-soft retry state.
            val listing = userGet(account, "/user/playlists")
            val parsed = listing?.let {
                runCatching { json.parseToJsonElement(it).jsonArray.map { o -> o.jsonObject } }.getOrNull()
            }
            val stillExists = parsed?.any { it["id"]?.jsonPrimitive?.contentOrNull == uuid } ?: true
            if (!stillExists) {
                store.remove("mirror.playlist:$localId")
                return true
            }
            return false
        }
        store.remove("mirror.playlist:$localId")
        return true
    }

    /** The remote uuid of a legacy bare-uuid binding, only if it still exists on [account]'s listing. */
    private suspend fun legacyOwnedMirrorUuid(account: PipedAccount, localId: String): String? {
        val listing = runCatching { userGet(account, "/user/playlists") }.getOrNull() ?: return null
        val parsed = runCatching { json.parseToJsonElement(listing).jsonArray.map { it.jsonObject } }
            .getOrNull() ?: return null
        return legacyOwnedUuid(account, "mirror.playlist:$localId", parsed)
    }

    /** Remote uuid of a pre-upgrade bare-uuid binding under [key], only if it still exists on [account]'s
     * listing (uuid-exact). Success upgrades the binding to an owned [StoredPlaylistId] — the normal path. */
    private suspend fun legacyOwnedUuid(account: PipedAccount, key: String, parsed: List<JsonObject>): String? {
        val raw = store.get(key)
        val legacy = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() } ?: return null
        if (parsed.none { it["id"]?.jsonPrimitive?.contentOrNull == legacy }) return null
        // Ownership verified uuid-exact against THIS account's listing: upgrade
        // the legacy binding so later operations take the normal owned path.
        store.put(key, json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, legacy)))
        return legacy
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Playlist uuid stored under [key], returned only when the entry is bound to [account] (instance + username). A
     * legacy bare-uuid entry has no owner and is treated as unbound so it is never reused for a different account. */
    private suspend fun ownedPlaylistId(account: PipedAccount, key: String): String? {
        val raw = store.get(key) ?: return null
        val stored = runCatching { json.decodeFromJsonElement<StoredPlaylistId>(raw) }.getOrNull()
        val sameInstance = stored != null && stored.instance.trim().trimEnd('/') == account.instance.trim().trimEnd('/')
        val sameUser = stored != null && stored.username == account.username
        return if (stored != null && sameInstance && sameUser) stored.playlistId else null
    }

    private suspend fun storedPlaylistId(account: PipedAccount, kind: SavedKind): String? =
        ownedPlaylistId(account, playlistKey(kind))

    /** The mirrored remote uuid of a locally created playlist, if owned by [account]. */
    private suspend fun mirrorPlaylistId(account: PipedAccount, localId: String): String? =
        ownedPlaylistId(account, "mirror.playlist:$localId")

    private suspend fun ensurePlaylist(account: PipedAccount, kind: SavedKind): String {
        // Verify BEFORE trusting a stored binding: an out-of-band deletion must not lock saves to a dead uuid
        // forever (nothing removes playlistKey(kind)) — a blindly-returned stale binding hides the rescue branches.
        val stored = storedPlaylistId(account, kind)
        val listing = userGet(account, "/user/playlists")
        val parsed = if (listing != null) {
            runCatching { json.parseToJsonElement(listing).jsonArray.map { it.jsonObject } }.getOrNull()
        } else null
        if (parsed == null) {
            // Unverifiable (network/5xx/token/parse): keep the last known binding — a TRANSIENT failure must
            // not block saves (a stale binding self-heals on the next listing). Never create a duplicate here.
            stored?.let { return it }
            throw IllegalStateException("Piped playlist listing unavailable")
        }
        // Live binding still present in the listing → use it as-is.
        if (stored != null && parsed.any { it["id"]?.jsonPrimitive?.contentOrNull == stored }) {
            return stored
        }
        // A renamed mirror is still the account's playlist: rescue the legacy bare-uuid binding uuid-exact
        // before adopting a same-named newcomer — with both present, the same-name one is a DIFFERENT playlist.
        legacyOwnedUuid(account, playlistKey(kind), parsed)?.let { return it }
        val existing = parsed.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == kind.playlistName }
            ?.get("id")?.jsonPrimitive?.contentOrNull
        if (existing != null) {
            store.put(playlistKey(kind), json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, existing)))
            return existing
        }
        val created = userPost(account, "/user/playlists/create", buildJsonObject { put("name", kind.playlistName) }.toString())
        val id = runCatching {
            json.parseToJsonElement(created.orEmpty()).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw IllegalStateException("Piped playlist create returned no id")
        store.put(playlistKey(kind), json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, id)))
        return id
    }

    /** All video ids of a playlist, walking to the end; null when the walk did NOT reach a trustworthy end
     * (failed first fetch, DECODED BLANK tail): a partial view must never drive dedupe/re-push — duplicate rows. */
    private suspend fun readPlaylistVideoIds(account: PipedAccount, playlistId: String): List<String>? {
        val ids = mutableListOf<String>()
        var pages = 0
        var lastRows = 0
        var lastPageConverted = 0
        val token = walkPlaylistPages(account, playlistId, pageLimit = null, fetch = { path -> userGet(account, path) }) { page, _ ->
            pages++
            lastRows = page.relatedStreams.size
            lastPageConverted = 0
            // Keep only REAL ids: videoIdOf returns "" for dead items; mapNotNull preserves it —
            // polluting defeats the all-dead guards (converted-empty looks non-empty; partial views re-push dupes).
            page.relatedStreams.forEach { item ->
                videoIdOf(item.url).takeIf { v -> v.isNotEmpty() }?.let { ids += it; lastPageConverted++ }
            }
            true
        }
        // Same completion rule as remoteRemoveRows: rows then a DECODED-EMPTY final page is the throttle shape,
        // not a proven end — deduping against this partial view re-pushes or under-deletes the mirror (no dedupe).
        if (token != null || pages == 0) return null
        if (pages > 1 && lastRows == 0) return null
        // Same converted-row doctrine as remoteRemoveRows/mirrorState: RAW rows but ZERO convertible ids on a
        // final page is NOT a proven end (dedupe appends permanent duplicates). One decoded zero-RAW page is.
        if (lastRows > 0 && lastPageConverted == 0) return null
        return ids
    }

    /** The uploader's resolved id from cached stream info only (no network). */
    private suspend fun cachedArtistIdOf(videoId: String): String? {
        val info = store.cachedStreams(videoId) ?: return null
        val channelId = channelIdOf(info.uploaderUrl)
        if (channelId.isNotEmpty()) return channelId
        return info.uploader.takeIf { it.isNotBlank() }?.let { "channel:${cleanArtistName(it)}" }
    }

    /** The uploader channel of a track's video, or a synthetic channel id. */
    private suspend fun artistChannelOf(account: PipedAccount, videoId: String): String? {
        val info = store.cachedStreams(videoId) ?: run {
            val body = sessionedGet(account.instance, "/streams/${videoId.percentEncoded()}") ?: return null
            // Same hardened decode as PipedClient.streams() (cr62): blank/key-missing bodies are throttle shapes,
            // never authoritative — the coerced blank poisons getTrack/AlbumLookup/resolveLocalTrack forever.
            if (body.isBlank()) return null
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
            val fetched = runCatching { json.decodeFromJsonElement<PipedStreamsInfo>(root) }.getOrNull() ?: return null
            store.cacheStreams(videoId, fetched)
            fetched
        }
        val channelId = channelIdOf(info.uploaderUrl)
        if (channelId.isNotEmpty()) return channelId
        val uploader = info.uploader.takeIf { it.isNotBlank() }?.let(::cleanArtistName)
        return uploader?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
    }

    /** One playable video representing a saved entity, not already used by another. */
    private suspend fun representativeVideo(account: PipedAccount, kind: SavedKind, id: String, used: Set<String>): String? {
        return when (kind) {
            // A track row IS the track id, so it can never collide with another entity; skipping it when
            // already mirrored would leave an already-present row without a repKey that a later unsave needs.
            SavedKind.TRACK -> id
            SavedKind.ALBUM -> firstVideoOfPlaylist(id, used)
            // this instance serves no channel uploads (relatedStreams empty), so fall
            // back to a music_songs search for the channel's name.
            SavedKind.ARTIST -> {
                // YouTube Music search tracks are reliably add-able; channel uploads often are not (full-album
                // / restricted uploads 500 in /streams and the instance refuses to add them).
                val name = channelNameFor(id)
                val viaSearch = name.takeIf { it.isNotBlank() }?.let { firstVideoOfSearch(it, used) }
                viaSearch ?: firstVideoOfArtist(id, used)
            }
        }
    }

    private suspend fun firstVideoOfPlaylist(playlistId: String, used: Set<String>): String? {
        val page = store.cachedAlbumPlaylist(playlistId) ?: run {
            val fetched = runCatching {
                decodePlaylistPage(
                    sessionedGet(instanceSource.requireApi(), "/playlists/${playlistId.percentEncoded()}") ?: return null,
                )
            }.getOrNull() ?: return null
            store.cacheAlbumPlaylist(playlistId, fetched)
            fetched
        }
        // The ALBUM representative is a CORRECTNESS requirement, not a picker: a silent drop diverges the
        // device saved list forever. Probe EVERY page-1 row; drop dead rows before the picker (round-109).
        return firstFetchableOf(
            page.relatedStreams.mapNotNull { videoIdOf(it.url).takeIf { v -> v.isNotEmpty() } },
            used, probeLimit = page.relatedStreams.size,
        )
    }

    /** Shared hardened /channel decode (PipedClient.channel's guard): blank or key-missing bodies are a
     * FAILED fetch — the coerced blank would be hoarded forever (CHANNEL_KEY never invalidated). */
    private fun decodeChannel(body: String): PipedChannelInfo? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
        return runCatching { json.decodeFromJsonElement<PipedChannelInfo>(root) }.getOrNull()
    }

    private suspend fun firstVideoOfArtist(channelId: String, used: Set<String>): String? {
        // Same cached-value trust as channelNameFor: a cached channel with a blank name is a MISS and
        // re-fetched — CHANNEL_KEY is never invalidated, so trusting it fails artist saves forever.
        val info = store.cachedChannel(channelId)?.takeIf { it.name.isNotBlank() } ?: run {
            val fetched = decodeChannel(
                sessionedGet(instanceSource.requireApi(), "/channel/${channelId.percentEncoded()}") ?: return null,
            ) ?: return null
            store.cacheChannel(channelId, fetched)
            fetched
        }
        // The ARTIST representative is a CORRECTNESS requirement like the album twin (save() commits the
        // library first, then throws on null rep). Probe EVERY page-1 row (round-102); drop dead rows first (109).
        return firstFetchableOf(
            info.relatedStreams.mapNotNull { videoIdOf(it.url).takeIf { v -> v.isNotEmpty() } },
            used,
            probeLimit = info.relatedStreams.size,
        )
    }

    /** First candidate the instance can resolve (/streams 200), up to [probeLimit] tries. The default 6 is a
     * PICKER budget; album/artist reps pass their full page-1 size — the probe is a correctness need there. */
    private suspend fun firstFetchableOf(candidates: List<String>, used: Set<String>, probeLimit: Int = 6): String? {
        val head = candidates.take(probeLimit)
        // Prefer a candidate not yet used (each entity owns a distinct row). When EVERY candidate is already a
        // sibling's rep, SHARE the first fetchable one — remoteRemoveRows re-points shared survivors later.
        for (vid in head) {
            if (vid !in used && fetchable(vid)) return vid
        }
        for (vid in head) {
            if (vid in used && fetchable(vid)) return vid
        }
        return null
    }

    /** Shared hardened /streams fetch for the representative PROBE (PipedClient.streams() doctrine): consult
     * the cache first; blank or key-missing bodies are FAILED fetches (null) — cache only key-present pages. */
    private suspend fun streamInfoOf(videoId: String): PipedStreamsInfo? =
        store.cachedStreams(videoId) ?: run {
            val body = sessionedGet(instanceSource.requireApi(), "/streams/${videoId.percentEncoded()}") ?: return null
            if (body.isBlank()) return null
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
            val fetched = runCatching { json.decodeFromJsonElement<PipedStreamsInfo>(root) }.getOrNull() ?: return null
            store.cacheStreams(videoId, fetched)
            fetched
        }

    private suspend fun fetchable(vid: String): Boolean = streamInfoOf(vid)?.title?.isNotBlank() == true

    private suspend fun channelNameFor(id: String): String {
        if (id.startsWith("channel:")) return id.removePrefix("channel:")
        store.cachedChannel(id)?.let { if (it.name.isNotBlank()) return it.name }
        val body = sessionedGet(instanceSource.requireApi(), "/channel/${id.percentEncoded()}")
        // Hardened decode again: a '{}' throttle body must not be cached as a blank channel (the name check
        // would force a refetch, but the blank would have poisoned the never-invalidated cache in between).
        val fetched = if (body == null) null else decodeChannel(body)
        if (fetched != null) store.cacheChannel(id, fetched)
        return fetched?.name?.takeIf { it.isNotBlank() } ?: id
    }

    private suspend fun firstVideoOfSearch(query: String, used: Set<String>): String? {
        // Hardened decode like PipedClient.search()/decodeChannel: blank/key-missing bodies are throttle shapes,
        // never a zero-hit — the coerced empty page silently drops the artist save (library already committed).
        val body = sessionedGet(
            instanceSource.requireApi(),
            "/search?filter=music_songs&q=${query.percentEncoded()}",
        ) ?: return null
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["items"] !is JsonArray) return null
        val page = runCatching { json.decodeFromJsonElement<PipedSearchPage>(root) }.getOrNull() ?: return null
        // Same shared picker and budget as firstFetchableOf (the album rep passes its full page-1 size; the
        // default 6 caps search fallbacks): one implementation so artist and album never drift.
        return firstFetchableOf(
            page.items.mapNotNull { videoIdOf(it.url).takeIf { v -> v.isNotEmpty() } },
            used,
        )
    }

    private suspend fun sessionedGet(instance: String, path: String): String? {
        // A transport failure (timeout, DNS) is a failed fetch, like a non-2xx status.
        val response = orNull {
            httpClient.request(
                method = HttpMethod.Get,
                url = instance.trimEnd('/') + path,
                requestHeaders = mapOf("Accept" to "application/json"),
                body = null,
            )
        } ?: return null
        return if (response.statusCode in 200..299) response.body else null
    }

    private suspend fun userGet(account: PipedAccount, path: String): String? {
        val response = orNull {
            httpClient.request(
                method = HttpMethod.Get,
                url = account.instance.trimEnd('/') + path,
                requestHeaders = mapOf("Accept" to "application/json", "Authorization" to account.token),
                body = null,
            )
        } ?: return null
        return if (response.statusCode in 200..299) response.body else null
    }

    /** A mutation POST confirms only on Piped's genuine success shape: throttles/errors come back as 200 +
     * '{}'/'{"error":…}'; counting '{}' as success drops bookkeeping for a row that never moved. */
    private fun postConfirmed(body: String?): Boolean {
        if (body == null) return false
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
        return obj.isNotEmpty() && obj["error"] == null
    }

    private suspend fun userPost(account: PipedAccount, path: String, body: String): String? {
        val response = httpClient.request(
            method = HttpMethod.Post,
            url = account.instance.trimEnd('/') + path,
            requestHeaders = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to account.token,
            ),
            body = body,
        )
        return if (response.statusCode in 200..299) response.body else null
    }
}