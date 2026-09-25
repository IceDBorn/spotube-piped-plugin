package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** Artist pages have no related-artists endpoint, so the section comes from the artist's own radio mix. */
class RelatedArtistsApiTest {

    private val artistId = "UCchannelalpha01a0000000"
    private val betaId = "UCbeta000000000000000"
    private val gammaId = "UCgamma00000000000000"
    private val allIds = listOf(artistId, betaId, gammaId)

    private fun harness(
        http: FakeHttp,
        radio: suspend (List<String>) -> List<MetadataTrack>,
    ): RealMetadataArtistAPI {
        val store = EntityStore(FakeStorage())
        val library = LocalLibrary(store)
        val client = PipedClient(http) { "https://piped.example" }
        val mirror = PipedSavedLibrary(http, store, library, AlbumLookup(client, store), InstanceSource(store, null)) { null }
        return RealMetadataArtistAPI(client, store, library, mirror, radio)
    }

    // The channel fixture carries 2 streams, so top10Tracks tops up from a music_songs search.
    private fun fakeHttp() = FakeHttp().apply {
        onPath("/search", body = Fixtures.searchMusicSongs)
        for (id in allIds) onPath("/channel/$id", body = channelBody(id))
    }

    private fun channelBody(id: String) = Fixtures.channel
        .replace("\"id\": \"UCchannelalpha01a0000000\"", "\"id\": \"$id\"")
        .replace("\"name\": \"Alpha Artist\"", "\"name\": \"Artist $id\"")

    @Test
    fun `the overview lists the ranked radio artists without the page artist`() = runTest {
        val http = fakeHttp()
        var seeds: List<String> = emptyList()
        val api = harness(http) { ids ->
            seeds = ids
            listOf(
                radioTrack(betaId),
                radioTrack(artistId),
                radioTrack(betaId),
                radioTrack(gammaId),
            )
        }
        val overview = api.artistOverview(artistId)
        assertEquals(
            listOf(betaId, gammaId),
            overview.relatedArtists.items.map { it.id },
        )
        assertEquals(
            overview.top10Tracks.take(2).map { it.id },
            seeds,
            "the radio is seeded with the first two top tracks, and the page artist is dropped from the result",
        )
    }

    @Test
    fun `avatars come from the channel fetch`() = runTest {
        val http = fakeHttp()
        val api = harness(http) { listOf(radioTrack(betaId)) }
        val related = api.artistOverview(artistId).relatedArtists.items.single()
        assertEquals("https://piped.example/av.jpg", related.thumbnails.first().url)
    }

    @Test
    fun `a failed avatar fetch keeps the radio artist`() = runTest {
        val http = FakeHttp().apply {
            onPath("/search", body = Fixtures.searchMusicSongs)
            // Registered first, so the radio artist's channel 500s while the page artist still resolves.
            on({ it.contains(betaId) }, status = 500, body = "")
            for (id in allIds) onPath("/channel/$id", body = channelBody(id))
        }
        val api = harness(http) { listOf(radioTrack(betaId)) }
        assertEquals(betaId, api.artistOverview(artistId).relatedArtists.items.single().id)
    }

    @Test
    fun `an empty radio leaves the rest of the overview intact`() = runTest {
        val api = harness(fakeHttp()) { emptyList() }
        val overview = api.artistOverview(artistId)
        assertTrue(overview.relatedArtists.items.isEmpty())
        assertTrue(overview.top10Tracks.isNotEmpty())
    }

    @Test
    fun `a failing radio leaves the rest of the overview intact`() = runTest {
        val api = harness(fakeHttp()) { error("instance is down") }
        val overview = api.artistOverview(artistId)
        assertTrue(overview.relatedArtists.items.isEmpty())
        assertTrue(overview.top10Tracks.isNotEmpty())
    }

    @Test
    fun `a synthetic artist never asks for a radio`() = runTest {
        var calls = 0
        val api = harness(fakeHttp()) { calls++; emptyList() }
        assertTrue(api.artistOverview("channel:Alpha Artist").relatedArtists.items.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `a second overview is served from the memory cache`() = runTest {
        var calls = 0
        val api = harness(fakeHttp()) { calls++; listOf(radioTrack(betaId)) }
        api.artistOverview(artistId)
        api.artistOverview(artistId)
        assertEquals(1, calls)
    }

    @Test
    fun `an empty first result is not cached`() = runTest {
        var calls = 0
        val api = harness(fakeHttp()) { calls++; emptyList() }
        api.artistOverview(artistId)
        api.artistOverview(artistId)
        assertEquals(2, calls)
    }

    @Test
    fun `a second relatedArtists call makes no top-tracks request`() = runTest {
        val http = fakeHttp()
        val api = harness(http) { listOf(radioTrack(betaId)) }
        api.relatedArtists(artistId, null)
        val after = http.countMatching("/search")
        api.relatedArtists(artistId, null)
        assertEquals(after, http.countMatching("/search"), "the memory cache must short-circuit the top-tracks search")
    }

    @Test
    fun `a radio slower than the timeout leaves the overview intact and is not cached`() = runTest {
        var calls = 0
        val api = harness(fakeHttp()) {
            calls++
            delay(10_000)
            listOf(radioTrack(betaId))
        }
        val overview = api.artistOverview(artistId)
        assertTrue(overview.relatedArtists.items.isEmpty())
        assertTrue(overview.top10Tracks.isNotEmpty())
        // A timeout must not be cached, or the section would stay blank for the rest of the session.
        assertTrue(api.artistOverview(artistId).relatedArtists.items.isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `relatedArtists returns the same list`() = runTest {
        val api = harness(fakeHttp()) { listOf(radioTrack(betaId)) }
        val page = api.relatedArtists(artistId, null)
        assertEquals(listOf(betaId), page.items.map { it.id })
        assertEquals(1, page.totalCount)
    }

    @Test
    fun `an offset past the end gives an empty page`() = runTest {
        val api = harness(fakeHttp()) { listOf(radioTrack(betaId)) }
        val page = api.relatedArtists(artistId, PaginationStrategy.Offset(50, 50))
        assertTrue(page.items.isEmpty())
    }
}
