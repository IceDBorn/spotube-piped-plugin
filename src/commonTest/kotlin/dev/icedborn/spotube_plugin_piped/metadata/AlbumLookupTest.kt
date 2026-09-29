package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.ALBUM_LOOKUP_PREFIX
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.NO_ALBUM
import dev.icedborn.spotube_plugin_piped.store.NO_ALBUM_TTL_MS
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Resolving a video to the album playlist that holds it. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumLookupTest {

    private class Harness {
        val piped = FakePiped()
        val store = EntityStore(FakeStorage())
        val lookup = AlbumLookup(PipedClient(piped) { FAKE_INSTANCE }, store)

        /** One album by "Alpha Artist" returned for both searches. */
        fun album(videos: List<String>) {
            piped.publicPlaylist("OLAK5uy_A1", "Album - One", videos)
            piped.search("filter=music_albums", listOf(piped.albumRow("OLAK5uy_A1", "Album - One", "Alpha Artist")))
        }
    }

    @Test
    fun `a video on page one of an artist album skips the title search`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        assertEquals("OLAK5uy_A1", h.lookup.resolveForNames("Title", "Alpha Artist", vid(1)))
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `a spent budget throws instead of a verdict`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        h.piped.uploaders[vid(4)] = "Alpha Artist"
        val spent = assertFailsWith<AlbumLookup.LookupBudgetSpent> { h.lookup.resolveAlbumForVideo(vid(4), budget = 3) }
        assertEquals(3, spent.requests)
        assertEquals(3, h.piped.total)
    }

    @Test
    fun `a proven none is remembered for that video only`() = runTest {
        val h = Harness()
        h.album((0 until 3).map(::vid))
        assertNull(h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)))
        h.piped.reset()
        assertNull(h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)))
        assertEquals(0, h.piped.total)
        // A same-titled upload that is on the album still resolves.
        assertEquals("OLAK5uy_A1", h.lookup.resolveForNames("Title", "Alpha Artist", vid(1)))
    }

    @Test
    fun `a none verdict expires after a week`() = runTest {
        val h = Harness()
        h.store.put("track-album:${vid(9)}", JsonPrimitive("none@${epochMillis() - NO_ALBUM_TTL_MS - 1}"))
        assertNull(h.store.cachedTrackAlbum(vid(9)))
        h.store.put("track-album:${vid(8)}", JsonPrimitive("none"))
        assertNull(h.store.cachedTrackAlbum(vid(8)))
        h.store.cacheTrackAlbum(vid(7), NO_ALBUM)
        assertEquals(NO_ALBUM, h.store.cachedTrackAlbum(vid(7)))
    }

    @Test
    fun `a slow album lookup serves a stub and fills the cache in the background`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        h.piped.uploaders[vid(1)] = "Alpha Artist"
        h.piped.latencyMs = 3_000
        val library = LocalLibrary(h.store)
        val mirror = PipedSavedLibrary(h.piped, h.store, library, h.lookup, InstanceSource(h.store, null), noSessionScope) { null }
        val api = RealMetadataAlbumAPI(PipedClient(h.piped) { FAKE_INSTANCE }, h.store, library, mirror, h.lookup, backgroundScope)
        val stub = api.getAlbum("$ALBUM_LOOKUP_PREFIX${vid(1)}")
        assertEquals("$ALBUM_LOOKUP_PREFIX${vid(1)}", stub.id)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("OLAK5uy_A1", h.store.cachedTrackAlbum(vid(1)))
        assertEquals("OLAK5uy_A1", api.getAlbum("$ALBUM_LOOKUP_PREFIX${vid(1)}").id)
    }

    @Test
    fun `a video on a later page resolves by walking the chain`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        assertEquals("OLAK5uy_A1", h.lookup.resolveForNames("Title", "Alpha Artist", vid(4)))
        assertEquals(5, h.piped.total)
    }

    @Test
    fun `a chain that ends without the video proves it absent`() = runTest {
        val h = Harness()
        h.album((0 until 3).map(::vid))
        assertNull(h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)))
        assertEquals(4, h.piped.total)
    }

    @Test
    fun `no candidates at all is a definite none`() = runTest {
        val h = Harness()
        assertNull(h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)))
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `a failed continuation is inconclusive and throws`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        h.piped.override("/nextpage/playlists/", status = 500, body = "")
        assertFailsWith<IllegalStateException> { h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)) }
    }

    @Test
    fun `a failed search throws instead of caching none`() = runTest {
        val h = Harness()
        h.piped.override("/search", status = 500, body = "")
        assertFailsWith<IllegalStateException> { h.lookup.resolveForNames("Title", "Alpha Artist", vid(9)) }
    }

    @Test
    fun `resolveAlbumForVideo counts the streams fetch`() = runTest {
        val h = Harness()
        h.album((0 until 5).map(::vid))
        h.piped.uploaders[vid(1)] = "Alpha Artist"
        val resolved = h.lookup.resolveAlbumForVideo(vid(1))
        assertEquals("OLAK5uy_A1", resolved.albumId)
        assertEquals(3, resolved.requests)
    }
}
