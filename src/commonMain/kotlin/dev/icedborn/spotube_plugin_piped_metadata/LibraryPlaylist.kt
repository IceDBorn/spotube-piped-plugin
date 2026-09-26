package dev.icedborn.spotube_plugin_piped_metadata

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val LIBRARY_PLAYLIST_KEY = "piped.library.placeholder"

/** Synthetic Playlists-tab entries. They are served, never stored, so an open page survives a setting change. */
internal const val LIKED_SONGS_ID = "piped-liked-songs"
internal const val RECENTLY_PLAYED_ID = "piped-recently-played"

/** How much the Playlists tab shows in place of nothing. */
enum class LibraryPlaylist { ALWAYS, WHEN_EMPTY, OFF }

/** The stored library playlist mode; an unknown stored value reads as [LibraryPlaylist.ALWAYS]. */
class LibraryPlaylistSetting(private val store: EntityStore) {

    suspend fun stored(): LibraryPlaylist =
        LibraryPlaylist.entries.firstOrNull {
            it.name == (store.get(LIBRARY_PLAYLIST_KEY) as? JsonPrimitive)?.contentOrNull
        } ?: LibraryPlaylist.ALWAYS

    suspend fun set(mode: LibraryPlaylist) {
        if (mode == LibraryPlaylist.ALWAYS) store.remove(LIBRARY_PLAYLIST_KEY)
        else store.put(LIBRARY_PLAYLIST_KEY, JsonPrimitive(mode.name))
    }
}

/** True for both generated entries, which are served from history or the saved set and stored nowhere. */
internal fun isSynthetic(id: String): Boolean = id == RECENTLY_PLAYED_ID || id == LIKED_SONGS_ID

/** The generated origin, as the "cannot be edited" error names it. */
internal fun syntheticOrigin(id: String): String = if (id == RECENTLY_PLAYED_ID) {
    "Recently played is generated from your history"
} else {
    "Liked Songs is generated from your saved tracks"
}
