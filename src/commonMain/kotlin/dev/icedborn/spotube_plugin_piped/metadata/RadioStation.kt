package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement

private const val STATIONS_KEY = "radio.stations"
private const val MAX_STATIONS = 3

/** A station that has not returned a batch for this long is over, and its seeds start a new one. */
internal const val STATION_IDLE_MS = 24 * 60 * 60_000L

private const val RETURNED_LIMIT = 500
private const val POOL_LIMIT = 200
private const val READ_LIMIT = 200
private const val QUEUED_LIMIT = 200
private const val SKIPPED_LIMIT = 100

/** Reciprocal rank fusion adds weight / (k + rank) per list. Mixes are short, so k sits below the usual 60. */
private const val FUSION_K = 20

@Serializable
internal data class StationTrack(val id: String, val version: String)

@Serializable
internal data class RadioBatch(val at: Long, val tracks: List<StationTrack>)

@Serializable
internal data class PoolEntry(val id: String, val score: Double)

/** One endless playback session. [queued] holds the seeds the host sent, [read] the seeds whose mix was read and
 * [pool] the scored songs not yet returned. */
@Serializable
internal data class RadioStation(
    val updatedAt: Long,
    val queued: List<String> = emptyList(),
    val read: List<String> = emptyList(),
    val pool: List<PoolEntry> = emptyList(),
    val batches: List<RadioBatch> = emptyList(),
    val skipped: List<StationTrack> = emptyList(),
) {
    fun returnedIds(): Set<String> = batches.flatMapTo(HashSet()) { batch -> batch.tracks.map { it.id } }

    /** How many of [seeds] this station queued or returned. */
    fun overlap(seeds: List<String>): Int {
        val known = returnedIds() + queued
        return seeds.count { it in known }
    }

    fun withQueued(ids: List<String>): RadioStation {
        val returned = returnedIds()
        return copy(queued = (queued + ids.filterNot { it in returned }).distinct().takeLast(QUEUED_LIMIT))
    }

    fun withRead(ids: List<String>) = copy(read = (read + ids).distinct().takeLast(READ_LIMIT))

    /** Adds the ranked list [ids] to the pool by reciprocal rank fusion. Queued and returned songs stay out. */
    fun withRanked(ids: List<String>, weight: Double): RadioStation {
        val blocked = returnedIds() + queued
        val scores = LinkedHashMap<String, Double>()
        for (entry in pool) scores[entry.id] = entry.score
        ids.distinct().forEachIndexed { rank, id ->
            if (id !in blocked) scores[id] = (scores[id] ?: 0.0) + weight / (FUSION_K + rank + 1)
        }
        // A stable sort, so a tie keeps the song that was in the pool first.
        val ranked = scores.entries.sortedByDescending { it.value }.take(POOL_LIMIT)
        return copy(pool = ranked.map { PoolEntry(it.key, it.value) })
    }

    /** Records [picks] as the batch returned at [at]. Past [RETURNED_LIMIT] returned songs the oldest batches go. */
    fun afterBatch(picks: List<StationTrack>, at: Long): RadioStation {
        var kept = if (picks.isEmpty()) batches else batches + RadioBatch(at, picks)
        while (kept.size > 1 && kept.sumOf { it.tracks.size } > RETURNED_LIMIT) kept = kept.drop(1)
        val ids = picks.mapTo(HashSet()) { it.id }
        return copy(updatedAt = at, pool = pool.filterNot { it.id in ids }, batches = kept)
    }

    /** The artists of the last [ARTIST_GAP] songs returned, oldest first. */
    fun lastArtists(): List<String> =
        batches.takeLast(ARTIST_GAP).flatMap { it.tracks }.takeLast(ARTIST_GAP).map { it.version.substringBefore('|') }

    /** Marks each song of the last batch that was not played after it while a later song was. The host asks for a
     * batch when the last song starts, so the songs before it are over. */
    fun withSkips(playedAt: (String) -> Long): RadioStation {
        val batch = batches.lastOrNull() ?: return this
        val played = batch.tracks.map { playedAt(it.id) >= batch.at }
        val last = played.lastIndexOf(true)
        val skips = batch.tracks.filterIndexed { index, _ -> index < last && !played[index] }
        return copy(skipped = (skipped + skips).distinct().takeLast(SKIPPED_LIMIT))
    }
}

/** The last [MAX_STATIONS] stations, newest first. The memory copy wins when a write fails, so a station lives
 * until restart. */
internal class RadioStations(private val store: EntityStore) {

    private var stations: List<RadioStation>? = null

    suspend fun load(): List<RadioStation> =
        stations ?: store.getDecoded(STATIONS_KEY, ListSerializer(RadioStation.serializer())).orEmpty().also { stations = it }

    /** Keeps the first [MAX_STATIONS] of [list]. False when the write failed. */
    suspend fun save(list: List<RadioStation>): Boolean {
        val kept = list.take(MAX_STATIONS)
        stations = kept
        return store.put(STATIONS_KEY, json.encodeToJsonElement(kept))
    }
}
