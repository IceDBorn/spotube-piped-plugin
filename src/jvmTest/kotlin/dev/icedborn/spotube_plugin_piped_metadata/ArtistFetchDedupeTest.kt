package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/** A channel fetch is shared per id, so a burst of artist calls costs one request. */
class ArtistFetchDedupeTest {

    private fun harness(http: FakeHttp): RealMetadataArtistAPI {
        val store = EntityStore(FakeStorage())
        val library = LocalLibrary(store)
        val client = PipedClient(http) { "https://piped.example" }
        val mirror = PipedSavedLibrary(http, store, library, AlbumLookup(client, store), InstanceSource(store, null)) { null }
        return RealMetadataArtistAPI(client, store, library, mirror)
    }

    @Test
    fun `two concurrent getArtist calls make one channel request`() = runTest {
        val http = FakeHttp().apply {
            onPath("/channel/", body = Fixtures.channel)
            // Both calls must be in flight together, or the second one just reads the cache.
            latencyMs = 10
        }
        val api = harness(http)
        val id = "UCchannelalpha01a0000000"

        val artists = listOf(async { api.getArtist(id) }, async { api.getArtist(id) }).awaitAll()

        assertEquals(1, http.countMatching("/channel/"), "concurrent calls should share one request")
        assertEquals(artists[0], artists[1])
        assertEquals("Alpha Artist", artists[0].name)
    }

    @Test
    fun `two calls for different ids are not shared`() = runTest {
        val http = FakeHttp().apply {
            onPath("/channel/", body = Fixtures.channel)
            latencyMs = 10
        }
        val api = harness(http)
        val other = "UCchannelbeta00020000000"
        listOf(async { api.getArtist("UCchannelalpha01a0000000") }, async { api.getArtist(other) }).awaitAll()
        assertEquals(2, http.countMatching("/channel/"), "the sharing is per id, not global")
    }

    @Test
    fun `a second call after the first is served from the store`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", body = Fixtures.channel) }
        val api = harness(http)
        val id = "UCchannelalpha01a0000000"
        api.getArtist(id)
        api.getArtist(id)
        assertEquals(1, http.countMatching("/channel/"))
    }

    @Test
    fun `a synthetic channel id needs no request`() = runTest {
        val http = FakeHttp()
        val artist = harness(http).getArtist("channel:Alpha Artist")
        assertEquals("Alpha Artist", artist.name)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a channel fetch failure serves a blank artist instead of throwing`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", status = 500, body = "") }
        val artist = harness(http).getArtist("UCchannelalpha01a0000000")
        assertTrue(artist.name.isBlank())
    }
}
