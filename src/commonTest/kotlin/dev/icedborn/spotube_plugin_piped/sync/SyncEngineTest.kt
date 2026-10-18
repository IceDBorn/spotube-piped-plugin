package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.dropSyncState
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Registration in spdevices, pushes of own files plus the manifest, and pulls that read only what moved. */
class SyncEngineTest {
    private val ice = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    private class NoteKind(store: EntityStore) : SyncKind {
        override val suffix = "set"
        override val own = LwwStore(store, suffix)
        var seen: Map<String, Winner> = emptyMap()

        override suspend fun apply(winners: Map<String, Winner>, self: String) {
            seen = winners
        }
    }

    private class Device(piped: FakePiped) {
        val store = EntityStore(FakeStorage())
        val kind = NoteKind(store)
        val marks = DirtyMarks(store)
        val owner = SyncOwner(store)
        val engine = SyncEngine(StorageHttp(piped), store, owner, marks, listOf(kind)) { 100L }

        suspend fun setUp(account: PipedAccount, id: String) {
            store.put("storage.device", JsonPrimitive(id))
        }

        suspend fun note(key: String, at: Long) {
            kind.own.record(listOf(LwwEntry(key, at, JsonPrimitive(1))))
            marks.mark("set")
        }
    }

    private suspend fun device(piped: FakePiped, id: String) = Device(piped).also { it.setUp(ice, id) }

    private class TwoFileKind(store: EntityStore) : SyncKind {
        override val suffix = "h0"
        override val own = LwwStore(store, "h0")
        val second = LwwStore(store, "h1")
        override val files: List<LwwStore> get() = listOf(own, second)
        var docs: List<SyncDoc> = emptyList()
        var applied = 0

        override suspend fun applyDocs(docs: List<SyncDoc>, self: String) {
            this.docs = docs
            applied++
        }
    }

    private class TwoFileDevice(piped: FakePiped, store: EntityStore, owner: SyncOwner) {
        val kind = TwoFileKind(store)
        val marks = DirtyMarks(store)
        val engine = SyncEngine(StorageHttp(piped), store, owner, marks, listOf(kind)) { 100L }
    }

    private suspend fun twoFileDevice(piped: FakePiped, id: String): TwoFileDevice {
        val store = EntityStore(FakeStorage())
        store.put("storage.device", JsonPrimitive(id))
        val owner = SyncOwner(store)
        return TwoFileDevice(piped, store, owner)
    }

    @Test
    fun `a kind with several files pushes each and gets every copy of each`() = runTest {
        val piped = FakePiped()
        val a = twoFileDevice(piped, "aaaa0001")
        val b = twoFileDevice(piped, "bbbb0002")
        a.kind.own.record(listOf(LwwEntry("x", 1, JsonPrimitive(1))))
        a.kind.second.record(listOf(LwwEntry("y", 2, JsonPrimitive(1))))
        a.marks.mark("h0")
        a.marks.mark("h1")
        a.engine.flush(ice)
        assertTrue("ice/spaaaa0001h0" in piped.files)
        assertTrue("ice/spaaaa0001h1" in piped.files)

        b.engine.pull(ice)

        assertEquals(4, b.kind.docs.size)
        val fromA = b.kind.docs.filter { it.device == "aaaa0001" }.flatMap { it.entries }.map { it.key }
        assertEquals(setOf("x", "y"), fromA.toSet())
    }

    @Test
    fun `a pull applies a kind only when one of its remote files moved`() = runTest {
        val piped = FakePiped()
        val a = twoFileDevice(piped, "aaaa0001")
        val b = twoFileDevice(piped, "bbbb0002")
        a.kind.own.record(listOf(LwwEntry("x", 1, JsonPrimitive(1))))
        a.marks.mark("h0")
        a.engine.flush(ice)
        b.engine.pull(ice)
        assertEquals(1, b.kind.applied)

        b.engine.pull(ice)
        assertEquals(1, b.kind.applied)

        a.kind.second.record(listOf(LwwEntry("y", 2, JsonPrimitive(1))))
        a.marks.mark("h1")
        a.engine.flush(ice)
        b.engine.pull(ice)
        assertEquals(2, b.kind.applied)
    }

    @Test
    fun `a file that did not arrive is fetched again without holding back the others`() = runTest {
        val piped = FakePiped()
        val a = twoFileDevice(piped, "aaaa0001")
        val b = twoFileDevice(piped, "bbbb0002")
        a.kind.own.record(listOf(LwwEntry("x", 1, JsonPrimitive(1))))
        a.kind.second.record(listOf(LwwEntry("y", 1, JsonPrimitive(1))))
        a.marks.mark("h0")
        a.marks.mark("h1")
        a.engine.flush(ice)
        piped.override("/storage/get?file=spaaaa0001h1", body = "{}", times = 1)
        val fromA = { b.kind.docs.filter { it.device == "aaaa0001" }.flatMap { it.entries }.map { it.key }.toSet() }

        b.engine.pull(ice)

        assertEquals(setOf("x"), fromA())
        piped.reset()
        b.engine.pull(ice)
        assertEquals(setOf("x", "y"), fromA())
        assertEquals(0, piped.count("/storage/get?file=spaaaa0001h0"))
    }

