package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The entries a device authored and the marks of kinds waiting for a push. */
class OwnEntriesTest {
    @Test
    fun `the device id is created once and kept`() = runTest {
        val store = EntityStore(FakeStorage())
        val first = SyncOwner(store, Random(1)).device()
        assertEquals(first, SyncOwner(store, Random(2)).device())
    }

    @Test
    fun `replaceAll swaps every entry of the kind`() = runTest {
        val own = LwwStore(EntityStore(FakeStorage()), "h0")
        own.record(listOf(LwwEntry("x", 1, JsonPrimitive(1)), LwwEntry("y", 2, JsonPrimitive(1))))
        own.replaceAll(listOf(LwwEntry("z", 3, JsonPrimitive(1))))
        assertEquals(listOf("z"), own.entries().map { it.key })
    }

    @Test
    fun `a record replaces the entry of its key and recordIfAbsent keeps it`() = runTest {
        val own = LwwStore(EntityStore(FakeStorage()), "lib")
        own.record(listOf(LwwEntry("t:x", 5, JsonPrimitive(1))))
        own.record(listOf(LwwEntry("t:x", 6)))
        own.recordIfAbsent(listOf(LwwEntry("t:x", 0, JsonPrimitive(1)), LwwEntry("t:y", 0, JsonPrimitive(1))))
        assertEquals(listOf(LwwEntry("t:x", 6), LwwEntry("t:y", 0, JsonPrimitive(1))), own.entries())
    }

    @Test
    fun `a mark made after a flush read survives the clear`() = runTest {
        val marks = DirtyMarks(EntityStore(FakeStorage()))
        var calls = 0
        marks.onMark = { calls++ }
        marks.mark("lib")
        val seen = marks.generation("lib")
        marks.mark("lib")
        marks.clear("lib", seen)
        assertEquals(listOf("lib"), marks.all())
        marks.clear("lib", marks.generation("lib"))
        assertTrue(marks.all().isEmpty())
        assertEquals(2, calls)
    }
}
