package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.WalkResult
import dev.icedborn.spotube_plugin_piped.client.decodePlaylistPage
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.percentEncoded
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.encodeToJsonElement

/** Row caches of the account's own playlists. */
internal class AccountRows(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val background: CoroutineScope,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    /** Rows of an account playlist, extended through the nextpage token when [minRows] asks beyond the cache. */
    suspend fun rowsFor(uuid: String, minRows: Int = 0): CachedRows {
        val account = sessionProvider()
        val base = "/playlists/${uuid.percentEncoded()}"
        val cache = RowCache(
            store = store,
            key = PLAYLIST_ROWS_PREFIX + uuid,
            fetchFirst = { account?.let { decodePlaylistPage(http.userGet(it, base)) } },
            fetchNext = { token ->
                account?.let { decodePlaylistPage(http.userGet(it, "/nextpage$base?nextpage=${token.percentEncoded()}")) }
            },
            convert = { item, _ -> item.toTrack() },
            background = background,
        )
        return cache.rows(minRows)
    }

    /** Stores a refresh walk as the row cache of an account playlist. A walk without evidence keeps the cache,
     * so an empty page 1 blanks it only when the account listing reports no rows either. */
    suspend fun cacheWalk(uuid: String, walk: WalkResult, listedTotal: Int) {
        val provenEmpty = walk.rows.isEmpty() && walk.lastPageRows == 0 && walk.pages == 1 &&
            walk.nextpage == null && listedTotal == 0
        if (walk.rows.isEmpty() && !provenEmpty) return
        val record = CachedRows(
            tracks = walk.rows,
            nextpage = walk.nextpage,
            complete = walk.nextpage == null && walk.lastPageConverted > 0,
            rawCount = walk.rawIds.size,
            lastVerifiedAt = epochMillis(),
            listedTotal = listedTotal,
        )
        val key = PLAYLIST_ROWS_PREFIX + uuid
        store.withRowsLock(key) { store.put(key, json.encodeToJsonElement(record)) }
    }
}
