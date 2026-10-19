package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.NO_ALBUM
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val DICTIONARY_SHARDS = 16

/** Bytes a shard keeps. The open shard is sent again after each new track, so it stays small. */
internal const val SHARD_BYTES = 120_000
private const val PENDING_KEY = "storage.dict.pending"
private const val TRACK = "t:"
private const val ALBUM = "al:"

internal fun dictionarySuffix(index: Int) = "t$index"

internal fun shardOf(id: String, shards: Int = DICTIONARY_SHARDS): Int = id.sumOf { it.code } % shards

/** The newest entries that fit [limit] bytes. */
internal fun trimShard(entries: List<LwwEntry>, limit: Int = SHARD_BYTES): List<LwwEntry> {
    var used = 0
    return entries.sortedByDescending { it.at }.takeWhile { entry ->
        used += json.encodeToString(LwwEntry.serializer(), entry).encodeToByteArray().size + 1
        used <= limit
    }
}

/** Metadata and album lookups of saved and played tracks, so other devices skip the fetches. */
internal class SyncedDictionary(private val store: EntityStore, private val marks: DirtyMarks, private val now: () -> Long) : SyncKind {
    private val shards = (0 until DICTIONARY_SHARDS).map { LwwStore(store, dictionarySuffix(it)) }
    override val own: LwwStore get() = shards[0]
    override val suffix: String get() = own.suffix
    override val files: List<LwwStore> get() = shards
    private val lock = Mutex()
    private val pendingSerializer = ListSerializer(String.serializer())

    // Entries applied since the start, so a pull does not refill caches the store evicted on purpose.
    private val applied = HashSet<String>()

    /** Queues [ids]. Their entries are built at the next push, when album lookups may have finished. */
    suspend fun note(ids: List<String>) {
        if (ids.isEmpty()) return
        lock.withLock {
            val current = store.getDecoded(PENDING_KEY, pendingSerializer).orEmpty()
            val updated = (current + ids).distinct()
            store.put(PENDING_KEY, json.encodeToJsonElement(pendingSerializer, updated))
        }
    }

    override suspend fun beforeFlush() = lock.withLock { collect() }

    // Ids of a shard whose write failed stay pending, so the next flush retries them.
    private suspend fun collect() {
        val pending = store.getDecoded(PENDING_KEY, pendingSerializer).orEmpty()
        if (pending.isEmpty()) return
        val failed = mutableListOf<String>()
        for ((index, ids) in pending.groupBy { shardOf(it) }) {
            val shard = shards[index]
            val current = shard.entries().associateBy { it.key }
            val updates = ids.flatMap { entriesFor(it) }.filter { current[it.key]?.value != it.value }
            if (updates.isEmpty()) continue
            val merged = (current + updates.associateBy { it.key }).values.toList()
            if (shard.replaceAll(trimShard(merged))) marks.mark(shard.suffix) else failed += ids
        }
        if (failed.isEmpty()) {
            store.remove(PENDING_KEY)
        } else {
            store.put(PENDING_KEY, json.encodeToJsonElement(pendingSerializer, failed))
        }
    }

    private suspend fun entriesFor(id: String): List<LwwEntry> {
        val track = store.cachedTrack(id) ?: return emptyList()
        val album = store.cachedTrackAlbum(id)?.takeIf { it != NO_ALBUM }
        val at = now()
        val trackEntry = LwwEntry(TRACK + id, at, json.encodeToJsonElement(MetadataTrack.serializer(), track))
        return listOfNotNull(trackEntry, album?.let { LwwEntry(ALBUM + id, at, JsonPrimitive(it)) })
    }

    override suspend fun apply(winners: Map<String, Winner>, self: String) {
        for (winner in winners.values) {
            val entry = winner.entry
            val value = entry.value
            if (winner.device == self || value == null || entry.removed) continue
            if (!applied.add("${entry.key}@${entry.at}")) continue
            when {
                entry.key.startsWith(TRACK) -> fillTrack(entry.key.removePrefix(TRACK), value)
                entry.key.startsWith(ALBUM) -> fillAlbum(entry.key.removePrefix(ALBUM), value)
            }
        }
    }

    private suspend fun fillTrack(id: String, value: JsonElement) {
        if (store.cachedTrack(id) != null) return
        val track = runCatching { json.decodeFromJsonElement(MetadataTrack.serializer(), value) }.getOrNull() ?: return
        if (track.id == id) store.rememberTrack(track)
    }

    private suspend fun fillAlbum(id: String, value: JsonElement) {
        val album = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != NO_ALBUM } ?: return
        if (store.cachedTrackAlbum(id) == null) store.cacheTrackAlbum(id, album)
    }
}
