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

internal fun nextContinuation(nextpage: String?): PaginationStrategy? =
    nextpage?.takeIf { it.isNotBlank() }?.let { PaginationStrategy.Continuation(it) }


internal fun <T> emptyPagination(): PaginationResult<T> =
    PaginationResult(emptyList(), 0, null)
