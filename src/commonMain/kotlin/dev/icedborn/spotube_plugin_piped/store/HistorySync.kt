package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.PostOutcome
import dev.icedborn.spotube_plugin_piped.client.WalkResult
import dev.icedborn.spotube_plugin_piped.client.YOUTUBE_VIDEO_ID
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.listedCount
import dev.icedborn.spotube_plugin_piped.client.string
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

private val historyLog = Logger("PipedHistorySync")

/** Refusals in a row that end one upload run. */
private const val HISTORY_REFUSAL_RUN = 3

/** Positions a trim tries before it gives up on finding the oldest row. */
private const val HISTORY_HEAD_PROBES = 3

/** Syncs the play history through the account playlist "Spotube - History", a log with one row per scrobbled
 * play. The rules are in docs/design.md. */
internal class HistorySync(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val bindings: SavedBindings,
    private val history: PlayHistory,
    private val scope: CoroutineScope,
    private val sessionProvider: suspend () -> PipedAccount?,
) {

    // One upload, trim or merge at a time, so a merge never reads a row whose upload is not recorded yet.
    private val syncLock = Mutex()

    // Read-modify-write of the queue: a play can arrive while an upload run rewrites it.
    private val queueLock = Mutex()

    // Set by every play, so a run that is about to end goes around once more.
    private var requested = false

    // The log as the listing last showed it. While it stands, an upload does not read the listing.
    private var trusted: String? = null

    // Set when the queue could not be stored. A run would send the same play again, so uploads wait for the next start.
    private var stalled = false

    /** Bumped by every confirmed upload, so a walk that overlapped one is not merged. */
    var generation = 0
        private set

    /** Queues a play the host scrobbled and starts an upload. Signed out, the play stays on the device. */
    suspend fun onPlay(track: MetadataTrack) {
        if (!YOUTUBE_VIDEO_ID.matches(track.id)) return
        val account = sessionProvider() ?: return
        val queued = changeQueue(account) { it.copy(plays = (it.plays + PendingPlay(track.id)).takeLast(HISTORY_PENDING_LIMIT)) }
        if (queued) scope.launch { orNull("history upload") { upload() } }
    }

    /** Uploads the waiting plays. A call during a run makes that run go around again. */
    suspend fun upload() {
        requested = true
        if (!syncLock.tryLock()) return
        try {
            while (requested) {
                requested = false
                val account = sessionProvider() ?: break
                flush(account)
            }
        } finally {
            syncLock.unlock()
        }
    }

    /** The log among the account's listed playlists, bound on the way, or null when the account has none. */
    suspend fun playlistIn(account: PipedAccount, entries: List<JsonObject>): String? {
        val id = listedLog(entries)?.string("id") ?: return null
        bind(account, id)
        return id
    }

    /** Merges the rows other devices added since the last merge into the local history. [walk] must have reached
     * the end of the log. Skipped while an upload runs, or when one finished since [since] was read. */
    suspend fun merge(account: PipedAccount, playlistId: String, walk: WalkResult, since: Int) {
        if (!syncLock.tryLock()) return
        try {
            if (generation == since) mergeLocked(account, playlistId, walk)
        } finally {
            syncLock.unlock()
        }
    }

    /** After a refresh: removes the oldest rows of a log [excess] rows over its cap, then retries the waiting plays. */
    suspend fun settle(account: PipedAccount, playlistId: String?, excess: Int) {
        if (playlistId != null && excess > 0 && syncLock.tryLock()) {
            try {
                trim(account, playlistId, minOf(excess, HISTORY_TRIM_LIMIT))
            } finally {
                syncLock.unlock()
            }
        }
        upload()
    }

    private suspend fun flush(account: PipedAccount) {
        if (stalled || nextDue(account) == null) return
        val known = trusted?.takeIf { it == bindings.ownedPlaylistId(account, HISTORY_PLAYLIST_KEY) }
        var verified = known == null
        var playlistId = known ?: verify(account) ?: return
        var refusals = 0
        while (refusals < HISTORY_REFUSAL_RUN) {
            val play = nextDue(account) ?: return
            if (!stillSignedIn(account)) return
            val target = playlistId
            when (orNull("history upload") { http.addVideo(account, target, play.id) } ?: PostOutcome.UNCLEAR) {
                PostOutcome.CONFIRMED -> {
                    generation++
                    refusals = 0
                    val recorded = changeQueue(account) {
                        it.copy(plays = it.plays - play, sent = (it.sent + play.id).takeLast(HISTORY_LOG_CAP))
                    }
                    if (!recorded) return stall()
                }

                PostOutcome.UNCLEAR -> {
                    trusted = null
                    return
                }

                PostOutcome.REFUSED -> {
                    if (!verified) {
                        // The refusal may be about the playlist, so the listing is read before the video is blamed.
                        verified = true
                        val checked = verify(account) ?: return
                        if (checked != target) {
                            playlistId = checked
                            continue
                        }
                    }
                    if (!refuse(account, play)) return stall()
                    refusals++
                }
            }
        }
    }

    /** Reads the listing and returns the log, which it creates when the account has none. Null when the listing
     * or the create failed. */
    private suspend fun verify(account: PipedAccount): String? {
        trusted = null
        val listing = http.listing(account) ?: return null
        val entry = listedLog(listing)
        val id = entry?.string("id")
            ?: orNull("history playlist create") { http.createPlaylist(account, HISTORY_PLAYLIST_NAME) }
            ?: return null
        if (!bind(account, id)) return null
        // A device that finds the log without rows has seen all of it, so the rows that follow merge as new plays.
        if (entry == null || listedCount(entry) == 0) storeSeen(account, id, emptyList())
        trusted = id
        return id
    }

    /** The log among [entries]. Of several playlists with its name every device takes the smallest id, so devices
     * that created a log at the same moment settle on one. */
    private fun listedLog(entries: List<JsonObject>): JsonObject? =
        entries.filter { it.string("name") == HISTORY_PLAYLIST_NAME && it.string("id") != null }.minByOrNull { it.string("id").orEmpty() }

    /** Binds the log to [account]. A new playlist drops the record of own uploads, which belongs to the old one. */
    private suspend fun bind(account: PipedAccount, playlistId: String): Boolean {
        if (bindings.ownedPlaylistId(account, HISTORY_PLAYLIST_KEY) == playlistId) return true
        if (!bindings.bindPlaylist(HISTORY_PLAYLIST_KEY, account, playlistId)) return false
        changeQueue(account) { it.copy(sent = emptyList()) }
        return true
    }

    /** Counts a refusal: the play waits [HISTORY_RETRY_MS], and the last of [HISTORY_PUSH_ATTEMPTS] drops it.
     * False when the queue could not be stored. */
    private suspend fun refuse(account: PipedAccount, play: PendingPlay): Boolean {
        val next = play.copy(attempts = play.attempts + 1, retryAt = epochMillis() + HISTORY_RETRY_MS)
        val dropped = next.attempts >= HISTORY_PUSH_ATTEMPTS
        if (dropped) historyLog.w { "${account.instance} refused a play ${next.attempts} times, dropped" }
        return changeQueue(account) { box ->
            val at = box.plays.indexOf(play)
            if (at < 0) return@changeQueue box
            val plays = box.plays.toMutableList()
            if (dropped) plays.removeAt(at) else plays[at] = next
            box.copy(plays = plays)
        }
    }

    private fun stall() {
        stalled = true
        historyLog.w { "the upload queue could not be stored, uploads wait for the next start" }
    }

    private suspend fun mergeLocked(account: PipedAccount, playlistId: String, walk: WalkResult) {
        val known = seen(account, playlistId)
        val sent = queue(account).sent
        val fresh = withoutOwn(appendedRows(known?.rows.orEmpty(), walk.rawIds), sent)
        // The seen rows are stored first: a failure after this loses the new plays instead of counting them twice.
        if (!storeSeen(account, playlistId, walk.rawIds)) return
        if (sent.isNotEmpty()) changeQueue(account) { it.copy(sent = emptyList()) }
        val local = history.all().associate { it.track.id to it.track }
        val rows = walk.rows.associateBy { it.id }
        val tracks = fresh.distinct().mapNotNull { id -> (local[id] ?: store.cachedTrack(id) ?: rows[id])?.let { id to it } }.toMap()
        if (!history.mergeRemote(fresh.mapNotNull { tracks[it] }, firstContact = known == null)) {
            historyLog.w { "plays of other devices could not be stored" }
        }
    }

    /** Removes the oldest rows one by one and stops at the first removal the instance does not confirm. */
    private suspend fun trim(account: PipedAccount, playlistId: String, count: Int) {
        repeat(count) {
            if (!stillSignedIn(account) || !removeOldest(account, playlistId)) return
        }
    }

    /** Piped removes by stored position. An add that interleaved with a removal can leave no row at position 0,
     * so a refused removal is tried at the next positions. */
    private suspend fun removeOldest(account: PipedAccount, playlistId: String): Boolean {
        for (index in 0 until HISTORY_HEAD_PROBES) {
            val outcome = orNull("history trim") { http.removeRowOutcome(account, playlistId, index) } ?: PostOutcome.UNCLEAR
            if (outcome != PostOutcome.REFUSED) return outcome == PostOutcome.CONFIRMED
        }
        return false
    }

    private suspend fun nextDue(account: PipedAccount): PendingPlay? {
        val now = epochMillis()
        return queue(account).plays.firstOrNull { it.retryAt <= now }
    }

    /** The queue of [account]. One stamped with another account is not read, and the next write replaces it. */
    private suspend fun queue(account: PipedAccount): PendingPlays =
        store.getDecoded(HISTORY_PENDING_KEY, PendingPlays.serializer())?.takeIf { sameAccount(it, account) }
            ?: PendingPlays(account.instance, account.username)

    /** False when the queue could not be stored. */
    private suspend fun changeQueue(account: PipedAccount, change: (PendingPlays) -> PendingPlays): Boolean = queueLock.withLock {
        // Read into a val first: QuickJS cannot compile a suspend call nested in a call's arguments inside withLock.
        val current = queue(account)
        store.put(HISTORY_PENDING_KEY, json.encodeToJsonElement(change(current)))
    }

    private suspend fun seen(account: PipedAccount, playlistId: String): SeenLog? =
        store.getDecoded(HISTORY_SEEN_KEY, SeenLog.serializer())?.takeIf { sameAccount(it, account) && it.playlistId == playlistId }

    private suspend fun storeSeen(account: PipedAccount, playlistId: String, rows: List<String>): Boolean =
        store.put(HISTORY_SEEN_KEY, json.encodeToJsonElement(SeenLog(account.instance, account.username, playlistId, rows)))

    private suspend fun stillSignedIn(account: PipedAccount): Boolean =
        sessionProvider()?.let { sameAccount(it, account) && it.token == account.token } == true
}
