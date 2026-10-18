package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Merged counts raise the local history and never lower it. */
class CombinePlaysTest {

    @Test
    fun `merged counts only raise local ones and the order follows the newest play`() {
        val local = listOf(PlayedTrack(logTrack(1), 4, 50), PlayedTrack(logTrack(2), 1, 40))
        val merged = listOf(PlayedTrack(logTrack(1), 2, 30), PlayedTrack(logTrack(2), 3, 60), PlayedTrack(logTrack(3), 1, 45))
        val combined = combinePlays(local, merged)
        assertEquals(listOf(vid(2), vid(1), vid(3)), combined.map { it.track.id })
        assertEquals(listOf(3, 4, 1), combined.map { it.plays })
    }

    @Test
    fun `mergeCounts stores the combined history`() = runTest {
        val history = PlayHistory(EntityStore(FakeStorage()))
        history.record(logTrack(1))
        history.mergeCounts(listOf(PlayedTrack(logTrack(1), 5, Long.MAX_VALUE)))
        assertEquals(5, history.all().single().plays)
    }
}
