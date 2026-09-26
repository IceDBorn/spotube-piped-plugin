package dev.icedborn.spotube_plugin_piped

import dev.icedborn.spotube_plugin_piped_metadata.EntityStore
import dev.icedborn.spotube_plugin_piped_metadata.PlayHistory
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import dev.krtirtho.plugin_interfaces.plugin_apis.scrobble.ScrobbleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val historyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** The scrobble role replaces the audio-resolve log as the play source, and only once the host calls it. */
class ScrobbleAPITest {

    private val storage = FakeStorage()
    private val store = EntityStore(storage)
    private val history = PlayHistory(store)
    private val fetched = mutableListOf<String>()
    private var fetch: (String) -> MetadataTrack? = { null }
    private var clock = 1_700_000_000_000L
    private val scrobbles = ScrobbleHistory(history, store, { id ->
        fetched += id
        fetch(id)
    }) { clock }
    private val api = RealScrobbleAPI(scrobbles)

    private fun videoId(index: Int) = "vid" + index.toString().padStart(7, '0') + "0"

    private fun track(index: Int, title: String = "Track $index") = MetadataTrack(
        id = videoId(index),
        title = title,
        durationMs = 200_000,
        trackNumber = null,
        discNumber = null,
        artists = listOf(MetadataArtist.Basic(id = "UCa", name = "Alpha", thumbnails = emptyList(), externalUri = null)),
        album = null,
        thumbnails = listOf(Thumbnail("https://piped.example/c$index.jpg", 300, 300)),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    private fun scrobbleOf(id: String, provider: String = "piped") = ScrobbleTrack(
        timestamp = 1_700_000_000,
        trackId = id,
        artistId = "UCa",
        albumId = null,
        trackName = "Track",
        artistName = "Alpha",
        albumName = null,
        streamingProvider = provider,
        durationMs = 200_000,
    )

    private suspend fun playedIds(): List<String> = history.recentTracks(10).map { it.id }

    private suspend fun playsOf(id: String): Int = history.all().firstOrNull { it.track.id == id }?.plays ?: 0

    /** Moves an entry's play time back, so a later record is not merged into it by REPLAY_WINDOW_MS. */
    private suspend fun backdate(id: String, byMs: Long = 300_000) {
        val entries = history.all().map {
            if (it.track.id == id) it.copy(lastPlayedAt = it.lastPlayedAt - byMs) else it
        }
        store.put("history.tracks", historyJson.encodeToJsonElement(entries))
    }

    @Test
    fun `audio resolves count as plays until the host scrobbles`() = runTest {
        scrobbles.onAudioRequested(track(1))
        assertEquals(listOf(videoId(1)), playedIds())
    }

    @Test
    fun `a scrobble stops the audio resolves from counting`() = runTest {
        fetch = { id -> track(9).copy(id = id) }
        api.scrobble(scrobbleOf(videoId(9)))
        scrobbles.onAudioRequested(track(2))
        assertEquals(listOf(videoId(9)), playedIds())
    }

    @Test
    fun `a scrobble does not count a play the audio role already recorded`() = runTest {
        scrobbles.onAudioRequested(track(1))
        backdate(videoId(1)) // far past REPLAY_WINDOW_MS, which is where a real scrobble lands
        api.scrobble(scrobbleOf(videoId(1)))
        assertEquals(listOf(videoId(1)), playedIds())
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `a scrobble for a track the audio role never saw counts once`() = runTest {
        fetch = { id -> track(9).copy(id = id) }
        api.scrobble(scrobbleOf(videoId(9)))
        assertEquals(1, playsOf(videoId(9)))
    }

    @Test
    fun `a replay counted by the audio role is not swallowed by the first scrobble`() = runTest {
        scrobbles.onAudioRequested(track(1))
        backdate(videoId(1))
        // An unresolvable scrobble leaves the audio path in charge, so a real replay still counts.
        api.scrobble(scrobbleOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        scrobbles.onAudioRequested(track(1))
        assertEquals(2, playsOf(videoId(1)))
    }

    @Test
    fun `a scrobble for an unseen Piped track fetches it`() = runTest {
        fetch = { id -> track(9).copy(id = id) }
        api.scrobble(scrobbleOf(videoId(9)))
        assertEquals(listOf(videoId(9)), playedIds())
        assertEquals(listOf(videoId(9)), fetched)
    }

    @Test
    fun `a second scrobble for the same track reuses the fetched copy`() = runTest {
        fetch = { id -> track(9).copy(id = id) }
        api.scrobble(scrobbleOf(videoId(9)))
        scrobbles.onAudioRequested(track(8))
        api.scrobble(scrobbleOf(videoId(9)))
        assertEquals(listOf(videoId(9)), playedIds())
        assertEquals(listOf(videoId(9)), fetched)
    }

    @Test
    fun `a foreign id is dropped without a fetch`() = runTest {
        fetch = { id -> track(9).copy(id = id) }
        api.scrobble(scrobbleOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(emptyList<String>(), playedIds())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a blank id is dropped`() = runTest {
        api.scrobble(scrobbleOf(""))
        assertEquals(emptyList<String>(), playedIds())
    }

    @Test
    fun `a fetch failure drops the scrobble instead of throwing`() = runTest {
        fetch = { throw IllegalStateException("streams fetch failed") }
        api.scrobble(scrobbleOf(videoId(9)))
        assertEquals(emptyList<String>(), playedIds())
    }

    @Test
    fun `a track resolved before the switch and replayed later still counts the replay`() = runTest {
        // Resolved, then skipped: it is in the audio set, but it was never really played.
        scrobbles.onAudioRequested(track(1))
        // A different track's scrobble flips the switch.
        api.scrobble(scrobbleOf(videoId(2)))
        // Much later the user plays it properly and the host scrobbles it again. Both windows
        // have to pass: ScrobbleHistory's own, and PlayHistory's REPLAY_WINDOW_MS.
        clock += 30 * 60_000
        backdate(videoId(1), 30 * 60_000)
        api.scrobble(scrobbleOf(videoId(1)))
        assertEquals(2, playsOf(videoId(1)))
    }

    @Test
    fun `a dropped foreign scrobble leaves the audio role as the source`() = runTest {
        api.scrobble(scrobbleOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        scrobbles.onAudioRequested(track(3))
        assertEquals(listOf(videoId(3)), playedIds())
    }
}
