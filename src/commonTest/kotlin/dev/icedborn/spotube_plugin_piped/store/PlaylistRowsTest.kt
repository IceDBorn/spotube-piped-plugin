package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.albumStub
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lazy row cache of public playlists and albums. */
class PlaylistRowsTest {

    private class Harness {
        val piped = FakePiped()
        val store = EntityStore(FakeStorage())
        val client = PipedClient(piped) { FAKE_INSTANCE }
        val rows = PlaylistRows(client, store)

        suspend fun page(offset: Int, limit: Int, id: String = "PLpub") =
            rows.page(id, isAlbum = false, album = null, paging = PaginationStrategy.Offset(offset, limit), knownTotal = 0)

        suspend fun cached(id: String = "PLpub"): CachedRows? = store.getDecoded(PLAYLIST_ROWS_PREFIX + id, CachedRows.serializer())

        suspend fun age(id: String = "PLpub") {
            val rows = assertNotNull(cached(id))
            store.put(PLAYLIST_ROWS_PREFIX + id, json.encodeToJsonElement(rows.copy(lastVerifiedAt = 1)))
        }
    }

    @Test
    fun `a first open fetches page one and extends to the window`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        val page = h.page(0, 3)
        assertEquals((0 until 3).map(::vid), page.items.map { it.id })
        assertEquals(PaginationStrategy.Offset(3, 3), page.nextPagination)
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `scrolling past the cache extends it to the end`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        h.page(0, 3)
        h.piped.reset()
        val page = h.page(3, 3)
        assertEquals(listOf(vid(3), vid(4)), page.items.map { it.id })
        assertNull(page.nextPagination)
        assertTrue(assertNotNull(h.cached()).complete)
        // One continuation; page 1 was anchored moments ago.
        assertEquals(1, h.piped.total)
    }

    @Test
    fun `a complete cache is served without a request inside its window`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 3).map(::vid))
        h.page(0, 10)
        h.piped.reset()
        assertEquals(3, h.page(0, 10).items.size)
        assertEquals(0, h.piped.total)
    }

    @Test
    fun `a complete cache is re-anchored against page one after fifteen minutes`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        h.page(0, 10)
        h.age()
        h.piped.reset()
        assertEquals(5, h.page(0, 10).items.size)
        assertEquals(1, h.piped.total)
    }

    @Test
    fun `a playlist that grew on the web is reread after the anchor`() = runTest {
        val h = Harness()
        val list = h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        h.page(0, 10)
        list.videos += vid(5)
        h.age()
        h.piped.reset()
        assertEquals((0 until 6).map(::vid), h.page(0, 10).items.map { it.id })
        // The anchor sees a new total, so the chain restarts: page 1 and two continuations.
        assertEquals(3, h.piped.total)
    }

    @Test
    fun `a playlist that shrank on the web is truncated at the re-walk`() = runTest {
        val h = Harness()
        val list = h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        h.page(0, 10)
        list.videos.subList(3, 5).clear()
        h.age()
        h.piped.reset()
        assertEquals((0 until 3).map(::vid), h.page(0, 10).items.map { it.id })
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `a throttled body on first open caches nothing complete`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        h.piped.override("/playlists/PLpub", body = "{}", times = 1)
        val page = h.page(0, 3)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextPagination)
        assertNull(h.cached())
        assertEquals(1, h.piped.total)
        // Once the instance answers again, the next open fills the window.
        assertEquals(3, h.page(0, 3).items.size)
    }

    @Test
    fun `a blank continuation token is not read as the end`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("PLpub", "Mix", (0 until 5).map(::vid))
        val blank = """{"name":"Mix","videos":5,"nextpage":"","relatedStreams":[${h.piped.row(vid(0))},${h.piped.row(vid(1))}]}"""
        h.piped.override("/playlists/PLpub", body = blank, times = 2)
        val first = h.page(0, 3)
        assertEquals(listOf(vid(0), vid(1)), first.items.map { it.id })
        assertFalse(assertNotNull(h.cached()).complete)
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `album rows carry their raw page positions as track numbers`() = runTest {
        val h = Harness()
        h.piped.publicPlaylist("OLAK5uy_A1", "Album - One", (0 until 5).map(::vid))
        val album = albumStub("x", emptyList(), emptyList()).copy(id = "OLAK5uy_A1")
        val page = h.rows.page("OLAK5uy_A1", isAlbum = true, album = album, paging = PaginationStrategy.Offset(0, 5), knownTotal = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), page.items.map { it.trackNumber })
    }
}
