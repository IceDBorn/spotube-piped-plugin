package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.PipedPlaylistPage
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement

/** An open cache is re-anchored against page 1 at most this often. */
internal const val OPEN_ANCHOR_TTL_MS = 2 * 60_000L

/** A complete cache is re-anchored against page 1 at most this often. */
internal const val COMPLETE_ANCHOR_TTL_MS = 15 * 60_000L

/** Cached rows of one playlist or album under [key], kept by the rules in the [CachedRows] KDoc. A due anchor
 * runs in [background] when the cached rows cover the request, else before they are served. */
internal class RowCache(
    private val store: EntityStore,
    private val key: String,
    private val fetchFirst: suspend () -> PipedPlaylistPage?,
    private val fetchNext: suspend (token: String) -> PipedPlaylistPage?,
    // Converts a row at its raw 1-based page position; the position is the album track number.
    private val convert: (item: PipedSearchItem, position: Int) -> MetadataTrack?,
    private val background: CoroutineScope? = null,
) {

    /** The cached rows, extended until they hold [minRows] or the list ends. [seed] is a page 1 the caller
     * already has, and [seedFresh] says it was fetched moments ago, so it counts as an anchor. */
    suspend fun rows(minRows: Int, seed: PipedPlaylistPage? = null, seedFresh: Boolean = false): CachedRows = store.withRowsLock(key) {
        val stored = store.getDecoded(key, CachedRows.serializer())
        var rows = stored ?: seedRows(seed, seedFresh) ?: return@withRowsLock CachedRows()
        if (stored != null && anchorDue(rows)) {
            if (background != null && covers(rows, minRows)) {
                background.launch { anchorInBackground() }
            } else {
                rows = anchor(rows)
            }
        }
        rows = extend(rows, minRows)
        if (rows != stored) store.put(key, json.encodeToJsonElement(rows))
        rows
    }

    private fun covers(rows: CachedRows, minRows: Int) = rows.complete || rows.tracks.size >= minRows

    private fun anchorDue(rows: CachedRows): Boolean {
        val ttl = if (rows.complete) COMPLETE_ANCHOR_TTL_MS else OPEN_ANCHOR_TTL_MS
        return epochMillis() - rows.lastVerifiedAt >= ttl
    }

    private suspend fun anchorInBackground() {
        store.withRowsLock(key) {
            val stored = store.getDecoded(key, CachedRows.serializer()) ?: return@withRowsLock
            if (!anchorDue(stored)) return@withRowsLock
            val anchored = anchor(stored)
            if (anchored != stored) store.put(key, json.encodeToJsonElement(anchored))
        }
    }

    private suspend fun seedRows(seed: PipedPlaylistPage?, seedFresh: Boolean): CachedRows? {
        if (seed != null && !isThrottled(seed)) {
            return firstPageRows(seed).copy(lastVerifiedAt = if (seedFresh) epochMillis() else 0)
        }
        val page = orNull { fetchFirst() } ?: return null
        if (isThrottled(page)) return null
        return firstPageRows(page).copy(lastVerifiedAt = epochMillis())
    }

    /** Compares page 1 with the cache. A changed first page or a total that no longer fits drops the cache for the
     * fresh page 1; a failed fetch keeps the cache without a stamp, so the next read retries. */
    private suspend fun anchor(rows: CachedRows): CachedRows {
        val page = orNull { fetchFirst() } ?: return rows
        if (isThrottled(page)) return rows
        // An empty page 1 never replaces rows; it is stamped so reads do not ask again before the next anchor.
        if (page.relatedStreams.isEmpty() && rows.rawCount > 0) return rows.copy(lastVerifiedAt = epochMillis())
        val fresh = firstPageRows(page).copy(lastVerifiedAt = epochMillis())
        val freshIds = fresh.tracks.map { it.id }
        val prefixSame = rows.tracks.take(freshIds.size).map { it.id } == freshIds.take(rows.tracks.size)
        val total = page.videos
        // A chain that ended on rows that did not convert restarts only when the listing grew past what it held.
        val deadEnd = !rows.complete && rows.nextpage == null && rows.rawCount > 0
        if (deadEnd && total > maxOf(rows.rawCount, rows.listedTotal)) return fresh
        // Some instances list more rows than the pages hold, so a complete cache also fits its own listed total.
        val totalFits = total < 0 ||
            (total >= rows.rawCount && (!rows.complete || total == rows.rawCount || total == rows.listedTotal))
        // A cache of rows that all failed to convert has no ids to compare, so it is kept while page 1 has none either.
        val kept = prefixSame && totalFits && rows.rawCount > 0 && (rows.tracks.isNotEmpty() || fresh.tracks.isEmpty())
        return if (kept) rows.copy(lastVerifiedAt = fresh.lastVerifiedAt) else fresh
    }

    private suspend fun extend(start: CachedRows, minRows: Int): CachedRows {
        var rows = start
        var reseeded = false
        while (rows.tracks.size < minRows && !rows.complete) {
            val token = rows.nextpage
            // A null token ends the walk after rows that did not convert, or on an empty list that page 1 proved
            // recently; the anchor revisits both.
            if (token == null && (rows.rawCount > 0 || !anchorDue(rows))) break
            val page = if (token.isNullOrBlank()) null else orNull { fetchNext(token) }
            if (page != null && page.relatedStreams.isNotEmpty()) {
                rows = append(rows, page)
                continue
            }
            // A dead or blank token, a failed fetch or an empty page: restart the chain from page 1, once per read.
            if (reseeded) break
            reseeded = true
            val first = orNull { fetchFirst() } ?: break
            // An empty page 1 does not replace cached rows, the same rule the anchor keeps.
            if (isThrottled(first) || (first.relatedStreams.isEmpty() && rows.rawCount > 0)) break
            // The new chain is walked aside, and takes the cache's place once it covers as many rows or ends.
            val fresh = walk(firstPageRows(first), maxOf(minRows, rows.tracks.size))
            // A null token is the true end, even when the last page held only rows that did not convert.
            if (fresh.nextpage != null && fresh.tracks.size < rows.tracks.size) break
            rows = fresh.copy(lastVerifiedAt = epochMillis())
        }
        return rows
    }

    /** Extends [start] through its nextpage chain until it holds [minRows] rows or a page fails. */
    private suspend fun walk(start: CachedRows, minRows: Int): CachedRows {
        var rows = start
        while (rows.tracks.size < minRows && !rows.complete) {
            val token = rows.nextpage
            if (token.isNullOrBlank()) break
            val page = orNull { fetchNext(token) } ?: break
            if (page.relatedStreams.isEmpty()) break
            rows = append(rows, page)
        }
        return rows
    }

    private fun append(rows: CachedRows, page: PipedPlaylistPage): CachedRows {
        val converted = page.relatedStreams.mapIndexedNotNull { index, item -> convert(item, rows.rawCount + index + 1) }
        return rows.copy(
            tracks = rows.tracks + converted,
            rawCount = rows.rawCount + page.relatedStreams.size,
            nextpage = page.nextpage,
            complete = page.nextpage == null && converted.isNotEmpty(),
        )
    }

    private fun firstPageRows(page: PipedPlaylistPage): CachedRows {
        val converted = page.relatedStreams.mapIndexedNotNull { index, item -> convert(item, index + 1) }
        return CachedRows(
            tracks = converted,
            nextpage = page.nextpage,
            complete = page.nextpage == null && converted.isNotEmpty(),
            rawCount = page.relatedStreams.size,
            listedTotal = page.videos,
        )
    }

    /** An empty page 1 with a blank token is a throttle, not an empty list. */
    private fun isThrottled(page: PipedPlaylistPage) = page.relatedStreams.isEmpty() && page.nextpage?.isBlank() == true
}
