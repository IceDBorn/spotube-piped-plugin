package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.core.PipedAccount

/** Ids an unsave may drop locally. [deleted] ids had their rows deleted; the others were proven absent.
 * [walkBacked] is false when no mirror was walked, which proves nothing about another account's mirror. */
internal class RemovalOutcome(val confirmed: List<String>, val deleted: Set<String>, val walkBacked: Boolean)

/** Rows scheduled for deletion from one mirror. */
private class RemovalPlan(
    // Rows bound to the removed ids.
    val wanted: Set<String>,
    // Rows a still-saved sibling of the same kind is bound to.
    val shared: Set<String>,
    val rowIndexes: MutableList<Pair<Int, String>>,
    // Rows a cached mapping resolves to a removed id, paired with that id.
    val orphanRows: List<Pair<String, String>>,
    val positionsOf: Map<String, List<Int>>,
)

/** The result of moving siblings off rows that the removed ids share with them. */
private class Repoint(val appended: List<String>, val released: Set<String>, val allPushed: Boolean)

/** Deletes the mirror rows of unsaved albums, artists and tracks, and decides which ids are confirmed gone. */
internal class MirrorRemoval(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val bindings: SavedBindings,
    private val cache: AccountCache,
    private val representatives: Representatives,
) {

    suspend fun removeRows(
        account: PipedAccount,
        kind: SavedKind,
        ids: List<String>,
        playlistId: String,
        mirror: MirrorRows,
    ): RemovalOutcome {
        val plan = plan(kind, ids, mirror)
        val repoint = if (mirror.ended && plan.positionsOf.isNotEmpty() && plan.shared.any { it in plan.wanted }) {
            repoint(account, kind, ids, playlistId, plan)
        } else {
            Repoint(emptyList(), emptySet(), allPushed = true)
        }
        // Rows released by the repoint were shared, so they are not scheduled yet.
        for (video in repoint.released) plan.positionsOf[video]?.forEach { plan.rowIndexes += it to video }
        val rows = plan.rowIndexes.distinct()
        var allDeleted = true
        val deletedCount = mutableMapOf<String, Int>()
        for ((index, video) in rows.sortedByDescending { it.first }) {
            if (http.removeRow(account, playlistId, index)) deletedCount[video] = (deletedCount[video] ?: 0) + 1 else allDeleted = false
        }
        if (allDeleted && repoint.allPushed && mirror.complete) {
            val deletedPositions = rows.map { it.first }.toHashSet()
            bindings.writeIndex(
                account,
                kind,
                playlistId,
                (mirror.videos + repoint.appended).filterIndexed { i, _ ->
                    i !in deletedPositions
                },
            )
        } else {
            bindings.dropIndex(kind)
        }
        // A video with duplicate rows counts as removed only when every scheduled row was deleted.
        val scheduled = rows.groupingBy { it.second }.eachCount()
        val removed = deletedCount.filter { (video, n) -> n >= (scheduled[video] ?: 0) }.keys
        return verdicts(account, kind, ids, plan, mirror, removed)
    }

    private suspend fun plan(kind: SavedKind, ids: List<String>, mirror: MirrorRows): RemovalPlan {
        val wanted = ids.mapNotNull { bindings.repVideoOf(kind, it) }.toHashSet()
        val shared = siblings(kind, ids).mapNotNull { bindings.repVideoOf(kind, it) }.toHashSet()
        val removable = wanted - shared
        val removedIds = ids.toHashSet()
        val rowIndexes = mutableListOf<Pair<Int, String>>()
        val orphanRows = mutableListOf<Pair<String, String>>()
        val positionsOf = mutableMapOf<String, MutableList<Int>>()
        mirror.videos.forEachIndexed { position, video ->
            if (video.isEmpty()) return@forEachIndexed
            positionsOf.getOrPut(video) { mutableListOf() } += position
            // A stale duplicate row still resolving to a removed id goes too, or the next refresh brings the id back.
            val orphanOwner = if (video in shared || video in removable) null else resolvedOwner(kind, video)?.takeIf { it in removedIds }
            if (orphanOwner != null) orphanRows += orphanOwner to video
            if (video in removable || orphanOwner != null) rowIndexes += position to video
        }
        return RemovalPlan(wanted, shared, rowIndexes, orphanRows, positionsOf)
    }

    private suspend fun siblings(kind: SavedKind, ids: List<String>): List<String> = cache.allSavedIds(kind).filterNot { it in ids }

    private suspend fun resolvedOwner(kind: SavedKind, video: String): String? = when (kind) {
        SavedKind.ALBUM -> store.cachedTrackAlbum(video)
        SavedKind.ARTIST -> representatives.cachedArtistIdOf(video)?.let(::canonicalArtistId)
        SavedKind.TRACK -> null
    }

    /** Moves every sibling on a row the removed ids share to a fresh row of its own, so the shared row can go.
     * A fresh row never duplicates a row already in the mirror or one scheduled for deletion. */
    private suspend fun repoint(account: PipedAccount, kind: SavedKind, ids: List<String>, playlistId: String, plan: RemovalPlan): Repoint {
        val used = plan.positionsOf.keys.toMutableSet()
        val scheduled = plan.rowIndexes.map { it.second }.toHashSet()
        val stillNeeded = mutableSetOf<String>()
        val appended = mutableListOf<String>()
        var allPushed = true
        val siblingsByRep = mutableMapOf<String, MutableList<String>>()
        for (sibling in siblings(kind, ids)) {
            val rep = bindings.repVideoOf(kind, sibling) ?: continue
            if (rep in plan.shared && rep in plan.wanted) siblingsByRep.getOrPut(rep) { mutableListOf() } += sibling
        }
        for ((video, siblings) in siblingsByRep) {
            for (sibling in siblings) {
                val newRep = representatives.representativeVideo(kind, sibling, used)
                if (newRep == null || newRep in used || newRep in plan.shared || newRep in scheduled) {
                    stillNeeded += video
                    continue
                }
                if (!http.addVideos(account, playlistId, listOf(newRep))) {
                    allPushed = false
                    stillNeeded += video
                    continue
                }
                appended += newRep
                used += newRep
                bindings.bindRep(kind, sibling, newRep)
            }
        }
        val released = plan.shared.filter { it in plan.wanted && it !in stillNeeded }.toSet()
        return Repoint(appended, released, allPushed)
    }

    /** Per id: deleted when its rows went and no surviving row could bring it back; absent when a complete walk
     * holds no row of it. The caller gates absent ids by account ownership. */
    private suspend fun verdicts(
        account: PipedAccount,
        kind: SavedKind,
        ids: List<String>,
        plan: RemovalPlan,
        mirror: MirrorRows,
        removed: Set<String>,
    ): RemovalOutcome {
        val walked = plan.positionsOf.keys
        val orphansRemovedByOwner = plan.orphanRows.filter { it.second in removed }.groupBy({ it.first }, { it.second })
        // Which saved entities each walked video stands for.
        val ownerByVideo = mutableMapOf<String, MutableSet<String>>()
        for (savedId in cache.allSavedIds(kind)) {
            bindings.repVideoOf(kind, savedId)?.let { ownerByVideo.getOrPut(it) { mutableSetOf() } += kind.canonical(savedId) }
        }
        val deleted = mutableSetOf<String>()
        // A latched or "none" row is let through, but its later lookup could map it to a removed id.
        val excused = if (kind == SavedKind.TRACK) emptyList() else walked.filter { it !in removed && excusedUnmapped(account, kind, it) }
        val confirmed = ids.filter { id ->
            val (isDeleted, isAbsent) = if (kind == SavedKind.TRACK) {
                (id in removed) to (mirror.complete && id !in walked)
            } else {
                val canonical = kind.canonical(id)
                val rep = bindings.repVideoOf(kind, id)
                val noSurvivor = walked.none { it !in removed && survivorBlocks(account, kind, canonical, it, ownerByVideo) }
                val rowsGone = if (rep != null) rep in removed else orphansRemovedByOwner[canonical].orEmpty().isNotEmpty()
                // An unbound id, such as one saved while signed out, is absent when no row could stand for it.
                val absent = mirror.complete && noSurvivor && (rep == null || rep !in walked)
                (noSurvivor && rowsGone) to absent
            }
            if (isDeleted) deleted += id
            isDeleted || isAbsent
        }
        if (excused.isNotEmpty() && confirmed.isNotEmpty()) bindings.markGone(kind, account, excused, confirmed)
        return RemovalOutcome(confirmed, deleted, walkBacked = true)
    }

    /** A row that [survivorBlocks] lets through without knowing what it maps to. A "none" verdict of any age is
     * such a row, since a fresh one expires and can map to a removed id on a later lookup. */
    private suspend fun excusedUnmapped(account: PipedAccount, kind: SavedKind, video: String): Boolean {
        if (bindings.unresolvable(kind, account, video)) return true
        return kind == SavedKind.ALBUM && store.hasNoAlbumVerdict(video)
    }

    /** Whether surviving row [video] could bring [canonical] back at the next refresh. A row mapped to another
     * entity cannot; an unmapped row can unless other entities own it. A latched or "none" row cannot. */
    private suspend fun survivorBlocks(
        account: PipedAccount,
        kind: SavedKind,
        canonical: String,
        video: String,
        ownerByVideo: Map<String, Set<String>>,
    ): Boolean {
        if (kind == SavedKind.TRACK || bindings.unresolvable(kind, account, video)) return false
        val mapped = when (kind) {
            SavedKind.ALBUM -> {
                // Any "none" verdict, even an expired one, proved no album for this row once.
                if (store.hasNoAlbumVerdict(video)) return false
                store.cachedTrackAlbum(video)
            }

            else -> representatives.cachedArtistIdOf(video)?.let(::canonicalArtistId)
        }
        if (mapped != null) return mapped == canonical
        val owners = ownerByVideo[video].orEmpty()
        return owners.isEmpty() || canonical in owners
    }
}
