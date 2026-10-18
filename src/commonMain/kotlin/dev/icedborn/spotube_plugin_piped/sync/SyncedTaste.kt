package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.store.Skip
import dev.icedborn.spotube_plugin_piped.store.trimSkips
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val TASTE_SUFFIX = "taste"
private const val SKIP_PREFIX = "s:"

internal fun skipKey(id: String, at: Long) = "$SKIP_PREFIX$id:$at"

internal fun skipOf(entry: LwwEntry): Skip? {
    if (!entry.key.startsWith(SKIP_PREFIX)) return null
    val id = entry.key.removePrefix(SKIP_PREFIX).substringBeforeLast(':')
    val artist = (entry.value as? JsonPrimitive)?.contentOrNull ?: return null
    return Skip(id, artist, entry.at).takeIf { id.isNotEmpty() }
}

private fun entryOf(skip: Skip) = LwwEntry(skipKey(skip.id, skip.at), skip.at, JsonPrimitive(skip.artist))

/** Endless playback skips, one event per skip, so every device's file merges as a union. */
internal class SyncedTaste(override val own: LwwStore, private val now: () -> Long) : SyncKind {
    override val suffix: String get() = own.suffix

    // The trim reads and rewrites the file, so a skip recorded meanwhile would be lost without it.
    private val lock = Mutex()

    suspend fun record(skips: List<Skip>): Boolean = lock.withLock {
        val ok = own.record(skips.map(::entryOf))
        ok
    }

    suspend fun importLocal(skips: List<Skip>): Boolean = lock.withLock {
        val ok = own.recordIfAbsent(trimSkips(skips, now()).map(::entryOf))
        ok
    }

    override suspend fun beforeFlush() {
        lock.withLock {
            val entries = own.entries()
            val skips = entries.mapNotNull(::skipOf)
            val kept = trimSkips(skips, now()).mapTo(HashSet()) { skipKey(it.id, it.at) }
            if (kept.size != entries.size) {
                val trimmed = entries.filter { it.key in kept }
                val ok = own.replaceAll(trimmed)
                ok
            }
        }
    }
}
