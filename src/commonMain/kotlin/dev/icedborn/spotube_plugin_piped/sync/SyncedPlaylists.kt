package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.StoredPlaylist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

internal const val PLAYLIST_IMAGE_LIMIT = 16_384

// Local playlists carry this prefix; their place in the library follows the playlist record, not the library kind.
internal const val LOCAL_PLAYLIST_PREFIX = "piped-"
private const val TOUCHED_KEY = "storage.pl.touched"

/** Local playlists as whole records. A touched playlist is read when it is pushed, after the API has saved it. */
internal class SyncedPlaylists(override val own: LwwStore, private val store: EntityStore, private val library: LocalLibrary) : SyncKind {
    override val suffix: String get() = own.suffix
    private val lock = Mutex()
    private val touchedSerializer = MapSerializer(String.serializer(), Long.serializer())

    private suspend fun touched(): Map<String, Long> = store.getDecoded(TOUCHED_KEY, touchedSerializer).orEmpty()

    suspend fun touch(localId: String, at: Long) = lock.withLock {
        val current = touched()
        val updated = current + (localId to at)
        store.put(TOUCHED_KEY, json.encodeToJsonElement(touchedSerializer, updated))
    }

    suspend fun importLocal() {
        val playlists = library.storedPlaylists()
        own.recordIfAbsent(playlists.map { LwwEntry(it.id, 0, encode(it)) })
    }

    override suspend fun beforeFlush() = lock.withLock {
        val pending = touched()
        if (pending.isEmpty()) return@withLock
        val entries = pending.map { (id, at) ->
            val record = library.storedPlaylist(id)
            LwwEntry(id, at, record?.let(::encode))
        }
        own.record(entries)
        store.remove(TOUCHED_KEY)
    }

    private fun encode(record: StoredPlaylist) = json.encodeToJsonElement(
        StoredPlaylist.serializer(),
        if (record.imageBase64.length > PLAYLIST_IMAGE_LIMIT) record.copy(imageBase64 = "") else record,
    )

    override suspend fun apply(winners: Map<String, Winner>, self: String) {
        val pending = touched().keys
        for (winner in winners.values) {
            val id = winner.entry.key
            if (winner.device == self || id in pending) continue
            val local = library.storedPlaylist(id)
            val listed = library.savedPlaylists()
            if (winner.entry.removed) {
                if (local != null) library.deleteStoredPlaylist(id)
                if (id in listed) library.removePlaylists(listOf(id))
                continue
            }
            if (id !in listed) library.savePlaylists(listOf(id))
            val element = winner.entry.value ?: continue
            val remote = runCatching {
                json.decodeFromJsonElement(StoredPlaylist.serializer(), element)
            }.getOrNull() ?: continue
            val merged = if (remote.imageBase64.isEmpty()) remote.copy(imageBase64 = local?.imageBase64.orEmpty()) else remote
            if (merged != local) library.upsertPlaylist(merged)
        }
    }
}
