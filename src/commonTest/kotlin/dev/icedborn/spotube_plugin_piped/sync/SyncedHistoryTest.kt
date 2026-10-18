package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlayedTrack
import dev.icedborn.spotube_plugin_piped.store.logTrack
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays as events in a ring of segments, folded counts, baselines and the rebuilt history. */
class SyncedHistoryTest {
    private val ice = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    private class Device(piped: FakePiped, segmentEvents: Int, segmentCount: Int, storage: FakeStorage = FakeStorage()) {
        val store = EntityStore(storage)
        val history = PlayHistory(store)
        val marks = DirtyMarks(store)
        val owner = SyncOwner(store)
        val kind = SyncedHistory(store, history, marks, segmentEvents, segmentCount)
        val engine = SyncEngine(StorageHttp(piped), store, owner, marks, listOf(kind)) { 100L }

        suspend fun plays(id: String): Int? = playCounts(kind.files.map { SyncDoc(device = "self", entries = it.entries()) })[id]?.plays

        fun segment(index: Int) = kind.files.single { it.suffix == segmentSuffix(index) }
    }

    private suspend fun device(piped: FakePiped, id: String, segmentEvents: Int = SEGMENT_EVENTS, segmentCount: Int = SEGMENT_COUNT) =
        Device(piped, segmentEvents, segmentCount).also {
            it.store.put("storage.device", JsonPrimitive(id))
            it.owner.ensure(ice)
        }

    @Test
    fun `a play reaches the other device with its time and a track without metadata is left out`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.kind.record(vid(1), 5_000)
        a.kind.record(vid(2), 6_000)
        a.engine.flush(ice)
        b.store.rememberTrack(logTrack(1))

        b.engine.pull(ice)

        val played = b.history.all()
        assertEquals(listOf(vid(1)), played.map { it.track.id })
        assertEquals(1, played[0].plays)
        assertEquals(5_000, played[0].lastPlayedAt)
    }

    @Test
    fun `a full ring folds the oldest segment into the counts`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001", segmentEvents = 2, segmentCount = 3)
        val b = device(piped, "bbbb0002", segmentEvents = 2, segmentCount = 3)
        (1..7).forEach { a.kind.record(vid(1), it * 100_000L) }

        val folded = a.kind.own.entries().single { it.key == foldedKey(vid(1)) }
        assertEquals(PlayCount(2, 200_000), decodeCount(folded.value))
        assertEquals(1, a.segment(0).entries().size)

        a.engine.flush(ice)
        b.store.rememberTrack(logTrack(1))
        b.engine.pull(ice)
        assertEquals(7, b.history.all().single().plays)
    }

    @Test
    fun `a fold that fails at either write keeps the play and counts every play once`() = runTest {
        for (failing in listOf("storage.own:agg", "storage.own:h0")) {
            val storage = FakeStorage()
            val a = Device(FakePiped(), 2, 3, storage)
            (1..6).forEach { a.kind.record(vid(1), it * 100_000L) }
            storage.failPutsFor = failing
            a.kind.record(vid(1), 700_000)
            assertEquals(7, a.plays(vid(1)), failing)

            storage.failPutsFor = null
            a.kind.record(vid(1), 800_000)

            assertEquals(8, a.plays(vid(1)), failing)
            assertEquals(listOf(eventKey(vid(1), 800_000)), a.segment(0).entries().map { it.key }, failing)
        }
    }

    @Test
    fun `a device holding the new counts and the old copy of the folded segment counts each play once`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001", segmentEvents = 2, segmentCount = 3)
        val b = device(piped, "bbbb0002", segmentEvents = 2, segmentCount = 3)
        b.store.rememberTrack(logTrack(1))
        (1..6).forEach { a.kind.record(vid(1), it * 100_000L) }
        a.engine.flush(ice)
        a.kind.record(vid(1), 700_000)
        // The push misses the emptied segment, as when its mark lands after the flush read the marks.
        a.marks.clear(segmentSuffix(0), a.marks.generation(segmentSuffix(0)))
        a.engine.flush(ice)

        b.engine.pull(ice)
        assertEquals(6, b.history.all().single().plays)

        a.marks.mark(segmentSuffix(0))
        a.engine.flush(ice)
        b.engine.pull(ice)
        assertEquals(7, b.history.all().single().plays)
    }

    @Test
    fun `restored files go on above the watermark, so the next play counts`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001", segmentEvents = 2, segmentCount = 3)
        (1..7).forEach { a.kind.record(vid(1), it * 100_000L) }
        a.engine.flush(ice)
        a.owner.ensure(PipedAccount(FAKE_INSTANCE, "bob", "token-bob"))
        a.owner.ensure(ice)
        val published = assertNotNull(a.engine.publishedOwn(ice, a.kind.files.map { it.suffix }))
        assertTrue(a.kind.restore(published))

        a.kind.record(vid(1), 800_000)

        assertEquals(8, a.plays(vid(1)))
    }

    @Test
    fun `baselines overlap, so only the largest one counts`() = runTest {
        val piped = FakePiped()
        val a = device(piped, "aaaa0001")
        val b = device(piped, "bbbb0002")
        a.history.mergeCounts(listOf(PlayedTrack(logTrack(1), 3, 1_000)))
        b.history.mergeCounts(listOf(PlayedTrack(logTrack(1), 5, 2_000)))
        a.kind.importBaseline()
        b.kind.importBaseline()
        a.kind.record(vid(1), 9_000)
        a.engine.flush(ice)
        b.engine.flush(ice)

        b.engine.pull(ice)

        assertEquals(6, b.history.all().single().plays)
        assertEquals(9_000, b.history.all().single().lastPlayedAt)
    }
}
