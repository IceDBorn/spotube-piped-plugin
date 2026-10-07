package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Storage detection per account, its 24-hour recheck, and the switch that decides the sync path. */
class StorageSupportTest {
    private val ice = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    private class Setup(val piped: FakePiped = FakePiped()) {
        var clock = 1_000L
        var account: PipedAccount? = null
        val store = EntityStore(FakeStorage())
        val support = StorageSupport(StorageHttp(piped), store) { clock }
        val setting = StorageSyncSetting(store)
        val mode = StorageMode(setting, support) { account }
    }

    @Test
    fun `an instance with storage is supported and the mode is active`() = runTest {
        val s = Setup()
        s.account = ice
        assertEquals(StorageState.UNCHECKED, s.mode.state())
        assertEquals(true, s.support.check(ice))
        assertEquals(StorageState.ACTIVE, s.mode.state())
        assertTrue(s.mode.active(ice))
        assertEquals(1, s.piped.count("/config"))
        assertEquals(1, s.piped.count("/storage/stat?file=spdevices"))
    }

    @Test
    fun `an instance without storage is unsupported after one config read`() = runTest {
        val s = Setup(FakePiped().apply { storageEnabled = false })
        s.account = ice
        assertEquals(false, s.support.check(ice))
        assertEquals(StorageState.UNSUPPORTED, s.mode.state())
        assertEquals(0, s.piped.count("/storage/"))
    }

    @Test
    fun `a failed check keeps the previous verdict`() = runTest {
        val s = Setup()
        s.support.check(ice)
        s.piped.override("/config", status = 500, body = "")
        assertEquals(null, s.support.check(ice))
        assertEquals(true, s.support.verdict(ice)?.supported)
    }

    @Test
    fun `a verdict is rechecked only after 24 hours`() = runTest {
        val s = Setup()
        s.support.checkIfStale(ice)
        s.clock += SUPPORT_RECHECK_MS - 1
        s.support.checkIfStale(ice)
        assertEquals(1, s.piped.count("/config"))
        s.clock += 1
        s.support.checkIfStale(ice)
        assertEquals(2, s.piped.count("/config"))
    }

    @Test
    fun `another account does not inherit the verdict`() = runTest {
        val s = Setup()
        s.support.check(ice)
        val other = ice.copy(username = "bob", token = "token-bob")
        s.account = other
        assertEquals(null, s.support.verdict(other))
        assertFalse(s.mode.active(other))
    }

    @Test
    fun `the switch turns the mode off and signed out is never active`() = runTest {
        val s = Setup()
        s.support.check(ice)
        s.account = ice
        s.setting.set(false)
        assertEquals(StorageState.OFF, s.mode.state())
        assertFalse(s.mode.active(ice))
        s.setting.set(true)
        s.account = null
        assertEquals(StorageState.SIGNED_OUT, s.mode.state())
        assertFalse(s.mode.active(ice))
    }
}
