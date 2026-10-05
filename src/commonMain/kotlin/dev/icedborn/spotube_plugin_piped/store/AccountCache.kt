package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.WalkResult
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.listedCount
import dev.icedborn.spotube_plugin_piped.client.string
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.mapConcurrently
import dev.icedborn.spotube_plugin_piped.core.normalizeInstance
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** How often the account cache is refreshed while the app is used. */
internal const val ACCOUNT_REFRESH_TTL_MS = 15 * 60_000L

/** Mirror pages one refresh reads; a longer mirror continues on the next refresh. */
private const val MIRROR_PAGE_LIMIT = 20

/** A mirror walk cut by the page limit, continued by the next refresh from [token]. */
@Serializable
private data class PartialWalk(val playlistId: String, val token: String, val rawIds: List<String>, val pages: Int)

/** A playlist from the account listing, with the saved kind it mirrors, if any. */
private data class ListedPlaylist(val entry: JsonObject, val uuid: String, val kind: SavedKind?)

/** The account snapshot: playlists and saved sets, served locally and refreshed from the instance. */
internal class AccountCache(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val instanceSource: InstanceSource,
    private val bindings: SavedBindings,
    private val rows: AccountRows,
    private val playlistMirror: PlaylistMirror,
    private val resolver: SavedSetResolver,
    private val historySync: HistorySync,
    private val refreshScope: CoroutineScope,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    private val refreshMutex = Mutex()

    // One background resolution of album and artist sets at a time; only the newest queued one runs.
    private val resolveMutex = Mutex()
    private var resolveTicket = 0

    /** The snapshot of the signed-in account. Signed out, the last snapshot is served only for the instance it
     * came from, so switching instances hides it. */
    suspend fun cachedState(): AccountCacheState? {
        val state = store.cachedAccountState() ?: return null
        val session = sessionProvider()
        if (session != null) return state.takeIf { sameAccount(it, session) }
        val configured = instanceSource.api() ?: return null
        return state.takeIf { normalizeInstance(it.instance) == normalizeInstance(configured) }
    }

    suspend fun stateOrEmpty(): AccountCacheState = cachedState() ?: AccountCacheState()

    suspend fun accountPlaylist(id: String): CachedAccountPlaylist? = cachedState()?.playlists?.firstOrNull { it.id == id }

    /** The ids of [kind] in this account's snapshot. */
    suspend fun ownedIds(kind: SavedKind): List<String> {
        val state = stateOrEmpty()
        return when (kind) {
            SavedKind.TRACK -> state.savedTracks
            SavedKind.ALBUM -> state.savedAlbums
            SavedKind.ARTIST -> state.savedArtists.map(::canonicalArtistId)
        }
    }

    /** Device saves and snapshot saves of [kind] together. */
    suspend fun allSavedIds(kind: SavedKind): List<String> {
        val local = when (kind) {
            SavedKind.TRACK -> library.savedTracks()
            SavedKind.ALBUM -> library.savedAlbums()
            SavedKind.ARTIST -> library.savedArtists().map(::canonicalArtistId)
        }
        return (local + ownedIds(kind)).distinct()
    }

    suspend fun isSaved(kind: SavedKind, ids: List<String>): List<Boolean> {
        val saved = allSavedIds(kind).toHashSet()
        return ids.map { kind.canonical(it) in saved }
    }

    /** Adds [ids] to the snapshot when it belongs to [account], so an unsave sees them before the next refresh. */
    suspend fun addToSnapshot(account: PipedAccount, kind: SavedKind, ids: List<String>) {
        val state = store.cachedAccountState()?.takeIf { sameAccount(it, account) } ?: return
        store.cacheAccountState(state.withSaved(kind) { (it + ids.map(kind::canonical)).distinct() })
    }

    /** Removes [ids] from this account's snapshot so offline reads match the write. */
    suspend fun dropFromSnapshot(kind: SavedKind, ids: List<String>) {
        val state = cachedState() ?: return
        val idSet = ids.toHashSet()
        store.cacheAccountState(state.withSaved(kind) { saved -> saved.filterNot { kind.canonical(it) in idSet } })
    }

    private fun AccountCacheState.withSaved(kind: SavedKind, change: (List<String>) -> List<String>) = when (kind) {
        SavedKind.TRACK -> copy(savedTracks = change(savedTracks))
        SavedKind.ALBUM -> copy(savedAlbums = change(savedAlbums))
        SavedKind.ARTIST -> copy(savedArtists = change(savedArtists))
    }

    /** Refreshes when signed in, the TTL has passed and no refresh runs. With a snapshot to serve, the refresh
     * runs in the background so library tabs do not wait for a full account walk. */
    suspend fun maybeRefresh() {
        if (sessionProvider() == null || !needsRefresh()) return
        if (cachedState() == null) return refreshIfStale()
        if (!refreshMutex.isLocked) refreshScope.launch { refreshIfStale() }
    }

    private suspend fun refreshIfStale() {
        refreshMutex.withLock {
            if (!needsRefresh()) return@withLock
            val account = sessionProvider() ?: return@withLock
            orNull("account refresh") { refresh(account) }
        }
    }

    /** Forced refresh at login and plugin start. A failure keeps the previous snapshot. */
    suspend fun refreshCache() {
        val account = sessionProvider() ?: return
        refreshMutex.withLock { orNull("account refresh") { refresh(account) } }
    }

    private suspend fun needsRefresh(): Boolean {
        val state = cachedState() ?: return true
        return epochMillis() - state.refreshedAt >= ACCOUNT_REFRESH_TTL_MS
    }

    private suspend fun refresh(account: PipedAccount) {
        val entries = http.listing(account) ?: return
        // Bindings a start without a listing could not adopt get their chance on every refresh.
        val localIds = library.storedPlaylists().map { it.id }
        store.adoptBareBindings(account, entries.mapNotNull { it.string("id") }.toHashSet(), bindingKeys(localIds))
        // Only this account's snapshot is a baseline; another account's ids are never bound.
        val previous = store.cachedAccountState()?.takeIf { sameAccount(it, account) }
        val since = SavedKind.entries.associateWith { bindings.generation(it) }
        val historySince = historySync.generation
        val historyId = historySync.playlistIn(account, entries)
        val walks = attribute(account, entries).mapConcurrently(3) { listed ->
            val kind = listed.kind
            val walk = when {
                listed.uuid == historyId -> http.walk(account, listed.uuid, pageLimit = MIRROR_PAGE_LIMIT)
                kind != null -> walkMirror(account, kind, listed.uuid, since.getValue(kind))
                else -> http.walk(account, listed.uuid)
            }
            // Log rows are not cached as tracks. A merge copies the ones it needs into the history.
            if (listed.uuid != historyId) store.rememberTracks(walk.rows)
            listed to walk
        }
        // A switch to another account during the walks leaves its bindings and snapshot alone.
        if (!stillSignedIn(account)) return
        val playlists = mutableListOf<CachedAccountPlaylist>()
        val mirrorIds = mutableMapOf<SavedKind, List<String>>()
        // Per kind: true for a proven end, false for an ambiguous walk (the previous set is kept), absent for no mirror.
        val mirrorState = mutableMapOf<SavedKind, Boolean>()
        var historyWalk: WalkResult? = null
        for ((listed, walk) in walks) {
            // The history log stays out of the playlists, like the mirrors.
            if (listed.uuid == historyId) {
                historyWalk = walk.takeIf(::provenEnd)
                continue
            }
            val kind = listed.kind
            if (kind == null) {
                rows.cacheWalk(listed.uuid, walk, listedCount(listed.entry) ?: -1)
                playlists += CachedAccountPlaylist(listed.uuid, listed.entry.string("name").orEmpty(), listedCount(listed.entry) ?: -1)
                continue
            }
            mirrorIds[kind] = walk.rawIds.filter { it.isNotEmpty() }
            // A walk that overlapped a save or unsave is stale, so it counts as ambiguous. The lock can wait on
            // another account's write, so the session is checked again inside it.
            mirrorState[kind] = bindings.withMirrorLock(kind) {
                if (!stillSignedIn(account)) return@withMirrorLock null
                bindings.bindPlaylist(bindings.playlistKey(kind), account, listed.uuid)
                val proven = provenEnd(walk) && bindings.generation(kind) == since.getValue(kind)
                bindings.refreshIndex(account, kind, listed.uuid, walk.rawIds.takeIf { proven }, since.getValue(kind))
                proven
            } ?: return
        }
        if (!stillSignedIn(account)) return
        val start = snapshot(account, playlists, previous, mirrorIds[SavedKind.TRACK].orEmpty().distinct(), mirrorState[SavedKind.TRACK])
        if (stillSignedIn(account)) playlistMirror.retryPending(account)
        syncHistory(account, historyId, historyWalk, historySince)
        val ticket = ++resolveTicket
        refreshScope.launch {
            resolveMutex.withLock {
                // A newer refresh queued behind this one has fresher rows.
                if (ticket != resolveTicket) return@withLock
                orNull("saved set resolution") { resolver.resolve(account, start, previous, mirrorIds, mirrorState) }
            }
        }
    }

    /** Merges a history log walked to its end, then trims it and retries the waiting plays in the background. */
    private suspend fun syncHistory(account: PipedAccount, playlistId: String?, walk: WalkResult?, since: Int) {
        if (playlistId != null && walk != null && stillSignedIn(account)) {
            orNull("history merge") { historySync.merge(account, playlistId, walk, since) }
        }
        val excess = (walk?.rawIds?.size ?: 0) - HISTORY_LOG_CAP
        refreshScope.launch { orNull("history sync") { historySync.settle(account, playlistId, excess) } }
    }

    private suspend fun stillSignedIn(account: PipedAccount): Boolean =
        sessionProvider()?.let { sameAccount(it, account) && it.token == account.token } == true

    /** Maps listed playlists to saved kinds. The bound uuid wins; a playlist with the mirror's name is adopted
     * only when the kind has no live binding, so the saved set is never split across two playlists. */
    private suspend fun attribute(account: PipedAccount, entries: List<JsonObject>): List<ListedPlaylist> {
        val kindByUuid = mutableMapOf<String, SavedKind>()
        for (entry in entries) {
            val uuid = entry.string("id") ?: continue
            SavedKind.entries.firstOrNull { k -> k !in kindByUuid.values && bindings.storedPlaylistId(account, k) == uuid }
                ?.let { kindByUuid[uuid] = it }
        }
        return entries.mapNotNull { entry ->
            val uuid = entry.string("id") ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            val kind = kindByUuid[uuid] ?: SavedKind.entries.firstOrNull { k -> k.playlistName == name && k !in kindByUuid.values }
            if (kind != null) kindByUuid[uuid] = kind
            ListedPlaylist(entry, uuid, kind)
        }
    }

    /** Proven with converted rows on a final null-token page, or with a single empty page and a null token. */
    private fun provenEnd(walk: WalkResult): Boolean {
        if (walk.nextpage != null) return false
        val converted = walk.lastPageRows > 0 && walk.lastPageConverted > 0 && walk.rawIds.any { it.isNotEmpty() }
        return converted || (walk.rawIds.isEmpty() && walk.lastPageRows == 0 && walk.pages == 1)
    }

    /** Writes the new snapshot. Saves and unsaves made during the walk survive: tracks merge like the album and
     * artist sets, which keep their members until the background resolution. */
    private suspend fun snapshot(
        account: PipedAccount,
        playlists: List<CachedAccountPlaylist>,
        previous: AccountCacheState?,
        trackIds: List<String>,
        trackState: Boolean?,
    ): AccountCacheState {
        val current = store.cachedAccountState()?.takeIf { sameAccount(it, account) } ?: previous
        val before = previous?.savedTracks.orEmpty()
        val now = current?.savedTracks.orEmpty()
        val tracks = if (trackState == false) now else mergeSaved(trackIds, before, now)
        val state = AccountCacheState(
            playlists = playlists,
            savedTracks = tracks,
            savedAlbums = current?.savedAlbums.orEmpty(),
            savedArtists = current?.savedArtists.orEmpty(),
            refreshedAt = epochMillis(),
            instance = account.instance,
            username = account.username,
        )
        store.cacheAccountState(state)
        // Every saved track needs a bound row and an owner stamp so an unsave can find and claim it.
        val stamp = accountStamp(account)
        tracks.filter { it in trackIds }.forEach {
            bindings.bindRep(SavedKind.TRACK, it, it)
            bindings.addOwnerStamp(SavedKind.TRACK, it, stamp)
        }
        return state
    }

    /** Walks a mirror [MIRROR_PAGE_LIMIT] pages at a time. A longer mirror continues from the stored token on the
     * next refresh and counts as ambiguous until its walk ends. */
    private suspend fun walkMirror(account: PipedAccount, kind: SavedKind, uuid: String, since: Int): WalkResult {
        val key = bindings.walkKey(kind)
        val partial = store.getDecoded(key, PartialWalk.serializer())?.takeIf { it.playlistId == uuid }
        val walk = http.walk(account, uuid, pageLimit = MIRROR_PAGE_LIMIT, startToken = partial?.token)
        val rawIds = partial?.rawIds.orEmpty() + walk.rawIds
        val pages = (partial?.pages ?: 0) + walk.pages
        bindings.withMirrorLock(kind) {
            val unchanged = bindings.generation(kind) == since
            if (unchanged && walk.pages > 0 && !walk.nextpage.isNullOrBlank()) {
                store.put(key, json.encodeToJsonElement(PartialWalk(uuid, walk.nextpage, rawIds, pages)))
            } else {
                store.remove(key)
            }
        }
        return walk.copy(rawIds = rawIds, pages = if (walk.pages == 0) 0 else pages)
    }
}
