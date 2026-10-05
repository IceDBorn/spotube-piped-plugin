package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.core.AccountIdentity
import kotlinx.serialization.Serializable

internal const val HISTORY_PLAYLIST_NAME = "Spotube - History"
internal const val HISTORY_PLAYLIST_KEY = "history.playlist"
internal const val HISTORY_PENDING_KEY = "history.pending"
internal const val HISTORY_SEEN_KEY = "history.seen"

/** Rows the log keeps. A refresh removes the oldest rows above it. */
internal const val HISTORY_LOG_CAP = 500

/** Rows one refresh removes, so a long log shrinks over several refreshes. */
internal const val HISTORY_TRIM_LIMIT = 20

/** Plays that can wait for upload. The oldest are dropped above it. */
internal const val HISTORY_PENDING_LIMIT = 300

/** Refusals after which a play is dropped, and the wait between two tries. */
internal const val HISTORY_PUSH_ATTEMPTS = 4
internal const val HISTORY_RETRY_MS = 6 * 3_600_000L

/** A play waiting for upload. [attempts] counts refusals, and the play is not tried again before [retryAt]. */
@Serializable
internal data class PendingPlay(val id: String, val attempts: Int = 0, val retryAt: Long = 0L)

/** One account's upload queue. [sent] holds the ids uploaded since the last merge, so a merge does not read
 * this device's own rows as plays of another device. */
@Serializable
internal data class PendingPlays(
    override val instance: String,
    override val username: String,
    val plays: List<PendingPlay> = emptyList(),
    val sent: List<String> = emptyList(),
) : AccountIdentity

/** The log rows this device has merged, oldest first. A stored one means the device has joined that log. */
@Serializable
internal data class SeenLog(
    override val instance: String,
    override val username: String,
    val playlistId: String,
    val rows: List<String> = emptyList(),
) : AccountIdentity

/** The rows of [walked] added since [seen] was read. The log only grows at its end, so the old rows are the
 * longest start of [walked] that [seen] holds in the same order, whatever was removed in between. */
internal fun appendedRows(seen: List<String>, walked: List<String>): List<String> {
    var at = 0
    for ((index, id) in walked.withIndex()) {
        while (at < seen.size && seen[at] != id) at++
        if (at == seen.size) return walked.drop(index)
        at++
    }
    return emptyList()
}

/** [rows] without one row per id in [sent], which are the rows this device added itself. */
internal fun withoutOwn(rows: List<String>, sent: List<String>): List<String> {
    val own = sent.groupingBy { it }.eachCount().toMutableMap()
    return rows.filter { id ->
        val left = own[id] ?: 0
        if (left > 0) own[id] = left - 1
        left == 0
    }
}
