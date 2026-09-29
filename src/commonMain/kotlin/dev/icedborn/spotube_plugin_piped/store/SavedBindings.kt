package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.listedCount
import dev.icedborn.spotube_plugin_piped.core.AccountIdentity
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Failed resolutions in a row before a video is latched unresolvable. */
private const val RESOLUTION_RETRY_LIMIT = 3

/** How long a latch holds before the video is tried again. */
internal const val RESOLUTION_RETRY_COOLDOWN_MS = 3_600_000L

/** The three playlists saved items are written through to. */
internal enum class SavedKind(val playlistName: String) {
    ALBUM("Spotube - Albums"),
    ARTIST("Spotube - Artists"),
    TRACK("Spotube - Favorites"),
    ;

    val key: String get() = name.lowercase()

    /** Artist ids compare in canonical form; other ids as they are. */
    fun canonical(id: String): String = if (this == ARTIST) canonicalArtistId(id) else id
}

/** Mirror playlist uuid, bound to the account that owns it. */
@Serializable
internal data class StoredPlaylistId(override val instance: String, override val username: String, val playlistId: String) :
    AccountIdentity

/** The ordered raw rows of a mirror as last seen; an unknown row is "". Row positions are the indexes Piped
 * deletes by. Trusted only while the listing reports the same count. */
@Serializable
internal data class MirrorIndex(
    override val instance: String,
    override val username: String,
    val playlistId: String,
    val videos: List<String>,
) : AccountIdentity

/** Raw rows of a mirror. [complete] means the rows are the whole mirror; [ended] means the walk reached a null token. */
internal class MirrorRows(val videos: List<String>, val complete: Boolean, val ended: Boolean)

/** A mirror playlist id with the row count the listing reported, or null when the listing was not read. */
internal data class MirrorPlaylist(val id: String, val listedCount: Int?)

/** The uuid of a binding written before account stamps, or null for a stamped or missing one. */
internal suspend fun EntityStore.bareBinding(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun bareMissKey(key: String) = "$BARE_MISS_PREFIX$key"

/** True when [account]'s listing was checked and did not hold the bare binding under [key]. */
internal suspend fun EntityStore.bareMissedBy(key: String, account: PipedAccount): Boolean =
    (get(bareMissKey(key)) as? JsonArray).orEmpty().any { it.jsonPrimitive.contentOrNull == accountStamp(account) }

internal suspend fun EntityStore.recordBareMiss(key: String, account: PipedAccount) {
    val stamps = (get(bareMissKey(key)) as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
    put(bareMissKey(key), JsonArray((stamps + accountStamp(account)).distinct().map(::JsonPrimitive)))
}

internal suspend fun EntityStore.clearBareMisses(key: String) {
    if (get(bareMissKey(key)) != null) remove(bareMissKey(key))
}

/** The keys a bare binding can sit under: the three mirrors and the copies of [localIds]. */
internal fun bindingKeys(localIds: List<String>): List<String> =
    SavedKind.entries.map { "saved.playlist:${it.key}" } + localIds.map { "$MIRROR_PLAYLIST_PREFIX$it" }

/** Stamps the bare bindings under [keys] whose uuid [listed] holds with [account], and records it as not owning
 * the rest. */
internal suspend fun EntityStore.adoptBareBindings(account: PipedAccount, listed: Set<String>, keys: List<String>) {
    for (key in keys) {
        val uuid = bareBinding(key) ?: continue
        if (uuid in listed) {
            put(key, json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, uuid)))
            clearBareMisses(key)
        } else if (!bareMissedBy(key, account)) {
            recordBareMiss(key, account)
        }
    }
}

private val GONE_SERIALIZER = MapSerializer(String.serializer(), SetSerializer(String.serializer()))

/** Device storage for the saved-item mirrors: playlist bindings, the row each entity owns, owner stamps, the
 * mirror index and the resolution latches. */
internal class SavedBindings(private val store: EntityStore) {

    // One writer per mirror, so its index follows every POST in order.
    private val mirrorLocks = SavedKind.entries.associateWith { Mutex() }

