package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.logTrack
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Track metadata and album lookups shared through storage. */
class SyncedDictionaryTest {
    private val ice = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    private class Device(piped: FakePiped, val storage: FakeStorage = FakeStorage()) {
        val store = EntityStore(storage)
        val marks = DirtyMarks(store)
        val owner = SyncOwner(store)
        val kind = SyncedDictionary(store, marks) { 1_000L }
        val engine = SyncEngine(StorageHttp(piped), store, owner, marks, listOf(kind)) { 100L }
    }

    private suspend fun device(piped: FakePiped, id: String, storage: FakeStorage = FakeStorage()) = Device(piped, storage).also {
        it.store.put("storage.device", JsonPrimitive(id))
    }

    @Test
    fun `a noted track and its album fill the other device's caches`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.store.rememberTrack(logTrack(1))
        a.store.cacheTrackAlbum(vid(1), "OLAK5uy_album1")
        a.kind.note(listOf(vid(1), vid(2)))
        a.engine.flush(ice)
        assertEquals(1, piped.files.keys.count { it.startsWith("ice/spaaaa0001t") })

        b.engine.pull(ice)

        assertEquals(logTrack(1), b.store.cachedTrack(vid(1)))
        assertEquals("OLAK5uy_album1", b.store.cachedTrackAlbum(vid(1)))
        assertNull(b.store.cachedTrack(vid(2)))
    }

    @Test
    fun `a track already in the dictionary is not pushed again`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        a.store.rememberTrack(logTrack(1))
        a.kind.note(listOf(vid(1)))
        a.engine.flush(ice)
        piped.reset()

        a.kind.note(listOf(vid(1)))
        a.engine.flush(ice)

        assertEquals(0, piped.count("/storage/put"))
    }

    @Test
    fun `a shard whose write failed is pushed on the next flush`() = runTest {
        val piped = FakePiped()
        val storage = FakeStorage()
        val a = device(piped, "aaaa0001", storage)
        a.store.rememberTrack(logTrack(1))
        a.kind.note(listOf(vid(1)))
        storage.failPutsFor = "storage.own:t"
        a.engine.flush(ice)
        assertEquals(0, piped.files.keys.count { it.startsWith("ice/spaaaa0001t") })

        storage.failPutsFor = null
        a.engine.flush(ice)

        assertEquals(1, piped.files.keys.count { it.startsWith("ice/spaaaa0001t") })
    }

    @Test
    fun `a shard keeps its newest entries within the byte limit`() {
        val entries = (1..10).map { LwwEntry("t:$it", it.toLong(), JsonPrimitive("x".repeat(80))) }
        val kept = trimShard(entries, limit = 400)
        assertTrue(kept.isNotEmpty() && kept.size < entries.size)
        assertEquals(entries.reversed().take(kept.size), kept)
    }
}
