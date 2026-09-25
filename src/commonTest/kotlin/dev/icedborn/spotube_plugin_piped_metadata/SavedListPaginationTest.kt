package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Saved lists page by the local id set, so every id is covered exactly once across the pages. */
class SavedListPaginationTest {

    /** The id shape the fixtures use: "vid" + a 7-digit index + "0". */
    private fun videoId(index: Int): String = "vid" + index.toString().padStart(7, '0') + "0"

    private class Harness(val http: FakeHttp, val storage: FakeStorage) {
        val store = EntityStore(storage)
        val library = LocalLibrary(store)
        val instance = InstanceSource(store, null)
        val client = PipedClient(http) { "https://piped.example" }
        val mirror = PipedSavedLibrary(http, store, library, AlbumLookup(client, store), instance) { null }
        val tracks = RealMetadataTrackAPI(client, store, library, mirror)
    }

    /** A /streams body, so a saved id resolves without any search. */
    private fun streamsBody(title: String) = """
        {"title":"$title","duration":200,"uploader":"Alpha Artist",
         "uploaderUrl":"https://www.youtube.com/channel/UCchannelalpha01a0000000",
         "relatedStreams":[]}
    """.trimIndent()

    @Test
    fun `saved track pages cover every id exactly once and end with a null next page`() = runTest {
        val harness = Harness(FakeHttp(), FakeStorage())
        val ids = (0 until 7).map { videoId(it) }
        harness.library.saveTracks(ids)
        harness.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))

        val seen = mutableListOf<String>()
        var page: PaginationStrategy.Offset = PaginationStrategy.Offset(0, 3)
        var pages = 0
        while (true) {
            val result = harness.tracks.savedTracks(page)
            assertTrue(result.items.size <= 3)
            seen += result.items.map { it.id }
            pages++
            val next = result.nextPagination
            if (next == null) break
            page = next as PaginationStrategy.Offset
            assertTrue(pages < 10, "pagination did not terminate")
        }
        assertEquals(ids.toSet(), seen.toSet())
        assertEquals(ids.size, seen.size)
    }

    @Test
    fun `a failing id is dropped from its page but later pages still resolve`() = runTest {
        val harness = Harness(FakeHttp(), FakeStorage())
        val good0 = "vid00000000"
        val bad = "vidBADBADBA"
        val good1 = "vid00000010"
        harness.library.saveTracks(listOf(good0, bad, good1))
        // Only the two good ids answer; the bad one gets no route and 404s.
        harness.http.on({ it.contains("/streams/") && !it.contains(bad) }, body = streamsBody("Track"))

        // The bad id sits alone in the middle page, so the pages around it must still resolve.
        assertEquals(listOf(good0), harness.tracks.savedTracks(PaginationStrategy.Offset(0, 1)).items.map { it.id })
        assertTrue(harness.tracks.savedTracks(PaginationStrategy.Offset(1, 1)).items.isEmpty())
        assertEquals(listOf(good1), harness.tracks.savedTracks(PaginationStrategy.Offset(2, 1)).items.map { it.id })
    }

    @Test
    fun `a saved track page reports the full id count even when an item fails`() = runTest {
        val harness = Harness(FakeHttp(), FakeStorage())
        harness.library.saveTracks(listOf("vid00000000", "vidBADBADBA"))
        harness.http.on({ it.contains("/streams/") && !it.contains("BAD") }, body = streamsBody("Track"))
        val page = harness.tracks.savedTracks(PaginationStrategy.Offset(0, 1))
        // The local id set is authoritative: the total counts the failed id too, and paging continues.
        assertEquals(2, page.totalCount)
        assertEquals(1, page.items.size)
        assertNotNull(page.nextPagination)
    }

    @Test
    fun `an empty saved set returns one empty page`() = runTest {
        val harness = Harness(FakeHttp(), FakeStorage())
        val page = harness.tracks.savedTracks(PaginationStrategy.Offset(0, 10))
        assertTrue(page.items.isEmpty())
        assertNull(page.nextPagination)
    }

    @Test
    fun `the default page size is fifty`() = runTest {
        val harness = Harness(FakeHttp(), FakeStorage())
        harness.library.saveTracks((0 until 60).map { videoId(it) })
        harness.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))
        val page = harness.tracks.savedTracks(null)
        assertEquals(50, page.items.size)
        assertNotNull(page.nextPagination)
    }
}
