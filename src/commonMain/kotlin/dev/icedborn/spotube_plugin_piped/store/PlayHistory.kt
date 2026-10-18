package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement

private const val HISTORY_KEY = "history.tracks"
private const val HISTORY_LIMIT = 200

/** The host resolves audio again for seeks and quality changes; repeats within this window are one play. */
private const val REPLAY_WINDOW_MS = 60_000L

@Serializable
internal data class PlayedTrack(val track: MetadataTrack, val plays: Int = 1, val lastPlayedAt: Long = 0L)

/** [local] with the plays other devices logged in [remote], oldest first. New plays go on top and add to the count.
 * At first contact the rows are older plays: they go below the device's history and a count only rises to them. */
internal fun mergeRemotePlays(local: List<PlayedTrack>, remote: List<MetadataTrack>, firstContact: Boolean): List<PlayedTrack> {
    val rows = remote.groupingBy { it.id }.eachCount()
    val mine = local.associateBy { it.track.id }
    // One entry per logged track, newest first. An entry keeps the device's own play time, which guards its replay window.
    val logged = remote.asReversed().distinctBy { it.id }.map { mine[it.id] ?: PlayedTrack(it, 0, 0L) }
    val merged = if (firstContact) {
        local.map { it.copy(plays = maxOf(it.plays, rows[it.track.id] ?: 0)) } +
            logged.filterNot { it.track.id in mine }.map { it.copy(plays = rows.getValue(it.track.id)) }
    } else {
        logged.map { it.copy(plays = it.plays + rows.getValue(it.track.id)) } + local.filterNot { it.track.id in rows }
    }
    return merged.take(HISTORY_LIMIT)
}

/** [local] raised to [merged]: a count or play time only grows, so plays not shared yet stay. Newest play first.
 * A play time from another device also moves the replay window of [PlayHistory.record]. */
internal fun combinePlays(local: List<PlayedTrack>, merged: List<PlayedTrack>): List<PlayedTrack> {
    val byId = LinkedHashMap<String, PlayedTrack>()
    local.forEach { byId[it.track.id] = it }
    for (remote in merged) {
        val mine = byId[remote.track.id]
        byId[remote.track.id] = mine?.copy(
            plays = maxOf(mine.plays, remote.plays),
            lastPlayedAt = maxOf(mine.lastPlayedAt, remote.lastPlayedAt),
        ) ?: remote
    }
    return byId.values.sortedByDescending { it.lastPlayedAt }.take(HISTORY_LIMIT)
}

/** The host sends plugins no play history, so the plugin logs every track the audio API resolves.
 * A track the host preloads counts too, even if it is skipped. */
internal class PlayHistory(private val store: EntityStore) {

    suspend fun all(): List<PlayedTrack> = store.getDecoded(HISTORY_KEY, ListSerializer(PlayedTrack.serializer())).orEmpty()

    // Read-modify-write, so a scrobble landing while an audio resolve writes cannot drop either play.
    private val writeLock = Mutex()

    /** False when the play was not counted: it fell into the replay window of the last one, or the write failed. */
    suspend fun record(track: MetadataTrack): Boolean {
        if (track.id.isBlank()) return false
        return store.pinned {
            writeLock.withLock {
                val now = epochMillis()
                val entries = all()
                val previous = entries.firstOrNull { it.track.id == track.id }
                if (previous != null && now - previous.lastPlayedAt < REPLAY_WINDOW_MS) return@withLock false
                val updated = PlayedTrack(track, (previous?.plays ?: 0) + 1, now)
                val rest = entries.filterNot { it.track.id == track.id }
                val encoded = json.encodeToJsonElement((listOf(updated) + rest).take(HISTORY_LIMIT))
                store.put(HISTORY_KEY, encoded)
            }
        }
    }

    /** Stores [mergeRemotePlays] of [remote]. False when the write failed. */
    suspend fun mergeRemote(remote: List<MetadataTrack>, firstContact: Boolean): Boolean {
        if (remote.isEmpty()) return true
        return store.pinned {
            writeLock.withLock {
                // Read into a val first: QuickJS cannot compile a suspend call nested in a call's arguments inside withLock.
                val local = all()
                store.put(HISTORY_KEY, json.encodeToJsonElement(mergeRemotePlays(local, remote, firstContact)))
            }
        }
    }

    /** Stores [combinePlays] of [merged]. False when the write failed. */
    suspend fun mergeCounts(merged: List<PlayedTrack>): Boolean = store.pinned {
        writeLock.withLock {
            // Read into a val first: QuickJS cannot compile a suspend call nested in a call's arguments inside withLock.
            val local = all()
            val combined = combinePlays(local, merged)
            if (combined == local) return@withLock true
            store.put(HISTORY_KEY, json.encodeToJsonElement(combined))
        }
    }

    /** Most recent first, one entry per track. */
    suspend fun recentTracks(limit: Int): List<MetadataTrack> = all().take(limit).map { it.track }

    /** Artists by total plays, most played first; ties go to the most recent. */
    suspend fun topArtists(limit: Int): List<MetadataArtist.Basic> {
        val scores = LinkedHashMap<String, Pair<MetadataArtist.Basic, Int>>()
        for (entry in all()) {
            val artist = entry.track.artists.firstOrNull() ?: continue
            val key = canonicalArtistId(artist.id)
            val current = scores[key]
            scores[key] = (current?.first ?: artist) to ((current?.second ?: 0) + entry.plays)
        }
        return scores.values.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
