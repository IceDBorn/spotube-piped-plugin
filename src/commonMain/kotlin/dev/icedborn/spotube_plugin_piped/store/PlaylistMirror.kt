package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.postConfirmed
import dev.icedborn.spotube_plugin_piped.client.string
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val playlistMirrorLog = Logger("PipedPlaylistMirror")

/** Account copies of locally created playlists. */
internal class PlaylistMirror(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val bindings: SavedBindings,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    /** The remote copy a playlist edit goes to. [None] means there is no copy to edit, so the edit succeeds
     * locally; [Unverifiable] means a copy may exist but cannot be reached, so the edit fails and is retried. */
    private sealed interface Target {
        data class Owned(val account: PipedAccount, val playlistId: String) : Target

        object None : Target

        object Unverifiable : Target
    }

    private fun bindingKey(localId: String) = "$MIRROR_PLAYLIST_PREFIX$localId"

    // Playlists whose copy could not be bound this session; creating another would only orphan it too.
    private val unwritable = HashSet<String>()

    private fun pendingKey(localId: String) = "$MIRROR_PENDING_PREFIX$localId"

    private suspend fun mirrorId(account: PipedAccount, localId: String) = bindings.ownedPlaylistId(account, bindingKey(localId))

    private suspend fun unadoptedBare(account: PipedAccount, localId: String) =
        store.bareBinding(bindingKey(localId)) != null && !store.bareMissedBy(bindingKey(localId), account)

    private suspend fun targetOf(localId: String): Target {
        if (store.get(bindingKey(localId)) == null) return Target.None
        val account = sessionProvider() ?: return Target.Unverifiable
        mirrorId(account, localId)?.let { return Target.Owned(account, it) }
        // A binding from before account stamps may still be this account's copy until its listing says otherwise.
        if (unadoptedBare(account, localId)) return Target.Unverifiable
        // A binding owned by another account is not touched.
        return Target.None
    }

    /** Creates the account copy of a local playlist. A failure while signed in marks the playlist pending, and
     * the next refresh retries it. */
    suspend fun create(localId: String, name: String, videoIds: List<String>): String? {
        val account = sessionProvider() ?: return null
        if (mirrorId(account, localId) != null || localId in unwritable) return null
        val id = createMirror(account, localId, name, videoIds)
        if (id == null) {
            playlistMirrorLog.w { "mirror of playlist $localId could not be created, retrying on the next refresh" }
            store.put(pendingKey(localId), JsonPrimitive(true))
        }
        return id
    }

    /** Retries mirrors that failed at creation: an unbound one is created, a bound one gets its missing rows. */
    suspend fun retryPending(account: PipedAccount) {
        for (record in library.storedPlaylists()) {
            val key = pendingKey(record.id)
            if (store.get(key) == null || record.id in unwritable) continue
            val owned = mirrorId(account, record.id)
            // Another account's copy stays pending for that account, not duplicated under this one.
            if (owned == null && store.get(bindingKey(record.id)) != null) continue
            val done = if (owned == null) {
                createMirror(account, record.id, record.name, record.trackIds) != null
            } else {
                addTracks(record.id, record.trackIds)
            }
            if (done) store.remove(key)
        }
    }

    private suspend fun createMirror(account: PipedAccount, localId: String, name: String, videoIds: List<String>): String? = orNull {
        val id = http.createPlaylist(account, name) ?: return@orNull null
        // Bound before the add, so a failed add leaves a known empty mirror that the retry fills. An unbound copy
        // would be created again by the next edit, so it is deleted.
        if (!bindings.bindPlaylist(bindingKey(localId), account, id)) {
            // Storage refuses writes, so retries stop for this session instead of creating a copy each time.
            unwritable += localId
            val deleted =
                postConfirmed(http.userPost(account, "/user/playlists/delete", buildJsonObject { put("playlistId", id) }.toString()))
            if (!deleted) playlistMirrorLog.w { "copy $id of $localId could not be bound or deleted, and stays on the account" }
            return@orNull null
        }
        if (videoIds.isNotEmpty() && !http.addVideos(account, id, videoIds)) return@orNull null
        id
    }

    /** False when the mirror could not be brought to contain [videoIds]. */
    suspend fun addTracks(localId: String, videoIds: List<String>): Boolean = when (val t = targetOf(localId)) {
        is Target.Owned -> videoIds.isEmpty() || orNull {
            // A partial view would push rows twice, so only a complete walk drives the dedupe.
            val mirror = http.walkMirror(t.account, t.playlistId).takeIf { it.complete } ?: return@orNull false
            val known = mirror.videos.toHashSet()
            val fresh = videoIds.filterNot { it in known }.distinct()
            fresh.isEmpty() || http.addVideos(t.account, t.playlistId, fresh)
        } == true

        Target.None -> true

        Target.Unverifiable -> false
    }

    /** False when the mirror may still contain rows of [videoIds]. */
    suspend fun removeTracks(localId: String, videoIds: List<String>): Boolean = when (val t = targetOf(localId)) {
        is Target.Owned -> videoIds.isEmpty() || orNull {
            val mirror = http.walkMirror(t.account, t.playlistId).takeIf { it.complete } ?: return@orNull false
            val wanted = videoIds.toSet()
            val indexes = mirror.videos.indices.filter { mirror.videos[it] in wanted }
            indexes.sortedDescending().all { http.removeRow(t.account, t.playlistId, it) }
        } == true

        Target.None -> true

        Target.Unverifiable -> false
    }

    /** False when the rename did not take or could not be checked; true when there is no copy to rename. */
    suspend fun rename(localId: String, name: String): Boolean = when (val t = targetOf(localId)) {
        is Target.Owned -> orNull {
            val body = buildJsonObject {
                put("playlistId", t.playlistId)
                put("newName", name)
            }
            postConfirmed(http.userPost(t.account, "/user/playlists/rename", body.toString()))
        } == true

        Target.None -> true

        Target.Unverifiable -> false
    }

    /** Deletes the remote copy of [localId]. False keeps the playlist and binding for a retry, since dropping the
     * binding would orphan a surviving copy. */
    suspend fun delete(localId: String): Boolean {
        // Signed out, a live binding stays until a session can delete the copy.
        val account = sessionProvider() ?: return store.get(bindingKey(localId)) == null
        val uuid = mirrorId(account, localId)
        if (uuid != null) {
            val deleted = orNull {
                postConfirmed(http.userPost(account, "/user/playlists/delete", buildJsonObject { put("playlistId", uuid) }.toString()))
            } == true
            // A failed delete still completes when the listing proves the copy gone.
            val gone = deleted || http.listing(account)?.none { it.string("id") == uuid } == true
            if (!gone) return false
        } else if (unadoptedBare(account, localId)) {
            return false
        }
        store.remove(bindingKey(localId))
        store.clearBareMisses(bindingKey(localId))
        store.remove(pendingKey(localId))
        return true
    }
}
