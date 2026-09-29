package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.listedCount
import dev.icedborn.spotube_plugin_piped.client.string
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlin.coroutines.cancellation.CancellationException

private val mirrorLog = Logger("PipedSavedLibrary")

/** Writes saves and unsaves of tracks, albums and artists through to the account's mirror playlists. */
internal class MirrorWriter(
    private val http: AccountHttp,
    private val library: LocalLibrary,
    private val bindings: SavedBindings,
    private val cache: AccountCache,
    private val representatives: Representatives,
    private val removal: MirrorRemoval,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    suspend fun save(kind: SavedKind, ids: List<String>) {
        val account = sessionProvider() ?: return
        if (ids.isEmpty()) return
        bindings.clearGone(kind, account, ids)
        bindings.withMirrorLock(kind) { saveLocked(account, kind, ids) }
    }

    /** A failed mirror write throws: an id recorded as saved without a mirror row could never be unsaved. */
    private suspend fun saveLocked(account: PipedAccount, kind: SavedKind, ids: List<String>) {
        val bound = try {
            pushSave(account, kind, ids.distinct())
        } catch (e: Exception) {
            bindings.dropIndex(kind)
            if (e is CancellationException) throw e
            mirrorLog.w { "save ${kind.playlistName} to ${account.instance} failed: ${e.message}" }
            throw IllegalStateException("Piped mirror save failed")
        }
        cache.addToSnapshot(account, kind, bound)
        // The stamp claims the id for this account even after its mirror row is gone.
        val stamp = accountStamp(account)
        bound.forEach { bindings.addOwnerStamp(kind, it, stamp) }
    }

    /** Gives every id its own mirror row and returns the ids now bound to one. */
    private suspend fun pushSave(account: PipedAccount, kind: SavedKind, ids: List<String>): List<String> {
        val mirror = ensurePlaylist(account, kind)
        val rows = mirrorRows(account, kind, mirror.id, mirror.listedCount)
        // A partial view would push rows twice.
        if (!rows.complete) throw IllegalStateException("mirror membership walk ambiguous")
        val present = rows.videos.filter { it.isNotEmpty() }.toHashSet()
        val used = present.toHashSet()
        val fresh = mutableListOf<Pair<String, String>>()
        for (id in ids) {
            val existing = bindings.repVideoOf(kind, id)?.takeIf { it.isNotEmpty() && it in used }
            if (existing != null) {
                bindings.bindRep(kind, id, existing)
                continue
            }
            val rep = representatives.representativeVideo(kind, id, used)
                ?: throw IllegalStateException("Piped mirror save: no representative video")
            used += rep
            fresh += id to rep
        }
        if (fresh.isNotEmpty()) {
            // A track row is the track id, so a present one is not pushed again; the add endpoint does not dedupe.
            val toPush = fresh.map { it.second }.filterNot { kind == SavedKind.TRACK && it in present }
            if (toPush.isNotEmpty() && !http.addVideos(account, mirror.id, toPush)) throw IllegalStateException("mirror add failed")
            bindings.writeIndex(account, kind, mirror.id, rows.videos + toPush)
            // Bound only after the push, so no id looks saved without a row.
            fresh.forEach { (id, rep) -> bindings.bindRep(kind, id, rep) }
        }
        return ids
    }

    suspend fun remove(kind: SavedKind, ids: List<String>) {
        if (ids.isEmpty()) return
        // Signed out there is no session to delete remote rows; the unsave completes once one exists.
        val account = sessionProvider() ?: return
        bindings.withMirrorLock(kind) { removeLocked(account, kind, ids) }
    }

    /** Throws when the mirror may still hold a row of any id, so the host shows the unsave as failed. */
    private suspend fun removeLocked(account: PipedAccount, kind: SavedKind, ids: List<String>) {
        val outcome = try {
            removeRemote(account, kind, ids)
        } catch (e: Exception) {
            // The host shows the error, and the id stays saved until an unsave succeeds.
            bindings.dropIndex(kind)
            if (e !is CancellationException) mirrorLog.w { "remove ${kind.playlistName} from ${account.instance} failed: ${e.message}" }
            throw e
        }
        val confirmed = outcome.confirmed.filter { claimable(account, kind, it, outcome) }
        // A deleted row proves only this account's row is gone; the device-wide binding may serve another account.
        confirmed.filterNot { it in outcome.deleted }.forEach { bindings.unbindRep(kind, it) }
        cache.dropFromSnapshot(kind, confirmed)
        removeFromLibrary(kind, confirmed)
        val unconfirmed = ids.size - outcome.confirmed.size
        if (unconfirmed > 0) {
            mirrorLog.w { "remove ${kind.playlistName} from ${account.instance}: $unconfirmed of ${ids.size} not confirmed" }
            throw IllegalStateException("Piped mirror unsave not confirmed")
        }
    }

    private suspend fun removeRemote(account: PipedAccount, kind: SavedKind, ids: List<String>): RemovalOutcome {
        // A removal never creates or adopts a mirror, and the listing checks the binding is still live.
        val stored = bindings.storedPlaylistId(account, kind)
        val listing = http.listing(account)
        val listed = listing?.firstOrNull { it.string("id") == stored }
        // An unreachable listing keeps a known binding, so a transient failure does not block the unsave.
        val playlistId = stored?.takeIf { listing == null || listed != null }
        if (playlistId == null) {
            // No mirror on this account: nothing survives remotely, but the verdict is not backed by a walk.
            return RemovalOutcome(if (listing != null) ids else emptyList(), emptySet(), walkBacked = false)
        }
        val rows = mirrorRows(account, kind, playlistId, listed?.let(::listedCount))
        return removal.removeRows(account, kind, ids, playlistId, rows)
    }

    /** Whether this account may drop [id] locally. A deleted row or an unbound id always may. An absent row
     * proves only that this account's mirror lacks it, so the id must belong to this account. */
    private suspend fun claimable(account: PipedAccount, kind: SavedKind, id: String, outcome: RemovalOutcome): Boolean {
        if (id in outcome.deleted || bindings.repVideoOf(kind, id) == null) return true
        if (cache.cachedState() == null) return false
        val stamps = bindings.ownerStampsOf(kind, id)
        return id in cache.ownedIds(kind) || (outcome.walkBacked && stamps.isEmpty()) || accountStamp(account) in stamps
    }

    /** Deletes [ids] from the device library. Signed in, ids another account owns survive. */
    suspend fun removeLibraryEntries(kind: SavedKind, ids: List<String>) {
        if (ids.isEmpty()) return
        val session = sessionProvider() ?: return removeFromLibrary(kind, ids)
        val claimed = cache.cachedState() != null
        val owned = cache.ownedIds(kind).toHashSet()
        // Ownerless unbound ids stay removable; unstamped bound ids need the walk evidence only remove() has.
        val local = ids.filter { id ->
            bindings.repVideoOf(kind, id) == null ||
                (claimed && (id in owned || accountStamp(session) in bindings.ownerStampsOf(kind, id)))
        }
        removeFromLibrary(kind, local)
    }

    private suspend fun removeFromLibrary(kind: SavedKind, ids: List<String>) = when (kind) {
        SavedKind.TRACK -> library.removeTracks(ids)
        SavedKind.ALBUM -> library.removeAlbums(ids)
        SavedKind.ARTIST -> library.removeArtists(ids.map(::canonicalArtistId))
    }

    /** The raw rows of a mirror: its index when current, else a full walk. */
    private suspend fun mirrorRows(account: PipedAccount, kind: SavedKind, playlistId: String, listedCount: Int?): MirrorRows {
        val index = bindings.indexOf(account, kind, playlistId, listedCount) ?: return http.walkMirror(account, playlistId)
        return MirrorRows(index.videos, complete = true, ended = true)
    }

    /** The mirror playlist of [kind], checked against the listing so a mirror deleted on the web is replaced. */
    private suspend fun ensurePlaylist(account: PipedAccount, kind: SavedKind): MirrorPlaylist {
        val stored = bindings.storedPlaylistId(account, kind)
        // An unreadable listing keeps the last binding and never creates a duplicate.
        val listing = http.listing(account)
            ?: return stored?.let { MirrorPlaylist(it, null) } ?: throw IllegalStateException("Piped playlist listing unavailable")
        listing.firstOrNull { stored != null && it.string("id") == stored }?.let { return MirrorPlaylist(stored!!, listedCount(it)) }
        val named = listing.firstOrNull { it.string("name") == kind.playlistName }
        val existing = named?.string("id")
        if (named != null && existing != null) {
            bindings.bindPlaylist(bindings.playlistKey(kind), account, existing)
            return MirrorPlaylist(existing, listedCount(named))
        }
        val id = http.createPlaylist(account, kind.playlistName) ?: throw IllegalStateException("Piped playlist create returned no id")
        bindings.bindPlaylist(bindings.playlistKey(kind), account, id)
        bindings.writeIndex(account, kind, id, emptyList())
        return MirrorPlaylist(id, 0)
    }
}
