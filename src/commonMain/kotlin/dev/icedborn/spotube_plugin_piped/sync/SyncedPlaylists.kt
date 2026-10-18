package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.StoredPlaylist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

internal const val PLAYLIST_IMAGE_LIMIT = 16_384

// Local playlists carry this prefix; their place in the library follows the playlist record, not the library kind.
internal const val LOCAL_PLAYLIST_PREFIX = "piped-"
private const val TOUCHED_KEY = "storage.pl.touched"

// Whether the playlist is in the library list. Entries written before it read as listed.
private const val LISTED = "listed"

/** Local playlists as whole records. A touched playlist is read when it is pushed, after the API has saved it. */
internal class SyncedPlaylists(
    override val own: LwwStore,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val marks: DirtyMarks,
) : SyncKind {
    override val suffix: String get() = own.suffix
    private val lock = Mutex()
    private val touchedSerializer = MapSerializer(String.serializer(), Long.serializer())

    private suspend fun touched(): Map<String, Long> = store.getDecoded(TOUCHED_KEY, touchedSerializer).orEmpty()

    suspend fun touch(localId: String, at: Long) = lock.withLock {
        val current = touched()
        val updated = current + (localId to at)
        store.put(TOUCHED_KEY, json.encodeToJsonElement(touchedSerializer, updated))
    }

    /** Touches the stored local playlists among [ids], since a local playlist carries its place in the library. */
    suspend fun touchListing(ids: List<String>, at: Long): Boolean {
        var any = false
        for (id in ids) {
            if (!isLocalPlaylist(id)) continue
            val record = library.storedPlaylist(id)
            if (record == null) continue
            touch(id, at)
            any = true
        }
        return any
    }

    suspend fun importLocal() {
        val playlists = library.storedPlaylists()
        val listed = library.savedPlaylists()
        own.recordIfAbsent(playlists.map { LwwEntry(it.id, 0, encode(it, it.id in listed)) })
    }

    override suspend fun beforeFlush() = lock.withLock {
        val pending = touched()
        if (pending.isEmpty()) return@withLock
        val listed = library.savedPlaylists()
        val entries = pending.map { (id, at) ->
            val record = library.storedPlaylist(id)
            LwwEntry(id, at, record?.let { encode(it, id in listed) })
        }
        // A failed record keeps the touches, so the edit still wins over a pull and the next flush records it.
        val recorded = own.record(entries)
        if (!recorded) return@withLock
        store.remove(TOUCHED_KEY)
        // On a retry the failed flush has already pushed the file and cleared its mark.
        val dirty = marks.all()
        if (suffix !in dirty) marks.mark(suffix)
    }

    /** True when an edit made here at [at] and not pushed yet wins over [winner] once pushed, as `mergeLww` decides. */
    private fun keepsLocal(at: Long?, winner: Winner, self: String): Boolean =
        at != null && (at > winner.entry.at || (at == winner.entry.at && self > winner.device))

    private fun encode(record: StoredPlaylist, listed: Boolean): JsonObject {
        val sent = if (record.imageBase64.length > PLAYLIST_IMAGE_LIMIT) record.copy(imageBase64 = "") else record
        val fields = json.encodeToJsonElement(StoredPlaylist.serializer(), sent).jsonObject
        return JsonObject(fields + (LISTED to JsonPrimitive(listed)))
    }

    override suspend fun apply(winners: Map<String, Winner>, self: String) {
        val pending = touched()
        for (winner in winners.values) {
            val id = winner.entry.key
            if (winner.device == self || keepsLocal(pending[id], winner, self)) continue
            val local = library.storedPlaylist(id)
            val listed = library.savedPlaylists()
            if (winner.entry.removed) {
                if (local != null) library.deleteStoredPlaylist(id)
                if (id in listed) library.removePlaylists(listOf(id))
                continue
            }
            val element = winner.entry.value ?: continue
            val remote = runCatching {
                json.decodeFromJsonElement(StoredPlaylist.serializer(), element)
            }.getOrNull() ?: continue
            val shown = ((element as? JsonObject)?.get(LISTED) as? JsonPrimitive)?.booleanOrNull ?: true
            if (shown && id !in listed) library.savePlaylists(listOf(id))
            if (!shown && id in listed) library.removePlaylists(listOf(id))
            val merged = if (remote.imageBase64.isEmpty()) remote.copy(imageBase64 = local?.imageBase64.orEmpty()) else remote
            if (merged != local) library.upsertPlaylist(merged)
        }
    }
}
