package dev.icedborn.spotube_plugin_piped_metadata

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
data class PlayedTrack(
    val track: MetadataTrack,
    val plays: Int = 1,
    val lastPlayedAt: Long = 0L,
)

/** The host sends plugins no play history, so the plugin logs every track the audio API resolves.
 * A track the host preloads counts too, even if it is skipped. */
class PlayHistory(private val store: EntityStore) {

    suspend fun all(): List<PlayedTrack> =
        store.getDecoded(HISTORY_KEY, ListSerializer(PlayedTrack.serializer())).orEmpty()

    // Read-modify-write, so a scrobble landing while an audio resolve writes cannot drop either play.
    private val writeLock = Mutex()

    suspend fun record(track: MetadataTrack) {
        if (track.id.isBlank()) return
        writeLock.withLock {
            val now = epochMillis()
            val entries = all()
            val previous = entries.firstOrNull { it.track.id == track.id }
            if (previous != null && now - previous.lastPlayedAt < REPLAY_WINDOW_MS) return
            val updated = PlayedTrack(track, (previous?.plays ?: 0) + 1, now)
            val rest = entries.filterNot { it.track.id == track.id }
            store.put(HISTORY_KEY, json.encodeToJsonElement((listOf(updated) + rest).take(HISTORY_LIMIT)))
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
