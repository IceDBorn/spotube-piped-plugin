package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlayedTrack
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val START = 1_800_000_000_000L
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Answers the mix of [seed] with its own row by [artist], then [rows], each a video index and its artist. */
private fun FakePiped.mix(seed: Int, artist: String, vararg rows: Pair<Int, String>) =
    override("RDAMVM${vid(seed)}", body = mixBody(listOf(artistRow(seed, artist)) + rows.map { (index, by) -> artistRow(index, by) }))

/** The indexes of [range], each by the artist [prefix] plus the index's last digit. */
private fun rowsBy(range: IntRange, prefix: String) = range.map { it to "$prefix${it % 10}" }.toTypedArray()

/** Endless playback batches over a fake instance, with a clock and liked songs the tests set. */
class EndlessPlaybackTest {

    private class Radio(val piped: FakePiped = FakePiped(pageSize = 50), val storage: FakeStorage = FakeStorage()) {
        var clock = START
        var liked = emptyList<String>()
        val store = EntityStore(storage)
        val endless = EndlessPlayback(
            RadioMixes(PipedClient(piped) { FAKE_INSTANCE }, store),
            RadioStations(store),
            store,
            PlayHistory(store),
            now = { clock },
        ) { liked }

        suspend fun batch(vararg seeds: Int, limit: Int = 20) = endless.batch(seeds.map(::vid), limit).map { it.id }

        suspend fun played(vararg plays: PlayedTrack) {
            store.put("history.tracks", json.encodeToJsonElement(plays.toList()))
        }
    }