    // Bumped by every write to a mirror, so a refresh walk that overlapped one does not overwrite the index.
    private val generations = SavedKind.entries.associateWith { 0 }.toMutableMap()

    fun generation(kind: SavedKind): Int = generations.getValue(kind)

    fun walkKey(kind: SavedKind) = "mirror.walk:${kind.key}"

    private suspend fun changed(kind: SavedKind) {
        generations[kind] = generation(kind) + 1
        // A partial refresh walk read rows from before this write.
        store.remove(walkKey(kind))
    }

    suspend fun <T> withMirrorLock(kind: SavedKind, block: suspend () -> T): T = mirrorLocks.getValue(kind).withLock { block() }

    fun playlistKey(kind: SavedKind) = "saved.playlist:${kind.key}"

    /** False when the binding could not be stored. */
    suspend fun bindPlaylist(key: String, account: PipedAccount, playlistId: String): Boolean {
        val stored = store.put(key, json.encodeToJsonElement(StoredPlaylistId(account.instance, account.username, playlistId)))
        store.clearBareMisses(key)
        return stored
    }

    /** Playlist uuid stored under [key], only when the binding belongs to [account]. */
    suspend fun ownedPlaylistId(account: PipedAccount, key: String): String? =
        store.getDecoded(key, StoredPlaylistId.serializer())?.takeIf { sameAccount(it, account) }?.playlistId

    suspend fun storedPlaylistId(account: PipedAccount, kind: SavedKind): String? = ownedPlaylistId(account, playlistKey(kind))

    private fun repKey(kind: SavedKind, entityId: String) = "saved.rep:${kind.key}:$entityId"

    /** The mirror row video bound to [entityId]. */
    suspend fun repVideoOf(kind: SavedKind, entityId: String): String? = store.get(repKey(kind, entityId))?.jsonPrimitive?.contentOrNull

    suspend fun bindRep(kind: SavedKind, entityId: String, video: String) = store.put(repKey(kind, entityId), JsonPrimitive(video))

    suspend fun unbindRep(kind: SavedKind, entityId: String) = store.remove(repKey(kind, entityId))

    private fun ownerKey(kind: SavedKind, id: String) = "saved.owner:${kind.key}:${kind.canonical(id)}"

