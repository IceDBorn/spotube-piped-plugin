package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.play
import dev.icedborn.spotube_plugin_piped.store.refresh
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Account switches on one device: each account keeps its own library, plays and sync state. */
class ProfileSwitchTest {

    private fun ownLib(piped: FakePiped, user: String, device: String): List<String> =
        piped.files["$user/sp${device}lib"]?.let { decodeSyncDoc(it.first)!!.entries.map { e -> e.key } }.orEmpty()

    private suspend fun save(h: AccountHarness, index: Int) {
        h.library.saveTracks(listOf(vid(index)))
        h.mirror.save(SavedKind.TRACK, listOf(vid(index)))
    }

    private suspend fun remove(h: AccountHarness, index: Int) {
        h.mirror.remove(SavedKind.TRACK, listOf(vid(index)))
        h.mirror.removeLibraryEntries(SavedKind.TRACK, listOf(vid(index)))
    }

    @Test
    fun `a second account gets none of the first account's saves`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        h.device("aaaa0001")
        h.signIn("ice")
        refresh(h)
        save(h, 1)
        refresh(h)
        h.signIn("bob")
        refresh(h)
        assertTrue(ownLib(piped, "ice", "aaaa0001").contains("t:${vid(1)}"))
        assertFalse(ownLib(piped, "bob", "aaaa0001").contains("t:${vid(1)}"))
        assertEquals(emptyList(), h.library.savedTracks())
        h.signIn("ice")
        assertEquals(listOf(vid(1)), h.library.savedTracks())
    }

    @Test
    fun `an unsave made before a switch stays after coming back`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.device(id)
            h.signIn("ice")
            refresh(h)
        }
        save(b, 1)
        refresh(b)
        refresh(a)
        assertEquals(listOf(vid(1)), a.library.savedTracks())
        a.clock = 2_000L
        remove(a, 1)
        refresh(a)
        a.signIn("bob")
        refresh(a)
        a.signIn("ice")
        refresh(a)
        refresh(b)
        assertEquals(emptyList(), a.library.savedTracks())
        assertEquals(emptyList(), b.library.savedTracks())
    }

    @Test
    fun `saves and plays made signed out reach no account`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        h.device("aaaa0001")
        save(h, 9)
        play(h, 9)
        h.signIn("ice")
        refresh(h)
        assertFalse(ownLib(piped, "ice", "aaaa0001").contains("t:${vid(9)}"))
        assertEquals(emptyList(), h.library.savedTracks())
        assertTrue(h.history.all().none { it.track.id == vid(9) })
        h.session.clear()
        assertEquals(listOf(vid(9)), h.library.savedTracks())
    }

    // FakePiped keeps one set of account playlists for every user, so the next two tests skip the refresh as bob:
    // it would read ice's mirrors and log. Storage files are per user, so the tests above refresh freely.
    @Test
    fun `in mirror mode a second account does not see the first account's saves`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, profiles = true)
        h.signIn("ice")
        h.emptyMirrors()
        refresh(h)
        save(h, 1)
        h.signIn("bob")
        assertEquals(listOf(false), h.library.isSavedTracks(listOf(vid(1))))
        h.signIn("ice")
        assertEquals(listOf(true), h.library.isSavedTracks(listOf(vid(1))))
    }

    @Test
    fun `with the history log a second account starts with its own history`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, profiles = true)
        h.signIn("ice")
        refresh(h)
        play(h, 1)
        h.signIn("bob")
        assertTrue(h.history.all().isEmpty())
        h.signIn("ice")
        assertEquals(1, h.history.all().single().plays)
    }
}