    @Test
    fun `later batches of a station repeat no song`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..40, "ar"))
        radio.piped.mix(1, "ar1", 2 to "ar2", 3 to "ar3", 21 to "ar1", *rowsBy(41..56, "b"))
        radio.piped.mix(5, "ar5", *rowsBy(61..80, "c"))
        val first = radio.batch(0)
        assertEquals((1..20).map(::vid), first)
        assertEquals(1, radio.piped.total)
        // The host seeds later batches with 5 random songs of the queue, mostly songs the station returned.
        val second = radio.batch(1, 5, 9, 13, 17)
        // vid21 sits in two mixes, so it ranks first.
        val expected = listOf(21, 61, 62, 63, 41, 64, 42, 65, 43, 66, 44, 67, 45, 68, 46, 69, 47, 70, 48, 71)
        assertEquals(expected.map(::vid), second)
        assertTrue(second.none { it in first })
        // A batch reads at most 2 seed mixes, so the mixes of vid9, vid13 and vid17 wait.
        assertEquals(1, radio.piped.count("RDAMVM${vid(1)}"))
        assertEquals(1, radio.piped.count("RDAMVM${vid(5)}"))
        assertEquals(3, radio.piped.total)
        // Both seeds were read, so the pool fills the next batch without a request.
        val third = radio.batch(1, 5, limit = 10)
        assertEquals(listOf(49, 72, 50, 73, 51, 74, 52, 75, 53, 76).map(::vid), third)
        assertEquals(3, radio.piped.total)
    }

    @Test
    fun `seeds that no station knows start a new one`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..40, "ar"))
        radio.batch(0)
        radio.piped.mix(100, "zz", 101 to "z1", 102 to "z2", 103 to "z3")
        assertEquals(listOf(101, 102, 103).map(::vid), radio.batch(100, limit = 3))
        val stations = RadioStations(EntityStore(radio.storage)).load()
        assertEquals(2, stations.size)
        assertEquals(listOf(vid(100)), stations.first().queued)
    }

    @Test
    fun `a single seed the station did not queue starts a new one`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..80, "ar"))
        radio.piped.mix(5, "ar5", 81 to "d1", 82 to "d2", 83 to "d3", 84 to "d4")
        assertEquals((1..20).map(::vid), radio.batch(0))
        // One Home recent card queues one song the station returned, so its mix is read and a new station starts.
        assertEquals(listOf(81, 82, 83, 84).map(::vid), radio.batch(5, limit = 4))
        assertEquals(1, radio.piped.count("RDAMVM${vid(5)}"))
        assertEquals(2, radio.piped.total)
        assertEquals(2, RadioStations(EntityStore(radio.storage)).load().size)
    }

    @Test
    fun `a station survives a restart`() = runTest {
        val before = Radio()
        before.piped.mix(0, "ar0", *rowsBy(1..40, "ar"))
        before.batch(0)
        val after = Radio(before.piped, before.storage)
        assertEquals((21..40).map(::vid), after.batch(0))
        // The mix was read once, before the restart.
        assertEquals(1, after.piped.total)
    }

    @Test
    fun `a song played in the last 6 hours is left out`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..4, "ar"))
        radio.played(
            PlayedTrack(rowTrack(1, "ar1"), lastPlayedAt = START - HOUR),
            PlayedTrack(rowTrack(2, "ar2"), lastPlayedAt = START - 7 * HOUR),
        )
        assertEquals(listOf(2, 3, 4).map(::vid), radio.batch(0, limit = 3))
    }

    @Test
    fun `every 4th song is a liked song by an artist of the station`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..40, "ar"))
        radio.liked = (0..9).map { vid(200 + it) }
        for (k in 0..9) radio.store.rememberTrack(rowTrack(200 + k, "ar$k"))
        // A liked song waits while its artist has 2 songs in the batch, so 203, 204 and 206 to 208 stay out.
        val expected = listOf(1, 2, 3, 200, 4, 5, 6, 201, 7, 8, 9, 202, 10, 13, 14, 205, 16, 17, 18, 209)
        assertEquals(expected.map(::vid), radio.batch(0))
        assertEquals(1, radio.piped.total)
    }

    @Test
    fun `a familiar song rests 2 days and keeps to the station's artists`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..6, "ar"))
        radio.played(
            PlayedTrack(rowTrack(200, "ar4"), plays = 9, lastPlayedAt = START - DAY),
            PlayedTrack(rowTrack(201, "ar5"), plays = 2, lastPlayedAt = START - 3 * DAY),
            PlayedTrack(rowTrack(202, "zz"), plays = 20, lastPlayedAt = START - 5 * DAY),
        )
        assertEquals(listOf(1, 2, 3, 201).map(::vid), radio.batch(0, limit = 4))
    }

    private fun takes(seed: Int, artist: String, title: String) = mixBody(
        listOf(
            artistRow(seed, artist, title),
            artistRow(1, "ar1", "Song One (Live at Wembley)"),
            artistRow(2, "ar2", "Song Two"),
            artistRow(3, "ar3", "Song Three - Remix"),
            artistRow(4, "ar4", "Song Four (Acoustic Session)"),
            artistRow(5, "ar5", "Song Five"),
            artistRow(6, "ar6", "Song Six"),
        ),
    )

    @Test
    fun `other versions of a seed and tagged takes are left out`() = runTest {
        val radio = Radio()
        // The seed is not cached, so its version comes from the mix's own row for it.
        radio.piped.override("RDAMVM${vid(0)}", body = takes(0, "ar2", "Song Two (2011 Remaster)"))
        assertEquals(listOf(5, 6).map(::vid), radio.batch(0, limit = 2))
        assertEquals(1, radio.piped.total)
    }

    @Test
    fun `a live seed lets live takes in`() = runTest {
        val radio = Radio()
        radio.store.rememberTrack(rowTrack(50, "ar0", "Song Fifty (Live)"))
        radio.piped.override("RDAMVM${vid(50)}", body = takes(50, "ar0", "Song Fifty (Live)"))
        assertEquals(listOf(1, 2, 5).map(::vid), radio.batch(50, limit = 3))
    }

    @Test
    fun `a skipped song seeds no mix and lowers its artist`() = runTest {
        val radio = Radio()
        radio.piped.mix(0, "ar0", *rowsBy(1..4, "ar"))
        radio.piped.mix(2, "ar2", 30 to "ar1", 31 to "ar6", 32 to "ar7")
        assertEquals((1..4).map(::vid), radio.batch(0, limit = 4))
        // vid2 and vid3 played after the batch and vid1 did not, so the listener skipped vid1.
        radio.played(
            PlayedTrack(rowTrack(3, "ar3"), lastPlayedAt = START + 8 * MINUTE),
            PlayedTrack(rowTrack(2, "ar2"), lastPlayedAt = START + 4 * MINUTE),
        )
        radio.clock = START + 10 * MINUTE
        // vid30 tops vid2's mix, but its artist ar1 was skipped.
        assertEquals(listOf(31, 32, 30).map(::vid), radio.batch(1, 2, limit = 3))
        assertEquals(0, radio.piped.count("RDAMVM${vid(1)}"))
        assertEquals(1, radio.piped.count("RDAMVM${vid(2)}"))
        assertEquals(2, radio.piped.total)
    }

    @Test
    fun `a seed whose mix failed is read again by the next batch`() = runTest {
        val radio = Radio()
        radio.piped.override("RDAMVM${vid(0)}", status = 500, times = 1)
        radio.piped.mix(0, "ar0", 1 to "ar1", 2 to "ar2")
        assertTrue(radio.batch(0, limit = 2).isEmpty())
        // The mix, then the seed's /streams and an empty catalog search.
        assertEquals(3, radio.piped.total)
        assertEquals(listOf(1, 2).map(::vid), radio.batch(0, limit = 2))
        assertEquals(2, radio.piped.count("RDAMVM${vid(0)}"))
    }

    @Test
    fun `a station whose write failed lives on in memory`() = runTest {
        val radio = Radio()
        radio.storage.failPutsFor = "radio.stations"
        radio.piped.mix(0, "ar0", *rowsBy(1..40, "ar"))
        assertEquals((1..20).map(::vid), radio.batch(0))
        assertEquals((21..40).map(::vid), radio.batch(0))
        assertNull(radio.storage.values["radio.stations"])
        assertEquals(1, radio.piped.total)
    }

    @Test
    fun `a video seed is read through its song and widened by another artist's mix`() = runTest {
        val radio = Radio()
        radio.store.rememberTrack(rowTrack(0, "Alpha", "Alpha - Song (Official Video)"))
        radio.piped.search("filter=music_songs", listOf(artistRow(9, "Alpha", "Song")))
        radio.piped.mix(9, "Alpha", 1 to "bee", 2 to "cee")
        radio.piped.mix(1, "bee", 9 to "Alpha", 3 to "dee")
        assertEquals(listOf(1, 2, 3).map(::vid), radio.batch(0, limit = 3))
        // The song search, the song's mix and bee's mix. The video's own mix is never read.
        assertEquals(3, radio.piped.total)
        assertEquals(0, radio.piped.count("RDAMVM${vid(0)}"))
    }
}
