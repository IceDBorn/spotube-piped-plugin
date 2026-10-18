package dev.icedborn.spotube_plugin_piped.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val PLAYS_KEY = "history.plays"
internal const val PLAY_LOG_LIMIT = 5_000

/** One counted play: the video and when it played. */
internal data class Play(val id: String, val at: Long)

/** Every play [PlayHistory] counts on this device, newest first, stored as `[videoId, at]` pairs. */
internal class PlayLog(private val store: EntityStore, private val limit: Int = PLAY_LOG_LIMIT) {

    suspend fun all(): List<Play> {
        val rows = store.get(PLAYS_KEY) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val pair = row as? JsonArray ?: return@mapNotNull null
            val id = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val at = (pair.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            Play(id, at)
        }
    }

    /** False when the write failed. [PlayHistory] calls it under its lock, so two appends never interleave. */
    suspend fun append(id: String, at: Long): Boolean {
        val newPair = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(at)))
        val existing = store.get(PLAYS_KEY) as? JsonArray
        val validExisting = if (existing != null && existing.all { it is JsonArray && it.size == 2 }) existing else null
        val encoded = JsonArray((listOf(newPair) + validExisting.orEmpty()).take(limit))
        return store.put(PLAYS_KEY, encoded)
    }
}
