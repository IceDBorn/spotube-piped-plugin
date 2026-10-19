package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.HISTORY_PENDING_LIMIT
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlayedTrack
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

private const val CURSOR_KEY = "storage.hist.cursor"
private const val HELD_KEY = "storage.hist.pending"

/** Tracks the rebuild looks up, newest first. Older ones still count, but `history.tracks` keeps only 200. */
private const val REBUILD_SCAN = 400
private val historyLog = Logger("PipedSyncHistory")

/** The open segment's generation. It grows by one per segment opened, and the open one is `h<gen % count>`. */
@Serializable
private data class HistoryCursor(val gen: Long = 0)

@Serializable
private data class HeldPlay(val id: String, val at: Long)

private val heldSerializer = ListSerializer(HeldPlay.serializer())

/** Scrobbled plays as events in a ring of segment files, with the counts of overwritten segments and the import
 * baseline in `agg`. */
internal class SyncedHistory(
    private val store: EntityStore,
    private val history: PlayHistory,
    private val marks: DirtyMarks,
    private val segmentEvents: Int = SEGMENT_EVENTS,
    segmentCount: Int = SEGMENT_COUNT,
) : SyncKind {
    private val segments = (0 until segmentCount).map { LwwStore(store, segmentSuffix(it)) }
    override val own = LwwStore(store, AGG_SUFFIX)
    override val suffix: String get() = own.suffix

    // `agg` goes out first. A failed segment put leaves a watermark that hides the folded events, and a failed `agg`
    // put holds the emptied segments back.
    override val files: List<LwwStore> get() = listOf(own) + segments
    override val firstHoldsBack: Boolean get() = true
    private val lock = Mutex()

    private fun segmentOf(gen: Long) = segments[(gen % segments.size).toInt()]

    /** False when the event could not be stored. */
    suspend fun record(videoId: String, at: Long): Boolean {
        val touched = mutableListOf<String>()
        val stored = lock.withLock { recordLocked(videoId, at, touched) }
        touched.forEach { marks.mark(it) }
        return stored
    }

    /** Keeps a play made before the history import finished, for [releaseHeld]. */
    suspend fun hold(videoId: String, at: Long) = lock.withLock {
        val current = heldPlays()
        val held = (current + HeldPlay(videoId, at)).takeLast(HISTORY_PENDING_LIMIT)
        store.put(HELD_KEY, json.encodeToJsonElement(heldSerializer, held))
    }

    /** Records the held plays with their times. With [count] false they are dropped, as a new baseline holds them.
     * A play whose record fails stays held. False when the remaining plays could not be stored. */
    suspend fun releaseHeld(count: Boolean): Boolean {
        val held = lock.withLock { heldPlays() }
        if (held.isEmpty()) return true
        val kept = mutableListOf<HeldPlay>()
        if (count) {
            for (play in held) {
                val stored = record(play.id, play.at)
                if (!stored) kept += play
            }
        }
        return lock.withLock {
            val current = heldPlays()
            // Plays held since the first read stay too.
            val remaining = kept + (current - held.toSet())
            store.put(HELD_KEY, json.encodeToJsonElement(heldSerializer, remaining))
        }
    }

    private suspend fun heldPlays(): List<HeldPlay> {
        val stored = store.getDecoded(HELD_KEY, heldSerializer)
        return stored.orEmpty()
    }

    private suspend fun recordLocked(videoId: String, at: Long, touched: MutableList<String>): Boolean {
        val cursor = store.getDecoded(CURSOR_KEY, HistoryCursor.serializer()) ?: HistoryCursor()
        var gen = cursor.gen
        val open = segmentOf(gen).entries()
        if (open.size >= segmentEvents && reuse(gen + 1, at, touched)) gen++
        val segment = segmentOf(gen)
        val stored = segment.record(listOf(LwwEntry(eventKey(videoId, at), at, JsonPrimitive(gen))))
        if (stored) touched += segment.suffix
        return stored
    }

    /** Folds what segment [gen] still holds into `agg`, empties it and moves the cursor there. False keeps the play in
     * the open segment, and the next play tries again. */
    private suspend fun reuse(gen: Long, at: Long, touched: MutableList<String>): Boolean {
        val segment = segmentOf(gen)
        val events = segment.entries()
        val agg = own.entries()
        // `agg` first: its watermark hides the folded events, so a copy of the segment that still has them counts nothing.
        val folded = foldInto(agg, events, through = gen - segments.size, at = at)
        if (folded !== agg) {
            val written = own.replaceAll(folded)
            if (!written) return false
            touched += AGG_SUFFIX
        }
        if (events.isNotEmpty()) {
            val cleared = segment.replaceAll(emptyList())
            if (!cleared) return false
        }
        val cursor = json.encodeToJsonElement(HistoryCursor.serializer(), HistoryCursor(gen))
        return store.put(CURSOR_KEY, cursor)
    }

    /** Puts back the files this device published for the account before an owner reset. False when a write failed. */
    suspend fun restore(published: Map<String, List<LwwEntry>>): Boolean = lock.withLock {
        var ok = true
        for (file in files) {
            val entries = published[file.suffix]
            if (entries != null) {
                val written = file.replaceAll(entries)
                if (!written) ok = false
            }
        }
        // The ring goes on from the newest generation, kept above the watermark so the next play counts.
        val newest = segments.flatMap { published[it.suffix].orEmpty() }.mapNotNull(::eventGeneration).maxOrNull() ?: 0L
        val gen = maxOf(newest, foldedThrough(published[AGG_SUFFIX].orEmpty()) + 1)
        val cursor = json.encodeToJsonElement(HistoryCursor.serializer(), HistoryCursor(gen))
        val cursorOk = store.put(CURSOR_KEY, cursor)
        ok && cursorOk
    }

    /** The plays this device counted before storage, as its baseline. */
    suspend fun importBaseline() {
        val local = history.all().filter { it.plays > 0 }
        own.recordIfAbsent(local.map { LwwEntry(baselineKey(it.track.id), 0, countValue(PlayCount(it.plays, it.lastPlayedAt))) })
        marks.mark(AGG_SUFFIX)
    }

    override suspend fun applyDocs(docs: List<SyncDoc>, self: String) {
        val counts = playCounts(docs)
        val local = history.all().associateBy { it.track.id }
        val newest = counts.entries.sortedByDescending { it.value.lastAt }.take(REBUILD_SCAN)
        val merged = newest.mapNotNull { (id, count) ->
            val track = local[id]?.track ?: store.cachedTrack(id) ?: return@mapNotNull null
            PlayedTrack(track, count.plays, count.lastAt)
        }
        if (!history.mergeCounts(merged)) historyLog.w { "merged plays could not be stored" }
    }
}
