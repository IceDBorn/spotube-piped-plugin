package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import kotlinx.serialization.json.JsonPrimitive

private val SAVED = JsonPrimitive(1)

internal fun libraryKey(kind: SavedKind, id: String): String = when (kind) {
    SavedKind.TRACK -> "t:$id"
    SavedKind.ALBUM -> "a:$id"
    SavedKind.ARTIST -> "r:${canonicalArtistId(id)}"
}

/** Saved tracks, albums, artists and public playlists as last-writer-wins entries. */
internal class SyncedLibrary(override val own: LwwStore, private val library: LocalLibrary) : SyncKind {
    override val suffix: String get() = own.suffix

    suspend fun record(kind: SavedKind, ids: List<String>, saved: Boolean, at: Long) {
        own.record(ids.map { LwwEntry(libraryKey(kind, it), at, SAVED.takeIf { saved }) })
    }

    suspend fun recordPlaylists(ids: List<String>, saved: Boolean, at: Long) {
        own.record(ids.map { LwwEntry("p:$it", at, SAVED.takeIf { saved }) })
    }

    /** Snapshot items join the local library too, since storage mode reads saved state only from there. */
    suspend fun importLocal(extra: Map<SavedKind, List<String>>) {
        extra[SavedKind.TRACK]?.takeIf { it.isNotEmpty() }?.let { library.saveTracks(it) }
        extra[SavedKind.ALBUM]?.takeIf { it.isNotEmpty() }?.let { library.saveAlbums(it) }
        extra[SavedKind.ARTIST]?.takeIf { it.isNotEmpty() }?.let { library.saveArtists(it) }
        val entries = SavedKind.entries.flatMap { kind ->
            (localIds(kind) + extra[kind].orEmpty()).distinct().map { LwwEntry(libraryKey(kind, it), 0, SAVED) }
        } + library.savedPlaylists().map { LwwEntry("p:$it", 0, SAVED) }
        own.recordIfAbsent(entries)
    }

    override suspend fun apply(winners: Map<String, Winner>, self: String) {
        val remote = winners.values.filter { it.device != self }
        for (prefix in listOf("t:", "a:", "r:", "p:")) {
            val ofKind = remote.filter { it.entry.key.startsWith(prefix) }
            val add = ofKind.filterNot { it.entry.removed }.map { it.entry.key.removePrefix(prefix) }
            val drop = ofKind.filter { it.entry.removed }.map { it.entry.key.removePrefix(prefix) }
            applyKind(prefix, add, drop)
        }
    }

    private suspend fun applyKind(prefix: String, add: List<String>, drop: List<String>) {
        if (add.isEmpty() && drop.isEmpty()) return
        val present = when (prefix) {
            "t:" -> library.savedTracks()
            "a:" -> library.savedAlbums()
            "r:" -> library.savedArtists().map(::canonicalArtistId)
            else -> library.savedPlaylists()
        }.toHashSet()
        val toAdd = add.filterNot { it in present }
        val toDrop = drop.filter { it in present }
        when (prefix) {
            "t:" -> {
                if (toAdd.isNotEmpty()) library.saveTracks(toAdd)
                if (toDrop.isNotEmpty()) library.removeTracks(toDrop)
            }

            "a:" -> {
                if (toAdd.isNotEmpty()) library.saveAlbums(toAdd)
                if (toDrop.isNotEmpty()) library.removeAlbums(toDrop)
            }

            "r:" -> {
                if (toAdd.isNotEmpty()) library.saveArtists(toAdd)
                if (toDrop.isNotEmpty()) library.removeArtists(toDrop)
            }

            else -> {
                if (toAdd.isNotEmpty()) library.savePlaylists(toAdd)
                if (toDrop.isNotEmpty()) library.removePlaylists(toDrop)
            }
        }
    }

    private suspend fun localIds(kind: SavedKind): List<String> = when (kind) {
        SavedKind.TRACK -> library.savedTracks()
        SavedKind.ALBUM -> library.savedAlbums()
        SavedKind.ARTIST -> library.savedArtists()
    }
}
