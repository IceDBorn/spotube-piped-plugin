package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Top tracks on artist pages must only contain tracks by that artist. */
class ArtistTopTracksTest {

    private val artistId = "UCchannelalpha01a0000000"
    private val otherChannelId = "UCother00000000000000000"
    private val diffChannelId = "UCdifferent0000000000000"
    private val randChannelId = "UCrandom0000000000000000"

    private fun harness(http: FakeHttp): RealMetadataArtistAPI {
        val store = EntityStore(FakeStorage())
        val library = LocalLibrary(store)
        val client = PipedClient(http) { "https://piped.example" }
        val mirror =
            PipedSavedLibrary(http, store, library, AlbumLookup(client, store), InstanceSource(store, null), noSessionScope) { null }
        return RealMetadataArtistAPI(client, store, library, mirror)
    }

    private fun channelJson(id: String, name: String, streams: List<JsonObject> = emptyList()) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("avatarUrl", "")
        put("relatedStreams", JsonArray(streams))
    }.toString()

    private fun searchJson(items: List<JsonObject>) = buildJsonObject {
        put("items", JsonArray(items))
        put("nextpage", null as String?)
    }.toString()

    private fun streamItem(id: String, title: String, uploader: String, uploaderChannelId: String) = buildJsonObject {
        put("type", "stream")
        put("url", "/watch?v=$id")
        put("title", title)
        put("name", title)
        put("uploaderName", uploader)
        put("uploaderUrl", if (uploaderChannelId.isNotEmpty()) "/channel/$uploaderChannelId" else "")
        put("duration", 200)
    }

    @Test
    fun `search results from other artists are dropped from top tracks`() = runTest {
        val http = FakeHttp().apply {
            onPath("/channel/$artistId", body = channelJson(artistId, "Alpha Artist"))
            onPath(
                "/search",
                body = searchJson(
                    listOf(
                        streamItem(vid(1), "Alpha Song 1", "Alpha Artist - Topic", artistId),
                        streamItem(vid(2), "Other Song", "Other Artist - Topic", otherChannelId),
                        streamItem(vid(3), "Alpha Song 2", "Alpha Artist", diffChannelId),
                        streamItem(vid(4), "Random Song", "Random Uploader", randChannelId),
                    ),
                ),
            )
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(artistId)

        assertEquals(listOf(vid(1), vid(3)), tracks.map { it.id })
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(1, http.countMatching("/search"))
    }

    @Test
    fun `channel streams uploaded by other channels are dropped`() = runTest {
        val channelStreams = listOf(
            streamItem(vid(1), "Own Track", "Alpha Artist", artistId),
            streamItem(vid(2), "Guest Video", "Guest Artist", otherChannelId),
        )
        val http = FakeHttp().apply {
            onPath("/channel/$artistId", body = channelJson(artistId, "Alpha Artist", channelStreams))
            onPath("/search", body = searchJson(emptyList()))
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(artistId)

        assertEquals(listOf(vid(1)), tracks.map { it.id })
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(1, http.countMatching("/search"))
    }

    @Test
    fun `topic channel searches by cleaned name and matches topic uploaders`() = runTest {
        val topicArtistId = "UCtopicbeta0000000000000"
        var searchedQuery = ""
        val http = FakeHttp().apply {
            onPath("/channel/$topicArtistId", body = channelJson(topicArtistId, "Beta Artist - Topic"))
            on(
                {
                    if (it.contains("/search")) searchedQuery = it
                    it.contains("/search")
                },
                body = searchJson(
                    listOf(
                        streamItem(vid(1), "Beta Song 1", "Beta Artist - Topic", topicArtistId),
                        streamItem(vid(2), "Other Song", "Gamma Artist - Topic", otherChannelId),
                    ),
                ),
            )
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(topicArtistId)

        assertFalse(searchedQuery.contains("Topic", ignoreCase = true), "query must not contain Topic")
        assertTrue(searchedQuery.contains("q=Beta+Artist") || searchedQuery.contains("q=Beta%20Artist"))
        assertEquals(listOf(vid(1)), tracks.map { it.id })
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(1, http.countMatching("/search"))
    }

    @Test
    fun `topic channel with trailing whitespace is cleaned and matches without topic suffix`() = runTest {
        val topicArtistId = "UCtopicbeta0000000000000"
        var searchedQuery = ""
        val http = FakeHttp().apply {
            onPath("/channel/$topicArtistId", body = channelJson(topicArtistId, "Beta Artist - Topic "))
            on(
                {
                    if (it.contains("/search")) searchedQuery = it
                    it.contains("/search")
                },
                body = searchJson(
                    listOf(
                        streamItem(vid(1), "Beta Song 1", "Beta Artist - Topic ", topicArtistId),
                    ),
                ),
            )
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(topicArtistId)

        assertFalse(searchedQuery.contains("Topic", ignoreCase = true), "query must not contain Topic")
        assertTrue(searchedQuery.contains("q=Beta+Artist") || searchedQuery.contains("q=Beta%20Artist"))
        assertEquals(listOf(vid(1)), tracks.map { it.id })
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(1, http.countMatching("/search"))
    }

    @Test
    fun `own channel uploads win ties over other topic channel matches`() = runTest {
        val http = FakeHttp().apply {
            onPath("/channel/$artistId", body = channelJson(artistId, "Alpha Artist"))
            onPath(
                "/search",
                body = searchJson(
                    listOf(
                        streamItem(vid(1), "Song Topic", "Alpha Artist - Topic", otherChannelId),
                        streamItem(vid(2), "Song Channel", "Alpha Artist", artistId),
                    ),
                ),
            )
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(artistId)

        assertEquals(listOf(vid(2), vid(1)), tracks.map { it.id })
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(1, http.countMatching("/search"))
    }

    @Test
    fun `channel named Topic suffix alone with blank uploader drops stream instead of matching empty names`() = runTest {
        val blankChannelStreams = listOf(
            streamItem(vid(1), "Blank Track", "", ""),
        )
        val http = FakeHttp().apply {
            onPath("/channel/$artistId", body = channelJson(artistId, " - Topic", blankChannelStreams))
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(artistId)

        assertTrue(tracks.isEmpty())
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(0, http.countMatching("/search"))
    }

    @Test
    fun `at least five channel streams skips the search request`() = runTest {
        val channelStreams = (1..5).map {
            streamItem(vid(it), "Track $it", "Alpha Artist", artistId)
        }
        val http = FakeHttp().apply {
            onPath("/channel/$artistId", body = channelJson(artistId, "Alpha Artist", channelStreams))
            onPath("/search", body = searchJson(emptyList()))
        }
        val api = harness(http)
        val tracks = api.getArtistTop10Tracks(artistId)

        assertEquals(5, tracks.size)
        assertEquals(1, http.countMatching("/channel/"))
        assertEquals(0, http.countMatching("/search"), "5 channel streams must skip search")
    }
}
