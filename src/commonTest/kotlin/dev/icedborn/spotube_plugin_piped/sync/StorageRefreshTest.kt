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
import kotlin.test.assertTrue

/** An account refresh with storage: no mirror walks, the import, and the account playlists as before. */
class StorageRefreshTest {

    @Test
    fun `a refresh skips the mirrors and imports their snapshot`() = runTest {
        val piped = FakePiped()
        val h = AccountHarness(backgroundScope, piped, storageSync = true)
        h.signIn()
        h.device("aaaa0001")
        h.sync!!.setting.set(false)
        h.emptyMirrors()
        h.library.saveTracks(listOf(vid(1)))
        h.mirror.save(SavedKind.TRACK, listOf(vid(1)))
        h.mirror.refreshCache()
        val own = h.piped.accountPlaylist("Mine", listOf(vid(3)))
        h.sync.setting.set(true)
        val favorites = h.mirrorOf(SavedKind.TRACK)
        piped.reset()

        h.mirror.refreshCache()

        assertEquals(0, piped.count("/playlists/${favorites.id}"))
        assertTrue(h.mirror.cachedState()!!.playlists.any { it.id == own.id })
        assertTrue(h.mirror.cachedState()!!.savedTracks.isEmpty())
        val doc = decodeSyncDoc(piped.files.getValue("ice/spaaaa0001lib").first)!!
        assertTrue(doc.entries.any { it.key == "t:${vid(1)}" })
        assertEquals(listOf(true), h.mirror.isSavedTracks(listOf(vid(1))))
    }

    @Test
    fun `the history log still carries plays between devices`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            refresh(h)
        }
        assertTrue(b.sync!!.mode.active(b.session.load()!!))
        play(a, 1)
        refresh(b)
        assertEquals(1, b.history.all().firstOrNull { it.track.id == vid(1) }?.plays)
    }

    @Test
    fun `an instance without storage keeps the mirror refresh`() = runTest {
        val piped = FakePiped().apply { storageEnabled = false }
        val h = AccountHarness(backgroundScope, piped, storageSync = true)
        h.signIn()
        h.emptyMirrors()
        val favorites = h.mirrorOf(SavedKind.TRACK)
        h.mirror.refreshCache()
        assertEquals(1, piped.count("/playlists/${favorites.id}"))
        assertEquals(0, piped.count("/storage/"))
    }
}
