package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Per-item fetches for one page, [limit] at a time; results keep the input order. */
internal suspend fun <T, R> List<T>.mapConcurrently(limit: Int = 4, transform: suspend (T) -> R): List<R> {
    val permits = Semaphore(limit)
    return coroutineScope { map { async { permits.withPermit { transform(it) } } }.awaitAll() }
}

/** Same defaults as the reference plugin: page zero, 50 items. */
internal fun PaginationStrategy?.getOffsetOrDefault(): PaginationStrategy.Offset =
    this as? PaginationStrategy.Offset ?: PaginationStrategy.Offset(0, 50)

/** Piped pages are continuation-token based; any fixed-page strategy means the first page. */
internal fun PaginationStrategy?.continuationToken(): String? = when (this) {
    is PaginationStrategy.Continuation -> continuationToken
    is PaginationStrategy.Cursor -> cursor
    else -> null
}

/** Null when the API hands back the cursor it was given, which would make the host request the same page forever. */
internal fun nextContinuation(nextpage: String?, current: String? = null): PaginationStrategy? =
    nextpage?.takeIf { it.isNotBlank() && it != current }?.let { PaginationStrategy.Continuation(it) }

private const val SEEN_LISTS_LIMIT = 50
private val seenPerList = LinkedHashMap<String, HashSet<String>>()

/** Drops items already served earlier in the same continuation list. YouTube repeats rows across pages, and the
 * host keys list items by id, so a repeat crashes it. The first page ([token] null) starts the list over. */
internal fun <T> distinctAcrossPages(listKey: String, token: String?, items: List<T>, id: (T) -> String): List<T> {
    val seen = if (token == null) HashSet() else seenPerList.remove(listKey) ?: HashSet()
    seenPerList[listKey] = seen
    if (seenPerList.size > SEEN_LISTS_LIMIT) seenPerList.remove(seenPerList.keys.first())
    return items.filter { seen.add(id(it)) }
}

/** The [offset]-based window of [rows] minus ids already shown earlier in the list, for the same host id-key rule. */
internal fun <T> distinctWindow(rows: List<T>, offset: Int, window: List<T>, id: (T) -> String): List<T> {
    val seen = rows.take(offset).mapTo(HashSet()) { id(it) }
    return window.filter { seen.add(id(it)) }
}

internal fun <T> emptyPagination(): PaginationResult<T> =
    PaginationResult(emptyList(), 0, null)