    /** Account stamps that saved [id] on this device; a second account appends its own. Empty means ownerless. */
    suspend fun ownerStampsOf(kind: SavedKind, id: String): List<String> {
        val raw = store.get(ownerKey(kind, id)) as? JsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<String>>(raw) }.getOrNull().orEmpty()
    }

    suspend fun addOwnerStamp(kind: SavedKind, id: String, stamp: String) {
        store.put(ownerKey(kind, id), json.encodeToJsonElement((ownerStampsOf(kind, id) + stamp).distinct()))
    }

    private fun indexKey(kind: SavedKind) = "mirror.index:${kind.key}"

    /** Records a writer's change to the mirror and the rows it left. */
    suspend fun writeIndex(account: PipedAccount, kind: SavedKind, playlistId: String, videos: List<String>) {
        changed(kind)
        store.put(indexKey(kind), json.encodeToJsonElement(MirrorIndex(account.instance, account.username, playlistId, videos)))
    }

    /** Records a writer's change to the mirror whose rows are no longer known. */
    suspend fun dropIndex(kind: SavedKind) {
        changed(kind)
        store.remove(indexKey(kind))
    }

    /** Stores a refresh walk as the index, or drops the index when [videos] is null. Skipped when a writer
     * changed the mirror since [since], because the walk may predate that write. Call under the mirror lock. */
    suspend fun refreshIndex(account: PipedAccount, kind: SavedKind, playlistId: String, videos: List<String>?, since: Int) {
        if (generation(kind) != since) return
        if (videos == null) {
            store.remove(indexKey(kind))
        } else {
            store.put(indexKey(kind), json.encodeToJsonElement(MirrorIndex(account.instance, account.username, playlistId, videos)))
        }
    }

    /** The index of [kind]'s mirror when it belongs to [account] and [playlistId] and matches [listedCount]. */
    suspend fun indexOf(account: PipedAccount, kind: SavedKind, playlistId: String, listedCount: Int?): MirrorIndex? {
        val index = store.getDecoded(indexKey(kind), MirrorIndex.serializer()) ?: return null
        val current = sameAccount(index, account) && index.playlistId == playlistId && listedCount == index.videos.size
        return index.takeIf { current }
    }

    private fun latchKey(kind: SavedKind, account: PipedAccount, videoId: String) =
        "unresolvable:${kind.key}:${accountStamp(account)}:$videoId"

    private fun retryKey(kind: SavedKind, account: PipedAccount, videoId: String) =
        "resolutionRetry:${kind.key}:${accountStamp(account)}:$videoId"

    /** True while the video's latch is in its cooldown; an expired latch is cleared so the row is tried again. */
    suspend fun unresolvable(kind: SavedKind, account: PipedAccount, videoId: String): Boolean {
        val key = latchKey(kind, account, videoId)
        val at = store.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return false
        if (at + RESOLUTION_RETRY_COOLDOWN_MS > epochMillis()) return true
        store.remove(key)
        return false
    }

    private fun goneKey(kind: SavedKind, account: PipedAccount) = "saved.gone:${kind.key}:${accountStamp(account)}"

    /** Per mirror row, the ids an unsave confirmed while that row survived unmapped. */
    private suspend fun goneRows(kind: SavedKind, account: PipedAccount): Map<String, Set<String>> =
        store.getDecoded(goneKey(kind, account), GONE_SERIALIZER).orEmpty()

    private suspend fun writeGone(kind: SavedKind, account: PipedAccount, rows: Map<String, Set<String>>) {
        if (rows.isEmpty()) {
            store.remove(
                goneKey(kind, account),
            )
        } else {
            store.put(goneKey(kind, account), json.encodeToJsonElement(GONE_SERIALIZER, rows))
        }
    }

    /** Keeps each of [rows] from bringing back [ids] when a later lookup maps it to one of them. */
    suspend fun markGone(kind: SavedKind, account: PipedAccount, rows: Collection<String>, ids: Collection<String>) {
        val gone = goneRows(kind, account).toMutableMap()
        rows.forEach { gone[it] = gone[it].orEmpty() + ids.map(kind::canonical) }
        writeGone(kind, account, gone)
    }

    suspend fun goneVia(kind: SavedKind, account: PipedAccount, video: String, id: String): Boolean =
        kind.canonical(id) in goneRows(kind, account)[video].orEmpty()

    /** Drops the marks of rows that left the mirror, given every row of a complete walk. */
    suspend fun pruneGone(kind: SavedKind, account: PipedAccount, liveRows: Set<String>) {
        val gone = goneRows(kind, account)
        if (gone.keys.any { it !in liveRows }) writeGone(kind, account, gone.filterKeys { it in liveRows })
    }

    /** A save of [ids] lifts their marks. */
    suspend fun clearGone(kind: SavedKind, account: PipedAccount, ids: Collection<String>) {
        val gone = goneRows(kind, account)
        val canonical = ids.map(kind::canonical).toSet()
        if (gone.values.none { set -> set.any { it in canonical } }) return
        writeGone(kind, account, gone.mapValues { it.value - canonical }.filterValues { it.isNotEmpty() })
    }

    /** Counts a failed resolution; the last of [RESOLUTION_RETRY_LIMIT] in a row latches the video. */
    suspend fun recordFailure(kind: SavedKind, account: PipedAccount, videoId: String) {
        val key = retryKey(kind, account, videoId)
        val attempts = (store.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1
        if (attempts >= RESOLUTION_RETRY_LIMIT) {
            store.put(latchKey(kind, account, videoId), JsonPrimitive(epochMillis()))
            store.remove(key)
        } else {
            store.put(key, JsonPrimitive(attempts))
        }
    }

    suspend fun clearFailures(kind: SavedKind, account: PipedAccount, videoId: String) = store.remove(retryKey(kind, account, videoId))
}
