package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.metadata.ALBUM_LOOKUP_BUDGET
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import kotlin.coroutines.cancellation.CancellationException

/** Lookup requests one refresh spends per saved set. */
internal const val ACCOUNT_RESOLVE_LIMIT = 40

/** [rebuilt] plus the ids added to [now] since [before], minus the ids removed since then. */
internal fun mergeSaved(rebuilt: List<String>, before: List<String>, now: List<String>): List<String> {
    val added = now - before.toSet()
    val removed = before - now.toSet()
    return (rebuilt + added).distinct() - removed.toSet()
}

/** Rebuilds the saved album and artist sets from the rows of their mirrors. */
internal class SavedSetResolver(
    private val store: EntityStore,
    private val bindings: SavedBindings,
    private val albumLookup: AlbumLookup,
    private val representatives: Representatives,
) {

    private class Budget(var used: Int = 0) {
        val left get() = ACCOUNT_RESOLVE_LIMIT - used
        val spent get() = used >= ACCOUNT_RESOLVE_LIMIT
    }

    /** Rebuilds the sets from [rowsByKind] and merges them so changes since [previous], read before the walk,
     * survive. A kind whose [mirrorState] is false keeps its previous set. */
    suspend fun resolve(
        account: PipedAccount,
        start: AccountCacheState,
        previous: AccountCacheState?,
        rowsByKind: Map<SavedKind, List<String>>,
        mirrorState: Map<SavedKind, Boolean>,
    ) {
        fun rebuilt(kind: SavedKind, before: List<String>, resolved: List<String>?) =
            if (mirrorState[kind] == false) before else resolved.orEmpty().distinct()
        val albums = rowsByKind[SavedKind.ALBUM]?.takeIf { mirrorState[SavedKind.ALBUM] != false }
            ?.let { resolveKind(account, SavedKind.ALBUM, it, previous?.savedAlbums.orEmpty(), mirrorState[SavedKind.ALBUM] == true) }
        val artists = rowsByKind[SavedKind.ARTIST]?.takeIf { mirrorState[SavedKind.ARTIST] != false }
            ?.let { resolveKind(account, SavedKind.ARTIST, it, previous?.savedArtists.orEmpty(), mirrorState[SavedKind.ARTIST] == true) }
        val current = store.cachedAccountState() ?: return
        if (current.instance != start.instance || current.username != start.username) return
        store.cacheAccountState(
            current.copy(
                savedAlbums = mergeSaved(
                    rebuilt(SavedKind.ALBUM, previous?.savedAlbums.orEmpty(), albums),
                    previous?.savedAlbums.orEmpty(),
                    current.savedAlbums,
                ),
                savedArtists = mergeSaved(
                    rebuilt(SavedKind.ARTIST, previous?.savedArtists.orEmpty(), artists),
                    previous?.savedArtists.orEmpty(),
                    current.savedArtists,
                ),
            ),
        )
    }

    /** Visits every row, starting at a window that rotates across refreshes so the budget reaches the tail.
     * Known and cached rows cost nothing; only fresh lookups spend the budget. */
    private suspend fun resolveKind(
        account: PipedAccount,
        kind: SavedKind,
        rows: List<String>,
        previous: List<String>,
        complete: Boolean,
    ): List<String> {
        if (complete) bindings.pruneGone(kind, account, rows.toSet())
        val stamp = accountStamp(account)
        val budget = Budget()
        val resolved = mutableListOf<String>()
        // Rows shared by several entities appear once per entity; each occurrence binds its own entity.
        val seenReps = mutableMapOf<String, Int>()
        val start = if (rows.size > ACCOUNT_RESOLVE_LIMIT) ((epochMillis() / 900_000L) * ACCOUNT_RESOLVE_LIMIT).toInt() % rows.size else 0
        for (k in rows.indices) {
            val videoId = rows[(start + k) % rows.size]
            if (videoId.isEmpty()) continue
            val occurrence = seenReps[videoId] ?: 0
            seenReps[videoId] = occurrence + 1
            val known = previous.filter { bindings.repVideoOf(kind, it) == videoId }.getOrNull(occurrence)
            val id = when {
                known != null -> known.also { bindings.clearFailures(kind, account, videoId) }
                kind == SavedKind.ALBUM -> resolveAlbumRow(account, videoId, previous, budget)
                else -> resolveArtistRow(account, videoId, budget)
            } ?: continue
            bindings.addOwnerStamp(kind, id, stamp)
            resolved += id
        }
        return resolved
    }

    private suspend fun resolveAlbumRow(account: PipedAccount, videoId: String, previous: List<String>, budget: Budget): String? {
        val cached = store.cachedTrackAlbum(videoId)
        if (cached != null) {
            // A cached verdict skips the lookup, but only a previously saved album is claimed from it.
            if (cached == NO_ALBUM || cached !in previous) return null
            bindings.bindRep(SavedKind.ALBUM, cached, videoId)
            return cached
        }
        if (bindings.unresolvable(SavedKind.ALBUM, account, videoId) || budget.spent) return null
        // Each row gets at most one lookup's share, so a heavy row cannot starve the rows behind it.
        val share = minOf(budget.left, ALBUM_LOOKUP_BUDGET)
        val counter = AlbumLookup.RequestCounter(share)
        // Spending the whole share is the row's own doing; a cut-short share is the earlier rows' doing.
        val ownFault = share == ALBUM_LOOKUP_BUDGET
        return try {
            val albumId = albumLookup.resolveAlbumForVideo(videoId, counter = counter).albumId
            budget.used += counter.requests
            bindings.clearFailures(SavedKind.ALBUM, account, videoId)
            // A "none" verdict is cached too, so the video is not looked up every refresh.
            store.cacheTrackAlbum(videoId, albumId ?: NO_ALBUM)
            // An album unsaved while its row could not be mapped is not claimed back from that row.
            albumId?.takeUnless {
                bindings.goneVia(SavedKind.ALBUM, account, videoId, it)
            }?.also { bindings.bindRep(SavedKind.ALBUM, it, videoId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlbumLookup.LookupBudgetSpent) {
            if (ownFault) bindings.recordFailure(SavedKind.ALBUM, account, videoId)
            // Only this row's share is spent, so the rows behind it still get looked up.
            budget.used += counter.requests
            null
        } catch (e: Exception) {
            // A failed lookup still spent its requests.
            budget.used += counter.requests
            bindings.recordFailure(SavedKind.ALBUM, account, videoId)
            null
        }
    }

    private suspend fun resolveArtistRow(account: PipedAccount, videoId: String, budget: Budget): String? {
        if (bindings.unresolvable(SavedKind.ARTIST, account, videoId) || budget.spent) return null
        // Charged before the fetch, so a failed one counts too.
        budget.used++
        val channelId = representatives.artistChannelOf(account, videoId)
        if (channelId == null) {
            bindings.recordFailure(SavedKind.ARTIST, account, videoId)
            return null
        }
        bindings.clearFailures(SavedKind.ARTIST, account, videoId)
        if (bindings.goneVia(SavedKind.ARTIST, account, videoId, channelId)) return null
        bindings.bindRep(SavedKind.ARTIST, channelId, videoId)
        return channelId
    }
}
