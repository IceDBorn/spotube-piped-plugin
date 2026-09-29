package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.PipedPlaylistPage
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases of the row cache engine, driven by scripted pages. */
class RowCacheTest {

    private val key = "playlist.rows:PLtest"

    private fun row(id: String) = PipedSearchItem(type = "stream", url = "/watch?v=$id", title = id)

    // A row without a readable video id, which does not convert.
    private val deadRow = PipedSearchItem(type = "stream", url = "")

    private class Pages {
        var first: PipedPlaylistPage? = null
        val next = mutableMapOf<String, PipedPlaylistPage?>()
        var firstCalls = 0
        var nextCalls = 0
        var gate: CompletableDeferred<Unit>? = null
    }

    private fun TestScope.cache(store: EntityStore, pages: Pages, background: Boolean = false) = RowCache(
        store = store,
        key = key,
        fetchFirst = {
            pages.firstCalls++
            pages.gate?.await()
            pages.first
        },
        fetchNext = { token ->
            pages.nextCalls++
            pages.next[token]
        },
        convert = { item, _ -> item.toTrack() },
        background = if (background) backgroundScope else null,
    )

    private suspend fun EntityStore.seed(rows: CachedRows) = put(key, json.encodeToJsonElement(rows))

    private fun cached(count: Int, nextpage: String?, verifiedAt: Long = Long.MAX_VALUE / 2) = CachedRows(
        tracks = (0 until count).mapNotNull { row(vid(it)).toTrack() },
        nextpage = nextpage,
        complete = false,
        rawCount = count,
        lastVerifiedAt = verifiedAt,
    )

