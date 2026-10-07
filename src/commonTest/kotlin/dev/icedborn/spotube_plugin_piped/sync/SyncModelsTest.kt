package dev.icedborn.spotube_plugin_piped.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File names, the merge order of entries, the size cap and the decoders that refuse a failed fetch. */
class SyncModelsTest {
    private fun saved(key: String, at: Long) = LwwEntry(key, at, JsonPrimitive(1))

    @Test
    fun `names follow the device id`() {
        assertEquals("spab12cd34m", manifestFile("ab12cd34"))
        assertEquals("spab12cd34lib", dataFile("ab12cd34", "lib"))
        val id = newDeviceId(Random(7))
        assertTrue(Regex("^[a-z0-9]{8}$").matches(id))
    }

    @Test
    fun `the newest entry wins and a tie goes to the larger device id`() {
        val a = SyncDoc(device = "aaaa0000", entries = listOf(saved("t:x", 5), LwwEntry("t:y", 9)))
        val b = SyncDoc(device = "bbbb0000", entries = listOf(LwwEntry("t:x", 7), saved("t:y", 9)))
        val winners = mergeLww(listOf(a, b))
        assertTrue(winners.getValue("t:x").entry.removed)
        assertEquals("bbbb0000", winners.getValue("t:y").device)
        assertTrue(!winners.getValue("t:y").entry.removed)
    }

    @Test
    fun `fitting drops the oldest removals first and gives up on live entries`() {
        val live = (1..10).map { saved("t:$it", it.toLong()) }
        val removals = (1..2000).map { LwwEntry("t:r$it", it.toLong()) }
        val doc = SyncDoc(device = "aaaa0000", entries = live + removals)
        val cap = encodeDoc(doc.copy(entries = live + removals.takeLast(100))).encodeToByteArray().size
        val fitted = assertNotNull(fitToCap(doc, cap))
        assertTrue(fitted.entries.containsAll(live))
        assertTrue(fitted.entries.none { it.key == "t:r1" })
        assertTrue(fitted.entries.any { it.key == "t:r2000" })
        assertNull(fitToCap(SyncDoc(device = "aaaa0000", entries = live), cap = 10))
    }

    @Test
    fun `a blank body, an empty object or an unknown version is a failed fetch`() {
        assertNull(decodeSyncDoc(null))
        assertNull(decodeSyncDoc(""))
        assertNull(decodeSyncDoc("{}"))
        assertNull(decodeSyncDoc("""{"v":2,"device":"a","entries":[]}"""))
        assertNull(decodeSyncDoc("""{"v":{},"device":"a","entries":[]}"""))
        assertNull(decodeSyncDoc("""{"v":"x","device":"a","entries":[]}"""))
        assertNull(decodeRegistry("{}"))
        assertNull(decodeManifest("""{"device":"a"}"""))
        val doc = assertNotNull(decodeSyncDoc("""{"device":"aaaa0000","entries":[{"k":"t:x","a":3}]}"""))
        assertTrue(doc.entries.single().removed)
    }

    @Test
    fun `a doc survives a round trip`() {
        val doc = SyncDoc(device = "aaaa0000", entries = listOf(saved("t:x", 1), LwwEntry("t:y", 2)))
        assertEquals(doc, decodeSyncDoc(encodeDoc(doc)))
    }
}