    @Test
    fun `a failed put does not hold back the kind's other files`() = runTest {
        val piped = FakePiped()
        val a = twoFileDevice(piped, "aaaa0001")
        a.kind.own.record(listOf(LwwEntry("x", 1, JsonPrimitive(1))))
        a.kind.second.record(listOf(LwwEntry("y", 1, JsonPrimitive(1))))
        a.marks.mark("h0")
        a.marks.mark("h1")
        piped.override("/storage/stat?file=spaaaa0001h0", status = 500, times = 1)

        a.engine.flush(ice)

        assertTrue("ice/spaaaa0001h1" in piped.files)
        assertEquals(listOf("h0"), a.marks.all())
    }

    @Test
    fun `two devices register without losing each other`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        assertTrue(a.engine.ensureRegistered(ice))
        assertTrue(b.engine.ensureRegistered(ice))
        val registry = decodeRegistry(piped.files.getValue("ice/spdevices").first)!!
        assertEquals(listOf("aaaa0001", "bbbb0002"), registry.devices.map { it.id })
        val before = piped.count("/storage/")
        assertTrue(a.engine.ensureRegistered(ice))
        assertEquals(before, piped.count("/storage/"))
    }

    @Test
    fun `a conflicting registry write is retried`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        a.engine.ensureRegistered(ice)
        val b = device(piped, "bbbb0002")
        piped.override(
            "/storage/put",
            body = """{"error":"Invalid etag provided! (File uploaded by another client?)"}""",
            times = 1,
        )
        assertTrue(b.engine.ensureRegistered(ice))
        assertEquals(2, decodeRegistry(piped.files.getValue("ice/spdevices").first)!!.devices.size)
    }

    @Test
    fun `a flush pushes dirty kinds and the manifest and the other device pulls them`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.note("piped.region", 50)
        a.engine.flush(ice)
        assertTrue(a.marks.all().isEmpty())
        assertTrue("ice/spaaaa0001set" in piped.files)
        assertEquals(1, decodeManifest(piped.files.getValue("ice/spaaaa0001m").first)!!.versions["set"])
        b.engine.ensureRegistered(ice)
        b.engine.pull(ice)
        assertEquals("aaaa0001", b.kind.seen.getValue("piped.region").device)
    }

    @Test
    fun `an idle pull costs one stat per device`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.note("k", 1)
        a.engine.flush(ice)
        b.engine.ensureRegistered(ice)
        b.engine.pull(ice)
        piped.reset()
        b.engine.pull(ice)
        assertEquals(2, piped.count("/storage/"))
        assertEquals(2, piped.count("/storage/stat"))
    }

    @Test
    fun `a failed push keeps the mark and a later flush sends it`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        a.engine.ensureRegistered(ice)
        a.note("k", 1)
        piped.override("/storage/put", status = 500, body = "", times = 1)
        a.engine.flush(ice)
        assertEquals(listOf("set"), a.marks.all())
        a.engine.flush(ice)
        assertTrue(a.marks.all().isEmpty())
        assertTrue("ice/spaaaa0001set" in piped.files)
    }

    @Test
    fun `a manifest left dirty goes out with the next flush`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        a.engine.ensureRegistered(ice)
        // The fake cannot fail only the manifest put, so the test starts from the state such a failure leaves.
        a.store.put("storage.manifest.dirty", JsonPrimitive(true))
        a.engine.flush(ice)
        assertTrue("ice/spaaaa0001m" in piped.files)
        assertEquals(null, a.store.get("storage.manifest.dirty"))
    }

    @Test
    fun `a wiped sync state never reuses a version another device cached`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.note("k", 1)
        a.engine.flush(ice)
        b.engine.ensureRegistered(ice)
        b.engine.pull(ice)
        a.store.dropSyncState()
        a.note("k2", 2)
        a.engine.flush(ice)
        assertEquals(2, decodeManifest(piped.files.getValue("ice/spaaaa0001m").first)!!.versions["set"])
        b.engine.pull(ice)
        assertEquals(setOf("k2"), b.kind.seen.keys)
    }

    @Test
    fun `a broken remote file keeps the cached copy`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.note("k", 1)
        a.engine.flush(ice)
        b.engine.ensureRegistered(ice)
        b.engine.pull(ice)
        a.note("k2", 2)
        a.engine.flush(ice)
        piped.override("/storage/get?file=spaaaa0001set", body = "{}", times = 1)
        b.engine.pull(ice)
        assertEquals(setOf("k"), b.kind.seen.keys)
        b.engine.pull(ice)
        assertEquals(setOf("k", "k2"), b.kind.seen.keys)
    }
}
