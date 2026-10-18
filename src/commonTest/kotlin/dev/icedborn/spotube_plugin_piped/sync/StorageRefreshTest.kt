package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.logPlaylist
import dev.icedborn.spotube_plugin_piped.store.logTrack
import dev.icedborn.spotube_plugin_piped.store.play
import dev.icedborn.spotube_plugin_piped.store.refresh
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `plays reach the other device through storage and no log is written`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            refresh(h)
        }
        play(a, 1)
        refresh(a)
        refresh(b)
        val merged = b.history.all().firstOrNull { it.track.id == vid(1) }
        assertEquals(1, merged?.plays)
        assertNull(a.logPlaylist())
    }

    @Test
    fun `the history log is merged once more before the import and then left alone`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            h.sync!!.setting.set(false)
            refresh(h)
        }
        play(a, 1)
        b.sync!!.setting.set(true)
        refresh(b)
        assertEquals(1, b.history.all().firstOrNull { it.track.id == vid(1) }?.plays)
        val log = assertNotNull(b.logPlaylist())
        piped.reset()

        refresh(b)

        assertEquals(0, piped.count("/playlists/${log.id}"))
    }

    @Test
    fun `switching to another account and back counts the plays once`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            refresh(h)
        }
        play(a, 1)
        refresh(a)
        a.signIn("bob")
        refresh(a)
        a.signIn("ice")
        refresh(a)

        refresh(b)

        assertEquals(1, b.history.all().single().plays)
        play(a, 2)
        refresh(a)
        refresh(b)
        val plays = b.history.all().associate { it.track.id to it.plays }
        assertEquals(mapOf(vid(1) to 1, vid(2) to 1), plays)
    }

    @Test
    fun `a play after the import pushes its segment and the manifest`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, storageSync = true)
        h.signIn()
        h.device("aaaa0001")
        refresh(h)

        val first = pushedBy(piped) { h.mirror.played(logTrack(1)) }
        // A track not shared before also sends its track details file.
        assertEquals(setOf("ice/spaaaa0001h0", "ice/spaaaa0001t${shardOf(vid(1))}", "ice/spaaaa0001m"), first)
        assertEquals(6, piped.count("/storage/"))
        h.clock += 1_000

        val second = pushedBy(piped) { h.mirror.played(logTrack(1)) }

        assertEquals(setOf("ice/spaaaa0001h0", "ice/spaaaa0001m"), second)
        assertEquals(4, piped.count("/storage/"))
    }

    /** The storage files a scheduled push after [action] wrote. */
    private suspend fun TestScope.pushedBy(piped: FakePiped, action: suspend () -> Unit): Set<String> {
        val before = piped.files.mapValues { it.value.second }
        piped.reset()
        action()
        testScheduler.advanceTimeBy(FLUSH_DELAY_MS + 1)
        return piped.files.filter { before[it.key] != it.value.second }.keys
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
