package dev.icedborn.spotube_plugin_piped

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The audio client differs from the metadata twin: a non-2xx is a null (failed fetch), not a throw. */
class AudioPipedClientTest {

    private fun client(http: FakeHttp) = PipedClient(http) { "https://piped.example" }

    private val twoSongs = """
        {"items":[
          {"type":"stream","url":"https://www.youtube.com/watch?v=aaaaaaaaaaa","title":"One","duration":100,
           "uploaderName":"Artist","thumbnail":""},
          {"type":"channel","url":"https://www.youtube.com/channel/UCchannelalpha01a0000000","title":"Artist",
           "duration":-1,"uploaderName":"","thumbnail":""}
        ]}
    """.trimIndent()

    @Test
    fun `search keeps only stream and video rows`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = twoSongs) }
        val items = assertNotNull(client(http).searchSongs("one"))
        assertEquals(1, items.size)
        assertEquals("aaaaaaaaaaa", items.single().url.substringAfter("v="))
    }

    @Test
    fun `search returns null for a blank body`() = runTest {
        assertNull(client(FakeHttp().apply { onPath("/search", body = "") }).searchSongs("one"))
    }

    @Test
    fun `search returns null without an items array`() = runTest {
        assertNull(client(FakeHttp().apply { onPath("/search", body = "{}") }).searchSongs("one"))
    }

    @Test
    fun `search returns null on a non-2xx instead of throwing`() = runTest {
        val http = FakeHttp().apply { onPath("/search", status = 429, body = "throttled") }
        assertNull(client(http).searchSongs("one"))
    }

    @Test
    fun `search percent-encodes an emoji query`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = twoSongs) }
        client(http).searchSongs("\uD83C\uDFB5")
        assertTrue(http.requests.single().contains("q=%F0%9F%8E%B5"), http.requests.single())
    }

    @Test
    fun `streams decodes a body with audioStreams`() = runTest {
        val body = """{"title":"One","audioStreams":[{"url":"https://piped.example/s.webm","format":"251",
            "quality":"160kbps","mimeType":"audio/webm","itag":251,"bitrate":160000}]}"""
        val http = FakeHttp().apply { onPath("/streams/", body = body) }
        val info = assertNotNull(client(http).streams("aaaaaaaaaaa"))
        assertEquals("One", info.title)
        assertEquals(1, info.audioStreams.size)
    }

    @Test
    fun `streams returns null without an audioStreams array`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", body = """{"relatedStreams":[]}""") }
        assertNull(client(http).streams("aaaaaaaaaaa"))
    }

    @Test
    fun `streams returns null on a non-2xx instead of throwing`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", status = 503, body = "") }
        assertNull(client(http).streams("aaaaaaaaaaa"))
    }

    @Test
    fun `the unresolved-source error names the query length, not the text`() = runTest {
        val http = FakeHttp().apply { onPath("/search", status = 429, body = "throttled") }
        val api = RealPipedAudioAPI(PipedClient(http) { "https://piped.example" })
        val track = MetadataTrack(
            id = "notavideoid00",
            title = "My Private Search",
            durationMs = 200_000,
            trackNumber = null,
            discNumber = null,
            artists = emptyList(),
            album = null,
            thumbnails = emptyList(),
            explicit = null,
            popularity = null,
            isrcCode = null,
            externalUri = null,
        )
        // The host logs this message, so it must not carry the search text.
        val error = kotlin.runCatching { api.getStreamsByTrack(track) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error.message!!.contains("Private"), error.message!!)
        assertTrue(error.message!!.contains("17-char"), error.message!!)
    }
}
