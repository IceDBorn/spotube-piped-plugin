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
        // Rows that do not read back are dropped, the way [all] skips them.
        val plays = (listOf(Play(id, at)) + all()).take(limit)
        val encoded = JsonArray(plays.map { JsonArray(listOf(JsonPrimitive(it.id), JsonPrimitive(it.at))) })
        return store.put(PLAYS_KEY, encoded)
    }
}
