package dev.icedborn.spotube_plugin_piped.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val SKIPS_KEY = "taste.skips"
internal const val SKIP_LIMIT = 500
internal const val SKIP_KEEP_MS = 30 * 24 * 60 * 60_000L

/** A song endless playback saw skipped, with its artist key and the time of the batch it came in. */
internal data class Skip(val id: String, val artist: String, val at: Long)

/** The newest [limit] of [skips] younger than [SKIP_KEEP_MS] at [now], one per song and time. */
internal fun trimSkips(skips: List<Skip>, now: Long, limit: Int = SKIP_LIMIT): List<Skip> =
    skips.filter { now - it.at < SKIP_KEEP_MS }.distinctBy { it.id to it.at }.sortedByDescending { it.at }.take(limit)

/** This device's skips, newest first, stored as `[videoId, artist, at]` triples. */
internal class SkipLog(private val store: EntityStore) {

    suspend fun all(): List<Skip> {
        val rows = store.get(SKIPS_KEY) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val triple = row as? JsonArray ?: return@mapNotNull null
            val id = (triple.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val artist = (triple.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val at = (triple.getOrNull(2) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            Skip(id, artist, at)
        }
    }

    /** False when the write failed. Endless playback batches run one at a time, so two appends never interleave. */
    suspend fun append(skips: List<Skip>, now: Long): Boolean {
        val stored = all()
        val kept = trimSkips(skips + stored, now)
        val rows = kept.map { JsonArray(listOf(JsonPrimitive(it.id), JsonPrimitive(it.artist), JsonPrimitive(it.at))) }
        return store.put(SKIPS_KEY, JsonArray(rows))
    }
}
