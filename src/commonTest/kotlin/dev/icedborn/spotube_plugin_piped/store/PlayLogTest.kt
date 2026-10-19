package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayLogTest {

    @Test
    fun `a counted play is logged with its time`() = runTest {
        val store = EntityStore(FakeStorage())
        PlayHistory(store).record(logTrack(1))
        val plays = PlayLog(store).all()
        assertEquals(listOf(vid(1)), plays.map { it.id })
        assertTrue(plays.single().at > 0)
    }

    @Test
    fun `a replay within the window is not logged`() = runTest {
        val store = EntityStore(FakeStorage())
        val history = PlayHistory(store)
        history.record(logTrack(1))
        history.record(logTrack(1))
        assertEquals(1, PlayLog(store).all().size)
    }

    @Test
    fun `the log keeps the newest plays up to its limit`() = runTest {
        val log = PlayLog(EntityStore(FakeStorage()), limit = 3)
        repeat(5) { log.append(vid(it), it.toLong()) }
        assertEquals(listOf(4L, 3L, 2L), log.all().map { it.at })
    }

    @Test
    fun `a bad row is dropped and the other plays stay`() = runTest {
        val storage = FakeStorage()
        storage.values[PLAYS_KEY] = "[[\"${vid(1)}\",1],[\"${vid(2)}\"],[\"${vid(3)}\",3]]"
        val log = PlayLog(EntityStore(storage))

        log.append(vid(4), 4)

        assertEquals(listOf(vid(4), vid(1), vid(3)), log.all().map { it.id })
    }

    @Test
    fun `a corrupt log reads as empty and the next play replaces it`() = runTest {
        val storage = FakeStorage()
        storage.values[PLAYS_KEY] = "{\"not\":\"a list\"}"
        val log = PlayLog(EntityStore(storage))
        assertEquals(emptyList(), log.all())
        assertTrue(log.append(vid(1), 7L))
        assertEquals(listOf(Play(vid(1), 7L)), log.all())
    }

    @Test
    fun `a failed log write still counts the play`() = runTest {
        val storage = FakeStorage()
        storage.failPutsFor = PLAYS_KEY
        val history = PlayHistory(EntityStore(storage))
        assertTrue(history.record(logTrack(1)))
        assertEquals(1, history.all().single().plays)
    }

    @Test
    fun `each profile has its own log`() = runTest {
        val h = AccountHarness(backgroundScope, profiles = true)
        h.signIn("ice")
        h.history.record(logTrack(1))
        h.signIn("bob")
        assertEquals(emptyList(), PlayLog(h.store).all())
        h.signIn("ice")
        assertEquals(listOf(vid(1)), PlayLog(h.store).all().map { it.id })
    }
}
