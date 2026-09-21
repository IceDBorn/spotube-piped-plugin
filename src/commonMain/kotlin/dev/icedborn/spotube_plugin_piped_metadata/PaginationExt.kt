package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy

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
