package dev.icedborn.spotube_plugin_piped.audio

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.fakes.FakeHttp
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioSource
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The audio client differs from the metadata twin: a non-2xx is a null (failed fetch), not a throw. */
class AudioPipedClientTest {

    private fun client(http: FakeHttp) = AudioPipedClient(PipedClient(http) { "https://piped.example" })

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

    // One /streams body for the fallback: an audio list the proxy refuses (403) plus the muxed row.
    // A refused URL is a route with status 403, like the real proxy.
    private val refusedBody = """{"title":"One","audioStreams":[
        {"url":"https://piped.example/audio/251","format":"251","quality":"160kbps","mimeType":"audio/webm","itag":251,"bitrate":160000},
        {"url":"https://piped.example/audio/140","format":"140","quality":"128kbps","mimeType":"audio/mp4","itag":140,"bitrate":128000}],
        "videoStreams":[
        {"url":"https://piped.example/video/137","format":"137","quality":"","mimeType":"video/mp4","itag":137,"bitrate":200000,"videoOnly":true},
        {"url":"https://piped.example/video/18","format":"MPEG_4","quality":"","mimeType":"video/mp4","itag":18,"bitrate":0,"videoOnly":false}]}"""

    private fun sourceApi(http: FakeHttp) = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" }))

    private fun basicSource() = AudioSource.Basic(
        id = "xlBUW0FPxJs",
        title = "",
        artist = null,
        album = null,
        thumbnails = emptyList(),
        externalUri = null,
        confidence = 1f,
    )

