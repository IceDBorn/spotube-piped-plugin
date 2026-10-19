package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.SKIP_KEEP_MS
import dev.icedborn.spotube_plugin_piped.store.Skip
import dev.icedborn.spotube_plugin_piped.store.SkipLog
import dev.icedborn.spotube_plugin_piped.store.refresh
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncedTasteTest {

    private fun taste(h: AccountHarness) = Taste(SkipLog(h.store), h.sync) { h.clock }

    @Test
    fun `a skip on one device reaches the other after a refresh`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        val b = AccountHarness(backgroundScope, piped, storageSync = true, profiles = true)
        listOf(a to "aaaa0001", b to "bbbb0002").forEach { (h, id) ->
            h.device(id)
            h.signIn("ice")
            refresh(h)
        }
        taste(a).record(listOf(Skip(vid(1), "ar1", a.clock)))
        refresh(a)
        refresh(b)
        assertEquals(listOf(Skip(vid(1), "ar1", a.clock)), taste(b).skips())
    }

    @Test
    fun `a skip pushes the taste file and the manifest`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val h = AccountHarness(backgroundScope, piped, storageSync = true)
        h.signIn()
        h.device("aaaa0001")
        refresh(h)
        val pushed = pushedBy(piped) { taste(h).record(listOf(Skip(vid(1), "ar1", h.clock))) }
        assertEquals(setOf("ice/spaaaa0001taste", "ice/spaaaa0001m"), pushed)
        assertEquals(4, piped.count("/storage/"))
    }

    @Test
    fun `skips from before storage are imported at the first sync`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true)
        SkipLog(h.store).append(listOf(Skip(vid(1), "ar1", h.clock), Skip(vid(2), "ar2", h.clock - 1)), h.clock)
        h.signIn()
        h.device("aaaa0001")
        refresh(h)
        val own = h.sync!!.tasteKind.own.entries()
        assertEquals(listOf(skipKey(vid(1), h.clock), skipKey(vid(2), h.clock - 1)), own.map { it.key })
    }

    @Test
    fun `the device's own skips count once`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true)
        h.signIn()
        h.device("aaaa0001")
        refresh(h)
        taste(h).record(listOf(Skip(vid(1), "ar1", h.clock)))
        assertEquals(1, taste(h).skips().size)
    }

    @Test
    fun `without storage the skips come from the log`() = runTest {
        val h = AccountHarness(backgroundScope, storageSync = true)
        taste(h).record(listOf(Skip(vid(3), "ar3", h.clock)))
        assertEquals(listOf(vid(3)), taste(h).skips().map { it.id })
        assertEquals(emptyList(), h.sync!!.tasteKind.own.entries())
    }

    @Test
    fun `a flush trims the own file to 30 days`() = runTest {
        var clock = 1_000L
        val kind = SyncedTaste(LwwStore(EntityStore(FakeStorage()), TASTE_SUFFIX)) { clock }
        kind.record(listOf(Skip(vid(1), "ar1", clock)))
        clock += SKIP_KEEP_MS
        kind.record(listOf(Skip(vid(2), "ar2", clock)))
        kind.beforeFlush()
        assertEquals(listOf(skipKey(vid(2), clock)), kind.own.entries().map { it.key })
    }

    @Test
    fun `entries that are no skip events read as nothing`() {
        assertEquals(null, skipOf(LwwEntry("e:${vid(1)}:5", 5, null)))
        assertEquals(Skip(vid(1), "ar1", 5), skipOf(LwwEntry(skipKey(vid(1), 5), 5, JsonPrimitive("ar1"))))
    }

    /** The storage files a scheduled push after [action] wrote. */
    private suspend fun TestScope.pushedBy(piped: FakePiped, action: suspend () -> Unit): Set<String> {
        val before = piped.files.mapValues { it.value.second }
        piped.reset()
        action()
        testScheduler.advanceTimeBy(FLUSH_DELAY_MS + 1)
        return piped.files.filter { before[it.key] != it.value.second }.keys
    }
}
