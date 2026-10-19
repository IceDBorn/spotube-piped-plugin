package dev.icedborn.spotube_plugin_piped.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal const val SEGMENT_EVENTS = 1_000
internal const val SEGMENT_COUNT = 16
internal const val AGG_SUFFIX = "agg"

/** Folded counts a device keeps. Above it the least played tracks go first. */
internal const val FOLDED_LIMIT = 5_000

private const val EVENT = "e:"
private const val FOLDED = "c:"
private const val BASELINE = "b:"

/** The `agg` entry with the newest segment generation folded into it. */
private const val FOLDED_THROUGH = "w"

internal fun segmentSuffix(index: Int) = "h$index"

/** A play is unique by device, video and time, so its key carries the time too. Its value is the segment generation. */
internal fun eventKey(videoId: String, at: Long) = "$EVENT$videoId:$at"

internal fun eventVideoId(key: String): String? =
    key.takeIf { it.startsWith(EVENT) }?.removePrefix(EVENT)?.substringBeforeLast(':', "")?.takeIf { it.isNotEmpty() }

internal fun eventGeneration(event: LwwEntry): Long? = (event.value as? JsonPrimitive)?.longOrNull

internal fun foldedKey(videoId: String) = "$FOLDED$videoId"

internal fun baselineKey(videoId: String) = "$BASELINE$videoId"

/** The newest generation folded into [agg], or -1 before the first fold. */
internal fun foldedThrough(agg: List<LwwEntry>): Long =
    agg.firstOrNull { it.key == FOLDED_THROUGH }?.let { (it.value as? JsonPrimitive)?.longOrNull } ?: -1

internal data class PlayCount(val plays: Int, val lastAt: Long)

internal fun countValue(count: PlayCount): JsonElement = JsonArray(listOf(JsonPrimitive(count.plays), JsonPrimitive(count.lastAt)))

internal fun decodeCount(value: JsonElement?): PlayCount? {
    val array = value as? JsonArray ?: return null
    val plays = (array.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return null
    val lastAt = (array.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: return null
    return PlayCount(plays, lastAt)
}

private fun HashMap<String, PlayCount>.add(id: String, count: PlayCount, sum: Boolean) {
    val current = this[id]
    val plays = when {
        current == null -> count.plays
        sum -> current.plays + count.plays
        else -> maxOf(current.plays, count.plays)
    }
    this[id] = PlayCount(plays, maxOf(current?.lastAt ?: 0L, count.lastAt))
}

/** Sums events and folded counts of every device, skipping events its `agg` already folded, then adds the largest
 * baseline per track. Baselines overlap because the old history log already spread plays between devices. */
internal fun playCounts(docs: List<SyncDoc>): Map<String, PlayCount> {
    val counts = HashMap<String, PlayCount>()
    val baselines = HashMap<String, PlayCount>()
    for (entries in docs.groupBy({ it.device }, { it.entries }).values.map { it.flatten() }) {
        val through = foldedThrough(entries)
        for (entry in entries) {
            val key = entry.key
            val event = eventVideoId(key)
            when {
                event != null -> if ((eventGeneration(entry) ?: -1) > through) counts.add(event, PlayCount(1, entry.at), sum = true)
                key.startsWith(FOLDED) -> decodeCount(entry.value)?.let { counts.add(key.removePrefix(FOLDED), it, sum = true) }
                key.startsWith(BASELINE) -> decodeCount(entry.value)?.let { baselines.add(key.removePrefix(BASELINE), it, sum = false) }
            }
        }
    }
    for ((id, baseline) in baselines) counts.add(id, baseline, sum = true)
    return counts
}

/** [agg] with the [events] of generations up to [through] added to its folded counts and [through] as its watermark.
 * [agg] itself when it is folded that far already. Baseline entries stay as they are. */
internal fun foldInto(agg: List<LwwEntry>, events: List<LwwEntry>, through: Long, at: Long, limit: Int = FOLDED_LIMIT): List<LwwEntry> {
    val from = foldedThrough(agg)
    if (through <= from) return agg
    val folded = HashMap<String, PlayCount>()
    for (entry in agg) {
        if (!entry.key.startsWith(FOLDED)) continue
        decodeCount(entry.value)?.let { folded[entry.key.removePrefix(FOLDED)] = it }
    }
    for (event in events) {
        val id = eventVideoId(event.key) ?: continue
        val generation = eventGeneration(event) ?: continue
        if (generation in (from + 1)..through) folded.add(id, PlayCount(1, event.at), sum = true)
    }
    val kept = folded.entries
        .sortedWith(compareByDescending<Map.Entry<String, PlayCount>> { it.value.plays }.thenByDescending { it.value.lastAt })
        .take(limit)
    val baselines = agg.filterNot { it.key.startsWith(FOLDED) || it.key == FOLDED_THROUGH }
    val counts = kept.map { (id, count) -> LwwEntry(foldedKey(id), at, countValue(count)) }
    return baselines + counts + LwwEntry(FOLDED_THROUGH, at, JsonPrimitive(through))
}