    @Test
    fun `an empty page 1 after a dead token keeps the cached rows`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(200, nextpage = "dead"))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = emptyList(), nextpage = null)
            next["dead"] = null
        }
        val rows = cache(store, pages).rows(minRows = 250)
        assertEquals(200, rows.tracks.size)
        assertFalse(rows.complete)
        assertEquals(200, assertNotNull(store.getDecoded(key, CachedRows.serializer())).tracks.size)
    }

    @Test
    fun `a reseed whose next page fails keeps the cached rows`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(200, nextpage = "dead"))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = (0 until 100).map { row(vid(it)) }, nextpage = "p2")
            next["dead"] = null
        }
        val rows = cache(store, pages).rows(minRows = 250)
        assertEquals(200, rows.tracks.size)
        assertEquals(200, assertNotNull(store.getDecoded(key, CachedRows.serializer())).tracks.size)
    }

    @Test
    fun `a reseed that catches up replaces the cached rows`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(200, nextpage = "dead"))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = (0 until 100).map { row(vid(it)) }, nextpage = "p2")
            next["dead"] = null
            next["p2"] = PipedPlaylistPage(relatedStreams = (100 until 200).map { row(vid(it)) }, nextpage = "p3")
        }
        val rows = cache(store, pages).rows(minRows = 200)
        assertEquals((0 until 200).map { vid(it) }, rows.tracks.map { it.id })
    }

    @Test
    fun `a reseed of a shrunk list whose last page is dead rows takes the cache's place`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(200, nextpage = "dead"))
        val pages = Pages().apply {
            // The list shrank to one page, and its new last page holds only rows that did not convert.
            first = PipedPlaylistPage(relatedStreams = (0 until 100).map { row(vid(it)) }, nextpage = "p2")
            next["dead"] = null
            next["p2"] = PipedPlaylistPage(relatedStreams = listOf(deadRow, deadRow), nextpage = null)
        }
        val rows = cache(store, pages).rows(minRows = 250)
        // The walk reached the true end, so the dead token must not survive for the next read to retry.
        assertNull(rows.nextpage)
        assertEquals(100, assertNotNull(store.getDecoded(key, CachedRows.serializer())).tracks.size)
    }

    @Test
    fun `a chain that ended on dead rows is not walked again`() = runTest {
        val store = EntityStore(FakeStorage())
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0)), row(vid(1))), nextpage = "p2")
            next["p2"] = PipedPlaylistPage(relatedStreams = listOf(deadRow, deadRow), nextpage = null)
        }
        val first = cache(store, pages).rows(minRows = 10)
        assertEquals(2, first.tracks.size)
        assertFalse(first.complete)
        assertEquals(1, pages.firstCalls)
        assertEquals(1, pages.nextCalls)
        cache(store, pages).rows(minRows = 10)
        assertEquals(1, pages.firstCalls)
        assertEquals(1, pages.nextCalls)
    }

    @Test
    fun `a chain that ended on dead rows grows once the listed total does`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(2, nextpage = null, verifiedAt = 0).copy(rawCount = 3))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0)), row(vid(1))), nextpage = "p2", videos = 4)
            next["p2"] = PipedPlaylistPage(relatedStreams = listOf(deadRow, row(vid(3))), nextpage = null)
        }
        val rows = cache(store, pages).rows(minRows = 3)
        assertEquals(listOf(vid(0), vid(1), vid(3)), rows.tracks.map { it.id })
        assertTrue(rows.complete)
    }

    @Test
    fun `a dead end is not walked again when the listing only lists more than the pages hold`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(2, nextpage = null, verifiedAt = 0).copy(rawCount = 3, listedTotal = 4))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0)), row(vid(1))), nextpage = "p2", videos = 4)
        }
        val rows = cache(store, pages).rows(minRows = 3)
        assertEquals(2, rows.tracks.size)
        assertEquals(1, pages.firstCalls)
        assertEquals(0, pages.nextCalls)
    }

    @Test
    fun `a dead end anchored against an empty page 1 keeps its rows`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(2, nextpage = null, verifiedAt = 0).copy(rawCount = 3))
        val pages = Pages().apply { first = PipedPlaylistPage(relatedStreams = emptyList(), nextpage = null, videos = -1) }
        assertEquals(2, cache(store, pages).rows(minRows = 3).tracks.size)
        assertEquals(2, assertNotNull(store.getDecoded(key, CachedRows.serializer())).tracks.size)
    }

    @Test
    fun `an empty page 1 keeps a complete cache whatever total it lists`() = runTest {
        for (total in listOf(51, 0, 10, -1)) {
            val store = EntityStore(FakeStorage())
            store.seed(cached(50, nextpage = null, verifiedAt = 0).copy(complete = true))
            val pages = Pages().apply { first = PipedPlaylistPage(relatedStreams = emptyList(), nextpage = null, videos = total) }
            assertEquals(50, cache(store, pages).rows(minRows = 10).tracks.size, "total $total")
            assertEquals(50, assertNotNull(store.getDecoded(key, CachedRows.serializer())).tracks.size)
        }
    }

    @Test
    fun `a cache of only dead rows is kept while page 1 has none either`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(CachedRows(nextpage = null, rawCount = 4, lastVerifiedAt = 0))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(deadRow, deadRow), nextpage = "p2", videos = 4)
            next["p2"] = PipedPlaylistPage(relatedStreams = listOf(deadRow, deadRow), nextpage = null)
        }
        cache(store, pages).rows(minRows = 5)
        assertEquals(1, pages.firstCalls)
        assertEquals(0, pages.nextCalls)
    }

    @Test
    fun `an empty list is not fetched again before its anchor is due`() = runTest {
        val store = EntityStore(FakeStorage())
        val pages = Pages().apply { first = PipedPlaylistPage(relatedStreams = emptyList(), nextpage = null, videos = 0) }
        repeat(3) { cache(store, pages).rows(minRows = 5) }
        assertEquals(1, pages.firstCalls)
    }

    @Test
    fun `a due anchor runs in the background when the cache covers the read`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(3, nextpage = "p2", verifiedAt = 0))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0)), row(vid(1)), row(vid(2))), nextpage = "p2")
            gate = CompletableDeferred()
        }
        val rows = cache(store, pages, background = true).rows(minRows = 2)
        assertEquals(3, rows.tracks.size)
        runCurrent()
        assertEquals(1, pages.firstCalls)
        pages.gate?.complete(Unit)
        runCurrent()
        val stamped = assertNotNull(store.getDecoded(key, CachedRows.serializer()))
        assertEquals(3, stamped.tracks.size)
        assertTrue(stamped.lastVerifiedAt > 0)
    }

    @Test
    fun `a complete cache survives a listed total above its rows`() = runTest {
        val store = EntityStore(FakeStorage())
        store.seed(cached(2, nextpage = null, verifiedAt = 0).copy(complete = true, listedTotal = 3))
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0))), nextpage = "p2", videos = 3)
        }
        val rows = cache(store, pages).rows(minRows = 1)
        assertEquals(1, pages.firstCalls)
        assertEquals(2, rows.tracks.size)
        assertTrue(rows.complete)
    }

    @Test
    fun `concurrent first reads share one page 1 fetch`() = runTest {
        val store = EntityStore(FakeStorage())
        val pages = Pages().apply {
            first = PipedPlaylistPage(relatedStreams = listOf(row(vid(0))), nextpage = null)
            gate = CompletableDeferred()
        }
        val a = async { cache(store, pages).rows(minRows = 1) }
        val b = async { cache(store, pages).rows(minRows = 1) }
        runCurrent()
        pages.gate?.complete(Unit)
        assertEquals(1, a.await().tracks.size)
        assertEquals(1, b.await().tracks.size)
        assertEquals(1, pages.firstCalls)
    }
}
