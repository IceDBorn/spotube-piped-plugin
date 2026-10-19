package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylist
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.REGION_GLOBAL
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chart region and the library playlist follow the newest change from any device. */
class SyncedSettingsTest {

    @Test
    fun `a region change reaches the other device`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            assertTrue(h.sync!!.prepare(h.session.load()!!))
            h.sync!!.sync(h.session.load()!!, null)
        }
        a.clock += 10_000
        RegionSetting(a.store, null).set(REGION_GLOBAL)
        LibraryPlaylistSetting(a.store).set(LibraryPlaylist.OFF)
        a.sync!!.settingChanged("piped.region")
        a.sync!!.settingChanged("piped.library.placeholder")
        a.sync!!.sync(a.session.load()!!, null)
        b.sync!!.sync(b.session.load()!!, null)
        assertEquals(REGION_GLOBAL, RegionSetting(b.store, null).stored())
        assertEquals(LibraryPlaylist.OFF, LibraryPlaylistSetting(b.store).stored())
    }

    @Test
    fun `the first sync keeps each device's own settings`() = runTest {
        val piped = FakePiped()
        val a = AccountHarness(backgroundScope, piped, storageSync = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true)
        RegionSetting(a.store, null).set(REGION_GLOBAL)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.signIn()
            h.device(id)
            assertTrue(h.sync!!.prepare(h.session.load()!!))
        }
        listOf(a, b, a).forEach { it.sync!!.sync(it.session.load()!!, null) }
        assertEquals(REGION_GLOBAL, RegionSetting(a.store, null).stored())
    }

    @Test
    fun `other keys are ignored`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true)
        h.signIn()
        h.sync!!.prepare(h.session.load()!!)
        h.sync!!.settingChanged("piped.form.theme")
        assertTrue(LwwStore(h.store, "set").entries().isEmpty())
    }
}