    @Test
    fun `a refused audio row falls through to the muxed progressive row`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", status = 403, body = "")
            onPath("/video/18", body = "mp4")
            onPath("/video/137", status = 403, body = "")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        // itag 18 reports bitrate 0, so it is only rankable with the fallback bitrate.
        assertEquals(listOf("https://piped.example/video/18"), stream.streams.map { it.url })
        assertEquals("aac", stream.streams.single().codec)
    }

    @Test
    fun `a video-only row never becomes a source`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", status = 403, body = "")
            onPath("/video/18", status = 403, body = "")
            onPath("/video/137", body = "mp4")
        }
        // 137 is videoOnly:true, so it is never probed as audio and nothing is left to offer.
        assertTrue(sourceApi(http).getStreamsOfAudioSource(basicSource()).isEmpty())
        assertEquals(0, http.countMatching("/video/137"))
    }

    @Test
    fun `a muxed row with a blank url is skipped rather than mapped to a broken stream`() = runTest {
        val body = """{"title":"One","audioStreams":[],"videoStreams":[
            {"url":"","format":"MPEG_4","quality":"","mimeType":"video/mp4","itag":18,"bitrate":0,"videoOnly":false}]}"""
        val http = FakeHttp().apply { onPath("/streams/", body = body) }
        assertTrue(sourceApi(http).getStreamsOfAudioSource(basicSource()).isEmpty())
    }

    @Test
    fun `a body with no usable progressive row returns an empty list instead of throwing`() = runTest {
        val body = """{"title":"One","audioStreams":[{"url":"","format":"251","quality":"160kbps",
            "mimeType":"audio/webm","itag":251,"bitrate":160000}],"videoStreams":[]}"""
        val http = FakeHttp().apply { onPath("/streams/", body = body) }
        // The host moves on to the next candidate, so this must stay a quiet empty list.
        assertTrue(sourceApi(http).getStreamsOfAudioSource(basicSource()).isEmpty())
    }

    @Test
    fun `a working audio list is used as it always was and the muxed row is never probed`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", body = "audio", contentType = "audio/webm")
            onPath("/video/18", body = "mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(2, stream.streams.size)
        // Only the richest row is probed, and the fallback is not reached.
        assertEquals(1, http.countMatching("/audio/"))
        assertEquals(0, http.countMatching("/video/"))
    }

    @Test
    fun `a 5xx or 429 is instance trouble, not a refusal, and keeps the stream`() = runTest {
        for (status in listOf(500, 429, 502, 503, 302)) {
            val http = FakeHttp().apply {
                onPath("/streams/", body = refusedBody)
                onPath("/audio/251", status = status, body = "")
                // No Content-Type, so the second row has no verdict either and nothing is proved.
                onPath("/audio/140", body = "audio")
                onPath("/video/18", body = "mp4")
            }
            val stream = assertNotNull(
                sourceApi(http).getStreamsOfAudioSource(basicSource()).single(),
                "status $status should not drop the row",
            )
            // Only 403/404 is a statement about the URL, so a blip keeps the row, and an inconclusive
            // verdict sends the probe to the rest before the rows are offered.
            assertEquals("https://piped.example/audio/251", stream.streams.first().url, "status $status")
            assertEquals(2, stream.streams.size, "status $status should keep both rows")
            assertEquals(2, http.countMatching("/audio/"), "status $status should probe the rest")
            assertEquals(0, http.countMatching("/video/"), "status $status should not need the fallback")
        }
    }

    @Test
    fun `a blip on the richest row drops it once another row proves served`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/251", status = 429, body = "")
            onPath("/audio/140", body = "audio", contentType = "audio/mp4")
            onPath("/video/18", body = "mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        // The host picks one row and has no failover, so an unproved URL never competes with a serving one.
        assertEquals(listOf("https://piped.example/audio/140"), stream.streams.map { it.url })
        assertEquals(2, http.countMatching("/audio/"))
        assertEquals(0, http.countMatching("/video/"))
    }

    @Test
    fun `a 403 is a refusal and drops the row`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/251", status = 403, body = "")
            onPath("/audio/140", status = 403, body = "")
            onPath("/video/18", body = "mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(listOf("https://piped.example/video/18"), stream.streams.map { it.url })
        // The refused richest row is not probed a second time.
        assertEquals(1, http.countMatching("/audio/251"))
        assertEquals(1, http.countMatching("/audio/140"))
    }

    @Test
    fun `probes are HEADs with Content-Type check`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", status = 403, body = "")
            onPath("/video/18", body = "mp4")
        }
        sourceApi(http).getStreamsOfAudioSource(basicSource())
        val probes = http.methods.zip(http.headers).filter { !it.first.second.contains("/streams/") }
        val probeHeaders = probes.map { it.second }
        assertEquals(3, probes.size)
        assertTrue(probes.all { it.first.first == HttpMethod.Head }, "probes: $probes")
        assertTrue(probeHeaders.all { it == null }, "headers: $probeHeaders")
    }

    @Test
    fun `a 206 with an audio Content-Type is served, so only the richest row is probed`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/251", status = 206, body = "", contentType = "audio/webm")
            onPath("/audio/", status = 403, body = "")
            onPath("/video/18", status = 403, body = "")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(2, stream.streams.size)
        assertEquals(1, http.countMatching("/audio/"))
        assertEquals(0, http.countMatching("/video/"))
    }

    @Test
    fun `a 429 and a 500 are inconclusive, so the next row gets probed`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/251", status = 429, body = "")
            onPath("/audio/140", status = 500, body = "")
            onPath("/video/18", body = "mp4", contentType = "audio/mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        // No verdict served anything, so the unknown rows are offered and the muxed fallback is untouched.
        assertEquals(
            listOf("https://piped.example/audio/251", "https://piped.example/audio/140"),
            stream.streams.map { it.url },
        )
        assertEquals(2, http.countMatching("/audio/"))
        assertEquals(0, http.countMatching("/video/"))
    }

    @Test
    fun `a 200 whose Content-Type is not media is inconclusive, so the next row is probed`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/251", body = "<html>gone</html>", contentType = "text/html")
            onPath("/audio/140", status = 206, body = "", contentType = "audio/mp4")
            onPath("/video/18", status = 403, body = "")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        // text/html at 200 proves nothing: only the row the instance answered with media is offered.
        assertEquals(listOf("https://piped.example/audio/140"), stream.streams.map { it.url })
        assertEquals(2, http.countMatching("/audio/"))
        assertEquals(0, http.countMatching("/video/"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `after a refusal the remaining rows are probed in parallel`() = runTest {
        val body = """{"title":"One","audioStreams":[
            {"url":"https://piped.example/audio/251","format":"251","quality":"160kbps","mimeType":"audio/webm","itag":251,"bitrate":160000},
            {"url":"https://piped.example/audio/140","format":"140","quality":"128kbps","mimeType":"audio/mp4","itag":140,"bitrate":128000},
            {"url":"https://piped.example/audio/250","format":"250","quality":"70kbps","mimeType":"audio/webm","itag":250,"bitrate":70000}]}"""
        val http = FakeHttp().apply {
            latencyMs = 100
            onPath("/streams/", body = body)
            onPath("/audio/251", status = 403, body = "")
            onPath("/audio/", body = "audio", contentType = "audio/webm")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(listOf("https://piped.example/audio/140", "https://piped.example/audio/250"), stream.streams.map { it.url })
        // /streams, the richest probe, then one round for both remaining probes; serial would be 400.
        assertEquals(300, testScheduler.currentTime)
    }

    @Test
    fun `a row with no resolvable bitrate is never probed`() = runTest {
        val body = """{"title":"One","audioStreams":[
            {"url":"https://piped.example/audio/251","format":"251","quality":"160kbps","mimeType":"audio/webm","itag":251,"bitrate":160000},
            {"url":"https://piped.example/audio/999","format":"","quality":"","mimeType":"audio/mp4","itag":999,"bitrate":0}]}"""
        val http = FakeHttp().apply {
            onPath("/streams/", body = body)
            onPath("/audio/251", status = 403, body = "")
            onPath("/audio/999", body = "audio")
        }
        assertTrue(sourceApi(http).getStreamsOfAudioSource(basicSource()).isEmpty())
        assertEquals(0, http.countMatching("/audio/999"))
    }

    @Test
    fun `LBRY mirror rows are skipped in favour of itag 18`() = runTest {
        // The shape pipedapi.ducks.party returned on 2026-09-28: LBRY mp4 (401) and HLS rows before itag 18.
        val body = """{"title":"One","audioStreams":[],"videoStreams":[
            {"url":"https://lbry.example/v6/streams/x.mp4","format":"MP4","quality":"LBRY","mimeType":"video/mp4","itag":-1,"bitrate":0,"videoOnly":false},
            {"url":"https://lbry.example/v6/streams/x","format":"HLS","quality":"LBRY HLS","mimeType":"application/x-mpegurl","itag":-1,"bitrate":0,"videoOnly":false},
            {"url":"https://piped.example/video/18","format":"MPEG_4","quality":"360p","mimeType":"video/mp4","itag":18,"bitrate":0,"videoOnly":false}]}"""
        val http = FakeHttp().apply {
            onPath("/streams/", body = body)
            onPath("/v6/streams/", status = 401, body = "")
            onPath("/video/18", body = "mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(listOf("https://piped.example/video/18"), stream.streams.map { it.url })
        assertEquals(0, http.countMatching("lbry.example"))
    }

    @Test
    fun `a 404 is a refusal and drops the row`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", status = 404, body = "")
            onPath("/video/18", body = "mp4")
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        assertEquals(listOf("https://piped.example/video/18"), stream.streams.map { it.url })
    }

    @Test
    fun `a blip on the muxed probe keeps the row instead of losing the source`() = runTest {
        // The worst case: the audio rows are genuinely refused and the muxed probe then hits a blip.
        // A blip is not a refusal, so the muxed row is kept and the track does not lose its source.
        for (status in listOf(500, 429, 503)) {
            val http = FakeHttp().apply {
                onPath("/streams/", body = refusedBody)
                onPath("/audio/", status = 403, body = "")
                onPath("/video/18", status = status, body = "")
            }
            val stream = assertNotNull(
                sourceApi(http).getStreamsOfAudioSource(basicSource()).single(),
                "status $status should not lose the source",
            )
            assertEquals(listOf("https://piped.example/video/18"), stream.streams.map { it.url }, "status $status")
        }
    }

    @Test
    fun `a probe that fails outright keeps the stream`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = refusedBody)
            onPath("/audio/", status = 403, body = "")
            onPath("/video/18", body = "mp4")
            onPathThrow("/audio/251", IllegalStateException("host timeout"))
        }
        val stream = assertNotNull(sourceApi(http).getStreamsOfAudioSource(basicSource()).single())
        // A probe that failed is not a refusal, so the timed-out 251 is kept and the audio path is used.
        assertEquals("https://piped.example/audio/251", stream.streams.first().url)
        assertEquals(0, http.countMatching("/video/"))
    }

    @Test
    fun `streamsForAudio still returns null on videoStreams alone`() = runTest {
        val body = """{"title":"One","videoStreams":[{"url":"https://piped.example/video/18","format":"MPEG_4",
            "quality":"","mimeType":"video/mp4","itag":18,"bitrate":0,"videoOnly":false}]}"""
        val http = FakeHttp().apply { onPath("/streams/", body = body) }
        // audioStreams is what the audio path reads, so its absence is a failed fetch.
        assertNull(client(http).streams("xlBUW0FPxJs"))
    }

    @Test
    fun `the unresolved-source error names the query length, not the text`() = runTest {
        val http = FakeHttp().apply { onPath("/search", status = 429, body = "throttled") }
        val api = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" }))
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

/** The probe skip after a served row, and the play log after resolution. */
class AudioProbeSkipTest {

    private val body = """{"title":"One","audioStreams":[
        {"url":"https://proxy.example/audio/251","format":"251","quality":"160kbps","mimeType":"audio/webm","itag":251,"bitrate":160000}],
        "videoStreams":[]}"""

    private fun source(id: String) = AudioSource.Basic(
        id = id,
        title = "",
        artist = null,
        album = null,
        thumbnails = emptyList(),
        externalUri = null,
        confidence = 1f,
    )

    @Test
    fun `a served video is not probed again within 2 minutes, but is after the window`() = runTest {
        var clock = 0L
        val http = FakeHttp().apply {
            onPath("/streams/", body = body)
            onPath("/audio/", body = "", contentType = "audio/webm")
        }
        val api = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" }), now = { clock })
        api.getStreamsOfAudioSource(source("aaaaaaaaaaa"))
        clock += 119_000L
        api.getStreamsOfAudioSource(source("aaaaaaaaaaa"))
        assertEquals(1, http.countMatching("/audio/"))
        clock += 2_000L
        api.getStreamsOfAudioSource(source("aaaaaaaaaaa"))
        assertEquals(2, http.countMatching("/audio/"))
    }

    @Test
    fun `another video on the same proxy is still probed`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = body)
            onPath("/audio/", body = "", contentType = "audio/webm")
        }
        val api = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" }))
        api.getStreamsOfAudioSource(source("aaaaaaaaaaa"))
        api.getStreamsOfAudioSource(source("bbbbbbbbbbb"))
        assertEquals(2, http.countMatching("/audio/"))
    }

    @Test
    fun `an inconclusive probe is not remembered`() = runTest {
        val http = FakeHttp().apply {
            onPath("/streams/", body = body)
            onPath("/audio/", status = 502, body = "")
        }
        val api = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" }))
        assertEquals(1, api.getStreamsOfAudioSource(source("aaaaaaaaaaa")).size)
        api.getStreamsOfAudioSource(source("aaaaaaaaaaa"))
        assertEquals(2, http.countMatching("/audio/"))
    }

    @Test
    fun `the play is logged after the sources resolve`() = runTest {
        val order = mutableListOf<String>()
        val http = FakeHttp()
        val api = RealPipedAudioAPI(AudioPipedClient(PipedClient(http) { "https://piped.example" })) { order += "log" }
        val track = MetadataTrack(
            id = "aaaaaaaaaaa",
            title = "One",
            durationMs = 1,
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
        val sources = api.getStreamsByTrack(track)
        order += "resolved"
        assertEquals("aaaaaaaaaaa", sources.single().id)
        assertEquals(listOf("log", "resolved"), order)
        assertEquals(0, http.requests.size)
    }
}
