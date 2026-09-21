package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
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
private const val ACCOUNT_RESOLVE_LIMIT = 40
private const val ACCOUNT_DERIVED_PREFIX = "piped-acct-"

/** The three playlists saved items are written through to (mirrors the brainz plugin pattern). */
internal enum class SavedKind(val playlistName: String) {
    ALBUM("Spotube - Albums"),
    ARTIST("Spotube - Artists"),
    TRACK("Spotube - Favorites"),
}

/**
 * Account cache + write-through. Reads of account data (playlists, saved
 * albums/artists/tracks) serve the locally cached snapshot, refreshed from the
 * instance when online (login, plugin start, or a read after the TTL). Saves
 * write through to the account when online, and the local library stays the
 * source of truth; nothing is materialized into the app's library, so there is
 * no mirror to converge.
 */
internal class PipedSavedLibrary(
    private val httpClient: HttpClientAPI,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val albumLookup: AlbumLookup,
    private val instanceSource: InstanceSource,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    private val refreshMutex = Mutex()

    private fun playlistKey(kind: SavedKind) = "saved.playlist:${kind.name.lowercase()}"
    private fun repKey(kind: SavedKind, entityId: String) = "saved.rep:${kind.name.lowercase()}:$entityId"

    // ── cache reads ────────────────────────────────────────────────────────────

    suspend fun cachedState(): AccountCacheState? = store.cachedAccountState()

    suspend fun accountPlaylist(id: String): CachedAccountPlaylist? =
        cachedState()?.playlists?.firstOrNull { it.id == id }

    private suspend fun stateOrEmpty(): AccountCacheState = cachedState() ?: AccountCacheState()

    suspend fun allSavedTrackIds(): List<String> =
        (library.savedTracks() + stateOrEmpty().savedTracks).distinct()

    suspend fun allSavedAlbumIds(): List<String> =
        (library.savedAlbums() + stateOrEmpty().savedAlbums).distinct()

    suspend fun allSavedArtistIds(): List<String> =
        (library.savedArtists() + stateOrEmpty().savedArtists).distinct()

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
        val local = library.savedArtists().toHashSet()
        val cached = stateOrEmpty().savedArtists.toHashSet()
        return ids.map { it in local || it in cached }
    }

    // ── cache refresh ──────────────────────────────────────────────────────────

    /** Refresh when a session exists, the TTL elapsed, and no refresh is running. Fail-soft. */
    suspend fun maybeRefresh() {
        if (sessionProvider() == null) return
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

        val previous = cachedState()
        val playlists = mutableListOf<CachedAccountPlaylist>()
        val repPages = mutableMapOf<SavedKind, List<MetadataTrack>>()
        var resolutions = 0

        // Walk every account playlist so its rows are cached for offline reads.
        entries.forEach { obj ->
            val uuid = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val count = obj["videos"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            val (_, rows, complete) = walkPlaylist(account, uuid)
            val kind = SavedKind.entries.firstOrNull { it.playlistName == name }
            if (kind != null) {
                repPages[kind] = rows
                // The mirror playlists themselves stay hidden; the saved sets below
                // are what surface in the app.
            } else {
                cacheRows(uuid, rows, complete)
                playlists += CachedAccountPlaylist(uuid, name, count)
            }
        }

        val savedAlbums = mutableListOf<String>()
        val savedArtists = mutableListOf<String>()
        for (kind in listOf(SavedKind.ALBUM, SavedKind.ARTIST)) {
            val rows = repPages[kind] ?: continue
            for (item in rows) {
                if (resolutions >= ACCOUNT_RESOLVE_LIMIT) break
                val videoId = item.id
                if (videoId.isEmpty()) continue
                when (kind) {
                    SavedKind.ALBUM -> {
                        store.cachedTrackAlbum(videoId)?.let { cached ->
                            if (cached != "none") savedAlbums += cached
                            return@let
                        }
                        val resolved = albumLookup.resolveAlbumForVideo(videoId)
                        if (resolved != null) {
                            store.cacheTrackAlbum(videoId, resolved)
                            store.put(repKey(kind, resolved), JsonPrimitive(videoId))
                            savedAlbums += resolved
                            resolutions++
                        } else {
                            store.cacheTrackAlbum(videoId, "none")
                        }
                    }
                    SavedKind.ARTIST -> {
                        val known = previous?.savedArtists
                            ?.firstOrNull { artistId -> store.get(repKey(kind, artistId))?.jsonPrimitive?.contentOrNull == videoId }
                        val channelId = known ?: artistChannelOf(account, videoId)?.also {
                            store.put(repKey(kind, it), JsonPrimitive(videoId))
                            resolutions++
                        }
                        if (channelId != null) savedArtists += channelId
                    }
                    SavedKind.TRACK -> Unit
                }
            }
        }
        val savedTracks = repPages[SavedKind.TRACK].orEmpty().map { it.id }.distinct()

        store.cacheAccountState(
            AccountCacheState(
                playlists = playlists,
                savedTracks = savedTracks,
                savedAlbums = savedAlbums.distinct(),
                savedArtists = savedArtists.distinct(),
                refreshedAt = epochMillis(),
            ),
        )

        // The old mirror pulled account playlists into the local library as
        // "piped-acct-*" records; they are served from the cache now.
        library.storedPlaylists().filter { it.id.startsWith(ACCOUNT_DERIVED_PREFIX) }.forEach { record ->
            library.deleteStoredPlaylist(record.id)
            library.removePlaylists(listOf(record.id))
        }
    }

    /** Full walk of an account playlist: ids, cached rows, and whether the end was reached. */
    private suspend fun walkPlaylist(
        account: PipedAccount,
        playlistId: String,
    ): Triple<List<String>, List<MetadataTrack>, Boolean> {
        val ids = mutableListOf<String>()
        val rows = mutableListOf<MetadataTrack>()
        var nextpage: String? = null
        var pages = 0
        do {
            val path = if (nextpage.isNullOrBlank()) "/playlists/${playlistId.percentEncoded()}"
            else "/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"
            val body = sessionedGet(account.instance, path) ?: break
            val page = runCatching { json.decodeFromString<PipedPlaylistPage>(body) }.getOrNull() ?: break
            page.relatedStreams.forEach { item ->
                videoIdOf(item.url).takeIf { it.isNotEmpty() }?.let { ids += it }
                item.toTrack()?.let { track ->
                    rows += track
                    store.rememberTrack(track)
                }
            }
            nextpage = page.nextpage
            pages++
        } while (nextpage != null && pages < ACCOUNT_PAGE_LIMIT)
        return Triple(ids, rows, nextpage == null)
    }

    private suspend fun cacheRows(uuid: String, rows: List<MetadataTrack>, complete: Boolean = true) {
        val record = CachedRows(tracks = rows, nextpage = null, complete = complete)
        store.put(PLAYLIST_ROWS_PREFIX + uuid, json.encodeToJsonElement(record))
    }

    /**
     * Rows of an account playlist: cached copy when present, otherwise a live
     * bounded walk that is cached for later (and for offline use).
     */
    suspend fun rowsFor(uuid: String): CachedRows {
        val key = PLAYLIST_ROWS_PREFIX + uuid
        val cached = store.get(key)?.let { raw ->
            runCatching { json.decodeFromJsonElement<CachedRows>(raw) }.getOrNull()
        }
        if (cached != null) return cached
        val account = sessionProvider() ?: return CachedRows()
        val (_, rows, complete) = walkPlaylist(account, uuid)
        val record = CachedRows(tracks = rows, nextpage = null, complete = complete)
        store.put(key, json.encodeToJsonElement(record))
        return record
    }

    // ── write-through: saved albums/artists/tracks ─────────────────────────────

    suspend fun save(kind: SavedKind, ids: List<String>) {
        val account = sessionProvider() ?: return
        if (ids.isEmpty()) return
        runCatching {
            val playlistId = ensurePlaylist(account, kind)
            val reps = ids.mapNotNull { id -> representativeVideo(account, kind, id)?.also { store.put(repKey(kind, id), JsonPrimitive(it)) } }
                .distinct()
            // the instance re-adds videos that are already in the playlist, so only
            // push reps that are not there yet.
            val fresh = reps.filterNot { it in readPlaylistVideoIds(account, playlistId).toSet() }
            if (fresh.isNotEmpty()) {
                userPost(account, "/user/playlists/add", buildJsonObject {
                    put("playlistId", playlistId)
                    putJsonArray("videoIds") { fresh.forEach { add(it) } }
                }.toString())
            }
        }
    }

    suspend fun remove(kind: SavedKind, ids: List<String>) {
        val account = sessionProvider() ?: return
        if (ids.isEmpty()) return
        runCatching {
            val playlistId = storedPlaylistId(kind) ?: return
            val rows = userGet(account, "/playlists/$playlistId") ?: return
            val page = runCatching { json.decodeFromString<PipedPlaylistPage>(rows) }.getOrNull() ?: return
            val wanted = ids.mapNotNull { id -> store.get(repKey(kind, id))?.jsonPrimitive?.contentOrNull }.toHashSet()
            if (wanted.isEmpty()) return
            val indexes = page.relatedStreams.mapIndexedNotNull { index, item ->
                val videoId = videoIdOf(item.url)
                if (videoId.isNotEmpty() && videoId in wanted) index else null
            }
            for (index in indexes.sortedDescending()) {
                userPost(account, "/user/playlists/remove", buildJsonObject {
                    put("playlistId", playlistId)
                    put("index", index)
                }.toString())
            }
            ids.forEach { id -> store.remove(repKey(kind, id)) }
            dropFromCache(kind, ids)
        }
    }

    /** Removes the ids from the cached saved set so offline reads match the write. */
    private suspend fun dropFromCache(kind: SavedKind, ids: List<String>) {
        val state = cachedState() ?: return
        val idSet = ids.toHashSet()
        val updated = when (kind) {
            SavedKind.TRACK -> state.copy(savedTracks = state.savedTracks.filterNot { it in idSet })
            SavedKind.ALBUM -> state.copy(savedAlbums = state.savedAlbums.filterNot { it in idSet })
            SavedKind.ARTIST -> state.copy(savedArtists = state.savedArtists.filterNot { it in idSet })
        }
        store.cacheAccountState(updated)
    }

    // ── write-through: locally created playlists ───────────────────────────────

    suspend fun mirrorCreatePlaylist(localId: String, name: String, videoIds: List<String>): String? {
        val account = sessionProvider() ?: return null
        if (store.get("mirror.playlist:$localId") != null) return null
        return runCatching {
            val created = userPost(account, "/user/playlists/create", buildJsonObject {
                put("name", name)
            }.toString())
            val id = runCatching {
                json.parseToJsonElement(created.orEmpty()).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return@runCatching null
            if (videoIds.isNotEmpty()) {
                userPost(account, "/user/playlists/add", buildJsonObject {
                    put("playlistId", id)
                    putJsonArray("videoIds") { videoIds.forEach { add(it) } }
                }.toString())
            }
            store.put("mirror.playlist:$localId", JsonPrimitive(id))
            store.put("mirror.playlistName:$localId", JsonPrimitive(name))
            id
        }.getOrNull()
    }

    suspend fun mirrorAddTracks(localId: String, videoIds: List<String>) {
        val account = sessionProvider() ?: return
        val uuid = store.get("mirror.playlist:$localId")?.jsonPrimitive?.contentOrNull ?: return
        if (videoIds.isEmpty()) return
        runCatching {
            val known = readPlaylistVideoIds(account, uuid).toSet()
            val fresh = videoIds.filterNot { it in known }.distinct()
            if (fresh.isNotEmpty()) {
                userPost(account, "/user/playlists/add", buildJsonObject {
                    put("playlistId", uuid)
                    putJsonArray("videoIds") { fresh.forEach { add(it) } }
                }.toString())
            }
        }
    }

    suspend fun mirrorRemoveTracks(localId: String, videoIds: List<String>) {
        val account = sessionProvider() ?: return
        val uuid = store.get("mirror.playlist:$localId")?.jsonPrimitive?.contentOrNull ?: return
        if (videoIds.isEmpty()) return
        runCatching {
            val wanted = videoIds.toSet()
            val indexes = mutableListOf<Int>()
            var nextpage: String? = null
            var seen = 0
            var pages = 0
            do {
                val path = if (nextpage.isNullOrBlank()) "/playlists/${uuid.percentEncoded()}"
                else "/nextpage/playlists/${uuid.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"
                val body = sessionedGet(account.instance, path) ?: break
                val page = runCatching { json.decodeFromString<PipedPlaylistPage>(body) }.getOrNull() ?: break
                page.relatedStreams.forEachIndexed { position, item ->
                    val video = videoIdOf(item.url)
                    if (video.isNotEmpty() && video in wanted) indexes += seen + position
                }
                seen += page.relatedStreams.size
                nextpage = page.nextpage
                pages++
            } while (nextpage != null && pages < 5)
            for (index in indexes.sortedDescending()) {
                userPost(account, "/user/playlists/remove", buildJsonObject {
                    put("playlistId", uuid)
                    put("index", index)
                }.toString())
            }
        }
    }

    suspend fun mirrorRenamePlaylist(localId: String, name: String) {
        val account = sessionProvider() ?: return
        val uuid = store.get("mirror.playlist:$localId")?.jsonPrimitive?.contentOrNull ?: return
        runCatching {
            userPost(account, "/user/playlists/rename", buildJsonObject {
                put("playlistId", uuid)
                put("newName", name)
            }.toString())
        }
    }

    suspend fun mirrorDeletePlaylist(localId: String) {
        val account = sessionProvider() ?: return
        val uuid = store.get("mirror.playlist:$localId")?.jsonPrimitive?.contentOrNull ?: return
        runCatching {
            userPost(account, "/user/playlists/delete", buildJsonObject {
                put("playlistId", uuid)
            }.toString())
        }
        store.remove("mirror.playlist:$localId")
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private suspend fun storedPlaylistId(kind: SavedKind): String? =
        store.get(playlistKey(kind))?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private suspend fun ensurePlaylist(account: PipedAccount, kind: SavedKind): String {
        storedPlaylistId(kind)?.let { return it }
        val listing = userGet(account, "/user/playlists")
        if (listing != null) {
            val existing = runCatching {
                json.parseToJsonElement(listing).jsonArray.map { it.jsonObject }
            }.getOrNull()?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == kind.playlistName }
                ?.get("id")?.jsonPrimitive?.contentOrNull
            if (existing != null) {
                store.put(playlistKey(kind), JsonPrimitive(existing))
                return existing
            }
        }
        val created = userPost(account, "/user/playlists/create", buildJsonObject { put("name", kind.playlistName) }.toString())
        val id = runCatching {
            json.parseToJsonElement(created.orEmpty()).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw IllegalStateException("Piped playlist create returned no id")
        store.put(playlistKey(kind), JsonPrimitive(id))
        return id
    }

    /** All video ids of a playlist, walking continuation pages (bounded). */
    private suspend fun readPlaylistVideoIds(account: PipedAccount, playlistId: String): List<String> {
        val ids = mutableListOf<String>()
        var nextpage: String? = null
        var pages = 0
        do {
            val path = if (nextpage.isNullOrBlank()) "/playlists/${playlistId.percentEncoded()}"
            else "/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"
            val body = sessionedGet(account.instance, path) ?: break
            val page = runCatching { json.decodeFromString<PipedPlaylistPage>(body) }.getOrNull() ?: break
            ids += page.relatedStreams.mapNotNull { videoIdOf(it.url) }
            nextpage = page.nextpage
            pages++
        } while (nextpage != null && pages < 5)
        return ids
    }

    /** The uploader channel of a track's video, or a synthetic channel id. */
    private suspend fun artistChannelOf(account: PipedAccount, videoId: String): String? {
        val info = store.cachedStreams(videoId) ?: run {
            val body = sessionedGet(account.instance, "/streams/${videoId.percentEncoded()}") ?: return null
            val fetched = runCatching { json.decodeFromString<PipedStreamsInfo>(body) }.getOrNull() ?: return null
            store.cacheStreams(videoId, fetched)
            fetched
        }
        val channelId = channelIdOf(info.uploaderUrl)
        if (channelId.isNotEmpty()) return channelId
        val uploader = info.uploader.takeIf { it.isNotBlank() }?.let(::cleanArtistName)
        return uploader?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
    }

    /** One playable video representing a saved entity. */
    private suspend fun representativeVideo(account: PipedAccount, kind: SavedKind, id: String): String? {
        return when (kind) {
            SavedKind.TRACK -> id
            SavedKind.ALBUM -> firstVideoOfPlaylist(id)
            // this instance serves no channel uploads (relatedStreams empty), so fall
            // back to a music_songs search for the channel's name.
            SavedKind.ARTIST -> {
                // YouTube Music search tracks are reliably add-able; channel
                // uploads often are not (full-album/restricted uploads fetch
                // 500 in /streams and the instance silently refuses to add them).
                val name = channelNameFor(id)
                val viaSearch = name.takeIf { it.isNotBlank() }?.let { firstVideoOfSearch(it) }
                viaSearch ?: firstVideoOfArtist(id)
            }
        }
    }

    private suspend fun firstVideoOfPlaylist(playlistId: String): String? {
        val page = store.cachedAlbumPlaylist(playlistId) ?: run {
            val fetched = runCatching {
                json.decodeFromString<PipedPlaylistPage>(
                    sessionedGet(instanceSource.requireApi(), "/playlists/${playlistId.percentEncoded()}") ?: return null,
                )
            }.getOrNull() ?: return null
            store.cacheAlbumPlaylist(playlistId, fetched)
            fetched
        }
        return firstFetchableOf(page.relatedStreams.mapNotNull { videoIdOf(it.url) })
    }

    private suspend fun firstVideoOfArtist(channelId: String): String? {
        val info = store.cachedChannel(channelId) ?: run {
            val fetched = runCatching {
                json.decodeFromString<PipedChannelInfo>(
                    sessionedGet(instanceSource.requireApi(), "/channel/${channelId.percentEncoded()}") ?: return null,
                )
            }.getOrNull() ?: return null
            store.cacheChannel(channelId, fetched)
            fetched
        }
        return firstFetchableOf(info.relatedStreams.mapNotNull { videoIdOf(it.url) })
    }

    /** First candidate the instance can actually resolve (/streams 200), up to 6 tries. */
    private suspend fun firstFetchableOf(candidates: List<String>): String? {
        for (vid in candidates.take(6)) {
            val ok = runCatching {
                val body = sessionedGet(instanceSource.requireApi(), "/streams/$vid") ?: return@runCatching false
                json.decodeFromString<PipedStreamsInfo>(body).title.isNotBlank()
            }.getOrDefault(false)
            if (ok) return vid
        }
        return null
    }

    private suspend fun channelNameFor(id: String): String {
        if (id.startsWith("channel:")) return id.removePrefix("channel:")
        store.cachedChannel(id)?.let { if (it.name.isNotBlank()) return it.name }
        val body = sessionedGet(instanceSource.requireApi(), "/channel/${id.percentEncoded()}")
        val fetched = if (body == null) null else runCatching {
            json.decodeFromString<PipedChannelInfo>(body)
        }.getOrNull()
        if (fetched != null) store.cacheChannel(id, fetched)
        return fetched?.name?.takeIf { it.isNotBlank() } ?: id
    }

    private suspend fun firstVideoOfSearch(query: String): String? {
        val page = runCatching {
            json.decodeFromString<PipedSearchPage>(
                sessionedGet(
                    instanceSource.requireApi(),
                    "/search?filter=music_songs&q=${query.percentEncoded()}",
                ) ?: return null,
            )
        }.getOrNull() ?: return null
        return page.items.firstNotNullOfOrNull { videoIdOf(it.url).takeIf { v -> v.isNotEmpty() } }
    }

    private suspend fun sessionedGet(instance: String, path: String): String? {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = instance.trimEnd('/') + path,
            requestHeaders = mapOf("Accept" to "application/json"),
            body = null,
        )
        return if (response.statusCode in 200..299) response.body else null
    }

    private suspend fun userGet(account: PipedAccount, path: String): String? {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = account.instance.trimEnd('/') + path,
            requestHeaders = mapOf("Accept" to "application/json", "Authorization" to account.token),
            body = null,
        )
        return if (response.statusCode in 200..299) response.body else null
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
