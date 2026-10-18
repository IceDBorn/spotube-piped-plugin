package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.LocalPlayTimes
import dev.icedborn.spotube_plugin_piped.store.PlayLog
import dev.icedborn.spotube_plugin_piped.store.logTrack
import dev.icedborn.spotube_plugin_piped.store.play
import dev.icedborn.spotube_plugin_piped.store.refresh
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoragePlayTimesTest {

    private fun source(h: AccountHarness) = PlaySource(LocalPlayTimes(PlayLog(h.store)), h.sync)

    @Test
    fun `a play on one device reaches the other after a refresh`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.device(id)
            h.signIn("ice")
            refresh(h)
        }
        play(a, 1)
        refresh(a)
        refresh(b)
        val snapshot = source(b).current().snapshot()
        assertEquals(listOf(vid(1)), snapshot.plays.map { it.id })
        assertEquals(1, snapshot.totals[vid(1)]?.plays)
    }

    @Test
    fun `without active storage the device's own log is read`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true)
        h.history.record(logTrack(2))
        val snapshot = source(h).current().snapshot()
        assertEquals(listOf(vid(2)), snapshot.plays.map { it.id })
        assertEquals(1, snapshot.totals[vid(2)]?.plays)
    }

    @Test
    fun `folded counts reach the totals without events`() = runTest {
        val folded = LwwEntry(foldedKey(vid(3)), 5L, countValue(PlayCount(6, 1_000L)))
        val snapshot = StoragePlayTimes { listOf(SyncDoc(device = "x", entries = listOf(folded))) }.snapshot()
        assertEquals(emptyList(), snapshot.plays)
        assertEquals(PlayCount(6, 1_000L), snapshot.totals[vid(3)])
    }

    @Test
    fun `events of every device are plays, newest first`() = runTest {
        val docs = listOf(
            SyncDoc(device = "x", entries = listOf(LwwEntry(eventKey(vid(1), 10L), 10L, null))),
            SyncDoc(device = "y", entries = listOf(LwwEntry(eventKey(vid(2), 20L), 20L, null))),
        )
        val plays = StoragePlayTimes { docs }.snapshot().plays
        assertEquals(listOf(vid(2), vid(1)), plays.map { it.id })
    }
}
