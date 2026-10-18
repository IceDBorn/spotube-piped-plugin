package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataPlaylistAPI
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.StoredPlaylist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local playlists travel as whole records; the newest edit of a playlist wins. */
class SyncedPlaylistsTest {

    private suspend fun pair(piped: FakePiped, a: AccountHarness, b: AccountHarness) {
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            assertTrue(h.sync!!.prepare(h.session.load()!!))
        }
    }

    @Test
    fun `a created, renamed and deleted playlist follows on the other device`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-road-1", name = "Road", trackIds = listOf(vid(1))))
        a.library.savePlaylists(listOf("piped-road-1"))
        a.mirror.mirrorCreatePlaylist("piped-road-1", "Road", listOf(vid(1)))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals("Road", b.library.storedPlaylist("piped-road-1")?.name)
        assertTrue("piped-road-1" in b.library.savedPlaylists())
        assertEquals(0, piped.count("/user/playlists/create"))

        a.clock += 1_000
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-road-1", name = "Road trip", trackIds = listOf(vid(1), vid(2))))
        a.mirror.mirrorRenamePlaylist("piped-road-1", "Road trip")
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals(listOf(vid(1), vid(2)), b.library.storedPlaylist("piped-road-1")?.trackIds)

        a.clock += 1_000
        a.mirror.mirrorDeletePlaylist("piped-road-1")
        a.library.deleteStoredPlaylist("piped-road-1")
        a.library.removePlaylists(listOf("piped-road-1"))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertNull(b.library.storedPlaylist("piped-road-1"))
        assertFalse("piped-road-1" in b.library.savedPlaylists())
    }

    @Test
    fun `a playlist imported at time zero stays deleted after a delete`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-old", name = "Old"))
        a.library.savePlaylists(listOf("piped-old"))
        pair(piped, a, b)
        listOf(a, b).forEach { it.sync!!.sync(it.session.load()!!, null) }
        assertTrue("piped-old" in b.library.savedPlaylists())
        a.clock += 1_000
        a.mirror.mirrorDeletePlaylist("piped-old")
        a.library.deleteStoredPlaylist("piped-old")
        a.library.removePlaylists(listOf("piped-old"))
        listOf(a, b, b).forEach { it.sync!!.sync(it.session.load()!!, null) }
        assertFalse("piped-old" in b.library.savedPlaylists())
        assertTrue(decodeSyncDoc(piped.files.getValue("ice/spaaaa0001lib").first)!!.entries.none { it.key == "p:piped-old" })
    }

    @Test
    fun `an edit not yet pushed is not overwritten by a pull`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "From A"))
        a.mirror.mirrorCreatePlaylist("piped-p", "From A", emptyList())
        a.sync!!.sync(a.session.load()!!, null)
        b.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "From B"))
        b.mirror.mirrorRenamePlaylist("piped-p", "From B")
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals("From B", b.library.storedPlaylist("piped-p")?.name)
    }

    @Test
    fun `a newer edit from another device replaces an edit not yet pushed`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        b.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "From B"))
        b.mirror.mirrorCreatePlaylist("piped-p", "From B", emptyList())
        a.clock += 1_000
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "From A"))
        a.mirror.mirrorCreatePlaylist("piped-p", "From A", emptyList())
        a.sync!!.sync(a.session.load()!!, null)

        b.sync!!.sync(b.session.load()!!, null)

        assertEquals("From A", b.library.storedPlaylist("piped-p")?.name)
    }

    @Test
    fun `an edit whose record fails is pushed by a later flush`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        a.sync!!.sync(a.session.load()!!, null)
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "Road"))
        a.mirror.mirrorCreatePlaylist("piped-p", "Road", emptyList())
        a.storage.failPutsFor = "storage.own:pl"
        a.sync!!.sync(a.session.load()!!, null)
        a.storage.failPutsFor = null

        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)

        assertEquals("Road", b.library.storedPlaylist("piped-p")?.name)
    }

    @Test
    fun `an edit of only the description reaches the other device`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        val api = RealMetadataPlaylistAPI(a.client, a.store, a.library, a.mirror, a.history, LibraryPlaylistSetting(a.store))
        val created = api.createPlaylist("Road", "Old", true, false, "", listOf(vid(1)))
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        a.clock += 1_000

        api.updatePlaylist(created.id, null, "New", null, null, null, null)
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)

        assertEquals("New", b.library.storedPlaylist(created.id)?.description)
    }

    @Test
    fun `a playlist removed from the library stays removed on both devices`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        val api = RealMetadataPlaylistAPI(a.client, a.store, a.library, a.mirror, a.history, LibraryPlaylistSetting(a.store))
        val created = api.createPlaylist("Road", null, true, false, "", listOf(vid(1)))
        listOf(a, b, a).forEach { it.sync!!.sync(it.session.load()!!, null) }
        assertTrue(created.id in b.library.savedPlaylists())
        a.clock += 1_000

        api.removeSavedPlaylists(listOf(created.id))
        listOf(a, b, a).forEach { it.sync!!.sync(it.session.load()!!, null) }

        assertFalse(created.id in a.library.savedPlaylists())
        assertFalse(created.id in b.library.savedPlaylists())
        assertEquals("Road", b.library.storedPlaylist(created.id)?.name)
    }

    @Test
    fun `a large image stays on its device`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        pair(piped, a, b)
        a.library.upsertPlaylist(StoredPlaylist(id = "piped-p", name = "Big", imageBase64 = "x".repeat(PLAYLIST_IMAGE_LIMIT + 1)))
        a.mirror.mirrorCreatePlaylist("piped-p", "Big", emptyList())
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals("", b.library.storedPlaylist("piped-p")?.imageBase64)
    }
}
