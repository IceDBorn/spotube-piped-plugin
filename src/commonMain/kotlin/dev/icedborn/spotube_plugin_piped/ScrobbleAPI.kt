package dev.icedborn.spotube_plugin_piped

import dev.icedborn.spotube_plugin_piped_metadata.EntityStore
import dev.icedborn.spotube_plugin_piped_metadata.PlayHistory
import dev.icedborn.spotube_plugin_piped_metadata.YOUTUBE_VIDEO_ID
import dev.icedborn.spotube_plugin_piped_metadata.epochMillis
import dev.icedborn.spotube_plugin_piped_metadata.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import dev.krtirtho.plugin_interfaces.plugin_apis.scrobble.ScrobbleAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.scrobble.ScrobbleTrack
import kotlin.coroutines.cancellation.CancellationException

private val scrobbleLog = Logger("PipedScrobble")

/** Tracks the audio role already resolved, so a scrobble can be joined back to a full [MetadataTrack]. */
private const val SEEN_LIMIT = 200

/** Long enough to cover a slow scrobble call and a paused player, short enough that a real replay
 * the next day is not mistaken for the same listen. */
private const val SCROBBLE_MATCH_WINDOW_MS = 10 * 60_000L

/**
 * Feeds [PlayHistory] from whichever signal the host provides.
 *
 * A host that implements the scrobble role reports a play only after the track really played, so that
 * signal wins as soon as it arrives. Until then, and on hosts without the role, the audio-resolve path
 * stays the source: the host preloads and re-resolves on seeks, so it over-counts, but it is all there is.
 *
 * The switch is per plugin instance, not persisted. A persisted switch would silence history forever on a
 * host that never scrobbles, because the first launch would have set it.
 */
class ScrobbleHistory(
    private val history: PlayHistory,
    private val store: EntityStore,
    /** Rebuilds a track from a Piped id on a cache miss. Null when the id is not one of ours. */
    private val fetchTrack: suspend (String) -> MetadataTrack?,
    /** Wall clock in epoch millis. A parameter so a test can move time without sleeping. */
    private val now: () -> Long = { epochMillis() },
) {

    private val seen = LinkedHashMap<String, MetadataTrack>()
    private var hostScrobbles = false

    // Ids the audio path already counted, with the wall-clock time it counted them, so the scrobble
    // that follows does not count the same listen twice. A scrobble lands at least
    // min(duration / 2, 240s) after the resolve, well past REPLAY_WINDOW_MS, so PlayHistory would
    // otherwise see a second play for one listen. The time bounds the claim: a much later scrobble
    // for the same id is a real replay the audio path never counted, so it must still be recorded.
    private val recordedByAudio = LinkedHashMap<String, Long>()

    /** The audio role resolved a track: cache it, and count it as a play while no scrobble has arrived. */
    suspend fun onAudioRequested(track: MetadataTrack) {
        if (track.id.isNotBlank()) {
            seen.remove(track.id)
            seen[track.id] = track
            while (seen.size > SEEN_LIMIT) seen.remove(seen.keys.first())
        }
        if (hostScrobbles) return
        history.record(track)
        if (track.id.isNotBlank()) {
            recordedByAudio.remove(track.id)
            recordedByAudio[track.id] = now()
            while (recordedByAudio.size > SEEN_LIMIT) recordedByAudio.remove(recordedByAudio.keys.first())
        }
    }

    suspend fun onScrobble(track: ScrobbleTrack) {
        val id = track.trackId
        if (id.isBlank()) return
        val full = resolve(id)
        if (full == null) {
            // Not a Piped id, so another metadata plugin owns this track. Leave the switch alone: the
            // audio role is the only source left for it, and Piped can still be the audio plugin.
            scrobbleLog.w { "scrobble for a track Piped cannot resolve, dropped: ${id.length}-char id from ${track.streamingProvider}" }
            return
        }
        hostScrobbles = true
        val recordedAt = recordedByAudio.remove(id)
        val window = (full.durationMs.takeIf { it > 0 } ?: SCROBBLE_MATCH_WINDOW_MS) + SCROBBLE_MATCH_WINDOW_MS
        // The audio path's play stands in for this one only when it is the same listen. A track
        // resolved before the switch and played again much later is a real play of its own.
        if (recordedAt != null && now() - recordedAt <= window) return
        history.record(full)
    }

    /** The audio role's copy, then the entity store, then a fetch. A foreign id never reaches the fetch. */
    private suspend fun resolve(id: String): MetadataTrack? {
        seen[id]?.let { return it }
        store.cachedTrack(id)?.let { return it }
        if (!YOUTUBE_VIDEO_ID.matches(id)) return null
        val fetched = orNull("scrobble track $id") { fetchTrack(id) } ?: return null
        seen.remove(id)
        seen[id] = fetched
        return fetched
    }
}

/** The host calls this after a track has really played. Failures are logged, never thrown at the player. */
class RealScrobbleAPI(private val scrobbles: ScrobbleHistory) : ScrobbleAPI {

    override suspend fun scrobble(track: ScrobbleTrack) {
        try {
            scrobbles.onScrobble(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scrobbleLog.w { "scrobble failed for ${track.trackId.length}-char id: ${e.message}" }
        }
    }
}
