package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Station state: rank fusion, the returned songs, skips and the stored list. */
class RadioStationTest {

    private fun tracks(vararg ids: String) = ids.map { StationTrack(it, "x|$it") }

    @Test
    fun `ranked lists add up by reciprocal rank`() {
        val station = RadioStation(0).withRanked(listOf("a", "b", "c"), 1.0).withRanked(listOf("c", "d"), 1.0)
        // b and d tie at 1 / 22, and the song already in the pool stays first.
        assertEquals(listOf("c", "a", "b", "d"), station.pool.map { it.id })
    }

    @Test
    fun `a returned song never comes back into the pool`() {
        val returned = RadioStation(0).withRanked(listOf("a", "b"), 1.0).afterBatch(tracks("a"), 1)
        assertEquals(listOf("b", "c"), returned.withRanked(listOf("a", "c"), 1.0).pool.map { it.id })
    }

    @Test
    fun `the pool keeps its 200 best songs`() {
        val station = RadioStation(0).withRanked((0 until 250).map { "id$it" }, 1.0)
        assertEquals((0 until 200).map { "id$it" }, station.pool.map { it.id })
    }

    @Test
    fun `the oldest batches go past 500 returned songs`() {
        var station = RadioStation(0)
        for (at in 0L..25L) station = station.afterBatch((0 until 20).map { StationTrack("s$at-$it", "x|s$at-$it") }, at)
        assertEquals(25, station.batches.size)
        assertEquals(1L, station.batches.first().at)
    }

    @Test
    fun `a station knows the seeds it queued or returned`() {
        val station = RadioStation(0).withQueued(listOf("q1", "q2")).afterBatch(tracks("r1"), 1)
        assertEquals(2, station.overlap(listOf("q1", "r1", "z")))
        // A returned song the host sends back as a seed is not queued again.
        assertEquals(listOf("q1", "q2"), station.withQueued(listOf("r1")).queued)
    }

    @Test
    fun `a song is skipped when a later song of its batch was played and it was not`() {
        val station = RadioStation(0).afterBatch(tracks("a", "b", "c", "d"), 100)
        val plays = mapOf("a" to 150L, "b" to 50L, "c" to 200L)
        val skipped = station.withSkips { plays[it] ?: 0L }
        // d may still be playing, so only b counts.
        assertEquals(listOf("b"), skipped.skipped.map { it.id })
        assertEquals(listOf("b"), skipped.withSkips { plays[it] ?: 0L }.skipped.map { it.id })
    }

    @Test
    fun `only the last batch is checked for skips`() {
        val station = RadioStation(0).afterBatch(tracks("s1", "p1"), 100).afterBatch(tracks("s2", "p2"), 200)
        val plays = mapOf("p1" to 150L, "p2" to 250L)
        assertEquals(listOf("s2"), station.withSkips { plays[it] ?: 0L }.skipped.map { it.id })
    }

    @Test
    fun `the artist gap reaches into earlier batches`() {
        val station = RadioStation(0)
            .afterBatch(listOf(StationTrack("1", "o|1"), StationTrack("2", "p|2")), 1)
            .afterBatch(listOf(StationTrack("3", "q|3"), StationTrack("4", "r|4")), 2)
        assertEquals(listOf("p", "q", "r"), station.lastArtists())
    }

    @Test
    fun `a failed write keeps the stations in memory until restart`() = runTest {
        val storage = FakeStorage().apply { failPutsFor = "radio.stations" }
        val stations = RadioStations(EntityStore(storage))
        val station = RadioStation(5).withQueued(listOf("a"))
        assertFalse(stations.save(listOf(station)))
        assertEquals(listOf(station), stations.load())
        assertTrue(RadioStations(EntityStore(storage)).load().isEmpty())
    }

    @Test
    fun `a save keeps the first 3 stations`() = runTest {
        val storage = FakeStorage()
        assertTrue(RadioStations(EntityStore(storage)).save((1L..4L).map { RadioStation(it) }))
        assertEquals(listOf(1L, 2L, 3L), RadioStations(EntityStore(storage)).load().map { it.updatedAt })
    }
}
