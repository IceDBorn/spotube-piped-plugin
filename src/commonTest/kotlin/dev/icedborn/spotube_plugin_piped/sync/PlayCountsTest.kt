package dev.icedborn.spotube_plugin_piped.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Counting plays from events, folded counts and import baselines. */
class PlayCountsTest {

    private fun event(id: String, at: Long, generation: Long = 0) = LwwEntry(eventKey(id, at), at, JsonPrimitive(generation))

    @Test
    fun `an event key gives back its video id`() {
        assertEquals("a-b_c123456", eventVideoId(eventKey("a-b_c123456", 5)))
        assertNull(eventVideoId(foldedKey("a-b_c123456")))
    }

    @Test
    fun `counts add every device's events and folded plays and the largest baseline`() {
        val a = SyncDoc(
            device = "a",
            entries = listOf(
                event("v1", 10),
                LwwEntry(foldedKey("v1"), 5, countValue(PlayCount(2, 4))),
                LwwEntry(baselineKey("v1"), 0, countValue(PlayCount(3, 2))),
            ),
        )
        val b = SyncDoc(
            device = "b",
            entries = listOf(
                event("v1", 20),
                LwwEntry(baselineKey("v1"), 0, countValue(PlayCount(5, 1))),
                LwwEntry(baselineKey("v2"), 0, countValue(PlayCount(1, 7))),
            ),
        )
        val counts = playCounts(listOf(a, b))
        assertEquals(PlayCount(2 + 2 + 5, 20), counts["v1"])
        assertEquals(PlayCount(1, 7), counts["v2"])
    }

    @Test
    fun `folding adds a segment's plays and keeps the most played under the limit`() {
        val agg = listOf(
            LwwEntry(foldedKey("v1"), 1, countValue(PlayCount(2, 5))),
            LwwEntry(baselineKey("v9"), 0, countValue(PlayCount(4, 1))),
        )
        val events = listOf(event("v1", 30), event("v2", 31), event("v3", 32), event("v3", 33))
        val folded = foldInto(agg, events, through = 0, at = 40, limit = 2)
        assertEquals(PlayCount(3, 30), decodeCount(folded.single { it.key == foldedKey("v1") }.value))
        assertEquals(PlayCount(2, 33), decodeCount(folded.single { it.key == foldedKey("v3") }.value))
        assertTrue(folded.none { it.key == foldedKey("v2") })
        assertTrue(folded.any { it.key == baselineKey("v9") })
        assertEquals(0L, foldedThrough(folded))
        assertSame(folded, foldInto(folded, events, through = 0, at = 50))
    }

    @Test
    fun `events a device folded already count once, through its folded counts`() {
        val agg = foldInto(emptyList(), listOf(event("v1", 10), event("v1", 11)), through = 0, at = 20)
        val a = SyncDoc(device = "a", entries = agg + event("v1", 10) + event("v1", 11) + event("v1", 30, generation = 1))
        val b = SyncDoc(device = "b", entries = listOf(event("v1", 40)))
        assertEquals(PlayCount(2 + 1 + 1, 40), playCounts(listOf(a, b))["v1"])
    }
}
