package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.Play
import dev.icedborn.spotube_plugin_piped.sync.PlayCount
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalSectionsTest {

    private val now = 100 * DAY_MS
    private val hour = 60 * 60_000L
    private val minute = 60_000L

    private fun plays(index: Int, vararg ats: Long) = ats.map { Play(vid(index), it) }

    @Test
    fun `On repeat ranks this month's plays and needs two of them`() {
        val log = plays(1, now - hour, now - 2 * hour) + plays(2, now - hour, now - 3 * hour, now - 4 * hour) +
            plays(3, now - hour) + plays(4, now - 31 * DAY_MS, now - 32 * DAY_MS)
        assertEquals(listOf(vid(2), vid(1)), onRepeat(log, now))
    }

    @Test
    fun `On repeat breaks a tie by the newest play`() {
        val log = plays(1, now - 5 * hour, now - 6 * hour) + plays(2, now - hour, now - 7 * hour)
        assertEquals(listOf(vid(2), vid(1)), onRepeat(log, now))
    }

    @Test
    fun `Forgotten favorites need five plays and none in sixty days`() {
        val totals = mapOf(
            vid(1) to PlayCount(5, now - 61 * DAY_MS),
            vid(2) to PlayCount(9, now - 70 * DAY_MS),
            vid(3) to PlayCount(4, now - 90 * DAY_MS),
            vid(4) to PlayCount(20, now - 59 * DAY_MS),
        )
        assertEquals(listOf(vid(2), vid(1)), forgottenFavorites(totals, now))
    }

    @Test
    fun `the windows split the day in four hour blocks`() {
        val midnight = 100 * DAY_MS
        assertEquals(0, timeWindow(midnight, 0))
        assertEquals(5, timeWindow(midnight - minute, 0))
        assertEquals(4, timeWindow(midnight + 16 * hour, 0))
        assertEquals("evening", TIME_WINDOWS[4])
    }

    @Test
    fun `the window follows the local offset`() {
        val midnight = 100 * DAY_MS
        assertEquals(1, timeWindow(midnight + 2 * hour, 180))
        assertEquals(5, timeWindow(midnight + 2 * hour, -180))
    }

    @Test
    fun `the time of day mix keeps plays of the current window`() {
        val evening = 100 * DAY_MS + 18 * hour
        val log = plays(1, evening - DAY_MS) + plays(2, evening - 2 * DAY_MS, evening - 3 * DAY_MS) +
            plays(3, evening - DAY_MS - 8 * hour)
        assertEquals(listOf(vid(2), vid(1)), timeOfDay(log, evening, 0))
    }

    @Test
    fun `a gap over thirty minutes starts a session`() {
        val log = plays(1, 0L, 30 * minute) + plays(2, 61 * minute)
        assertEquals(listOf(2, 1), sessions(log).map { it.size })
    }

    @Test
    fun `artists sharing two sessions join the most played artist`() {
        val artist = mapOf(vid(1) to "A", vid(2) to "B", vid(3) to "C", vid(4) to "D")
        // A and B share two sessions, C shares only one with A, D plays alone.
        val log = plays(1, 0L, 1 * minute, 10 * hour, 20 * hour, 20 * hour + minute) +
            plays(2, 2 * minute, 10 * hour + minute) + plays(3, 20 * hour + 2 * minute) + plays(4, 40 * hour)
        assertEquals(listOf(listOf("A", "B"), listOf("C"), listOf("D")), artistGroups(log) { artist[it] })
    }

    @Test
    fun `a group takes at most five artists and three groups are made`() {
        val names = ('A'..'L').map { it.toString() }
        val artist = names.withIndex().associate { (i, name) -> vid(i) to name }
        // Two sessions with every artist, A played most.
        val log = names.indices.flatMap { i -> plays(i, i * minute, 10 * hour + i * minute) } + plays(0, 2 * hour)
        val groups = artistGroups(log) { artist[it] }
        assertEquals(5, groups.first().size)
        assertEquals("A", groups.first().first())
        assertEquals(DAILY_MIXES, groups.size)
    }

    @Test
    fun `known and new songs take turns`() {
        assertEquals(listOf("k1", "n1", "k2", "n2", "n3"), alternate(listOf("k1", "k2"), listOf("n1", "n2", "n3")))
        assertEquals(3, alternate(listOf("a", "b"), listOf("c", "d"), limit = 3).size)
        assertEquals(listOf("a", "b"), alternate(listOf("a"), listOf("a", "b")))
    }
}
