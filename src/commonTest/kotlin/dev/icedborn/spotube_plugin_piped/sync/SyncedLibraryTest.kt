package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.AccountCacheState
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.SavedBindings
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saves and unsaves through storage files between two devices of one account. */
class SyncedLibraryTest {

    private suspend fun AccountHarness.ready(id: String): AccountHarness {
        signIn()
        device(id)
        assertTrue(sync!!.prepare(session.load()!!))
        return this
    }

    @Test
    fun `a like on one device reaches the other without touching the mirrors`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true).ready("aaaa0001")
        val b = AccountHarness(backgroundScope, piped, storageSync = true).ready("bbbb0002")
        a.library.saveTracks(listOf(vid(1)))
        a.mirror.save(SavedKind.TRACK, listOf(vid(1)))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals(listOf(vid(1)), b.library.savedTracks())
        assertEquals(listOf(true), b.mirror.isSavedTracks(listOf(vid(1))))
        assertEquals(0, piped.count("/user/playlists/add"))
        assertEquals(0, piped.count("/user/playlists/create"))
    }

    @Test
    fun `a later unsave wins on every device`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true).ready("aaaa0001")
        val b = AccountHarness(backgroundScope, piped, storageSync = true).ready("bbbb0002")
        a.library.saveTracks(listOf(vid(1)))
        a.mirror.save(SavedKind.TRACK, listOf(vid(1)))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        b.clock += 60_000
        b.mirror.remove(SavedKind.TRACK, listOf(vid(1)))
        b.mirror.removeLibraryEntries(SavedKind.TRACK, listOf(vid(1)))
        b.sync!!.sync(b.session.load()!!, null)
        a.sync!!.sync(a.session.load()!!, null)
        assertFalse(vid(1) in a.library.savedTracks())
        assertEquals(listOf(false), a.mirror.isSavedTracks(listOf(vid(1))))
    }

    @Test
    fun `the first sync imports the snapshot and the device library at time zero`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true).ready("aaaa0001")
        a.library.saveAlbums(listOf("album-1"))
        val previous = AccountCacheState(savedTracks = listOf(vid(2)), instance = FAKE_INSTANCE, username = "ice")
        a.sync!!.sync(a.session.load()!!, previous)
        val doc = decodeSyncDoc(piped.files.getValue("ice/spaaaa0001lib").first)!!
        assertEquals(setOf("a:album-1", "t:${vid(2)}"), doc.entries.map { it.key }.toSet())
        assertTrue(doc.entries.all { it.at == 0L })
        assertEquals(listOf(true), a.mirror.isSavedTracks(listOf(vid(2))))
    }

    @Test
    fun `an unsave removes an item a mirror row still binds`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true).ready("aaaa0001")
        h.library.saveTracks(listOf(vid(1)))
        SavedBindings(h.store).bindRep(SavedKind.TRACK, vid(1), vid(1))
        h.mirror.remove(SavedKind.TRACK, listOf(vid(1)))
        h.mirror.removeLibraryEntries(SavedKind.TRACK, listOf(vid(1)))
        assertEquals(listOf(false), h.mirror.isSavedTracks(listOf(vid(1))))
    }

    @Test
    fun `changes made with storage off go out once it is back on`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true).ready("aaaa0001")
        val b = AccountHarness(backgroundScope, piped, storageSync = true).ready("bbbb0002")
        a.library.saveTracks(listOf(vid(1), vid(2)))
        a.mirror.save(SavedKind.TRACK, listOf(vid(1), vid(2)))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        a.sync.setting.set(false)
        a.emptyMirrors()
        a.clock += 60_000
        a.library.saveTracks(listOf(vid(3)))
        a.mirror.save(SavedKind.TRACK, listOf(vid(3)))
        a.library.removeTracks(listOf(vid(1)))
        a.mirror.remove(SavedKind.TRACK, listOf(vid(1)))

        a.sync.setting.set(true)
        a.sync.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)

        assertEquals(setOf(vid(2), vid(3)), b.library.savedTracks().toSet())
        assertEquals(1, piped.count("/user/playlists/add"))
    }

    @Test
    fun `with storage off a save takes the mirror path`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true).ready("aaaa0001")
        h.sync!!.setting.set(false)
        h.emptyMirrors()
        h.library.saveTracks(listOf(vid(1)))
        h.mirror.save(SavedKind.TRACK, listOf(vid(1)))
        assertEquals(1, h.piped.count("/user/playlists/add"))
    }
}
