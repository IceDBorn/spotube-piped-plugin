package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The failed-fetch rules every cache writer depends on: only a keyed JSON object is authoritative. */
class PipedClientTest {

    private fun client(http: FakeHttp) = PipedClient(http) { "https://piped.example" }

    @Test
    fun `search decodes a keyed body`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = Fixtures.searchMusicSongs) }
        val page = assertNotNull(client(http).search("alpha", PipedSearchFilter.MUSIC_SONGS))
        assertEquals(4, page.items.size)
        assertEquals("TOKEN2", page.nextpage)
        assertTrue(http.requests.single().contains("filter=music_songs"))
    }

    @Test
    fun `search returns null for a blank body`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = "") }
        assertNull(client(http).search("alpha", PipedSearchFilter.MUSIC_SONGS))
    }

    @Test
    fun `search returns null for an empty object`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = "{}") }
        assertNull(client(http).search("alpha", PipedSearchFilter.MUSIC_SONGS))
    }

    @Test
    fun `search returns null for an error body`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = """{"error":"throttled"}""") }
        assertNull(client(http).search("alpha", PipedSearchFilter.MUSIC_SONGS))
    }

    @Test
    fun `search returns an empty page for a keyed empty array`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = """{"items":[],"nextpage":null}""") }
        val page = assertNotNull(client(http).search("nothing", PipedSearchFilter.MUSIC_SONGS))
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `search throws on a non-2xx status`() = runTest {
        val http = FakeHttp().apply { onPath("/search", status = 429, body = "slow down") }
        assertFailsWith<IllegalStateException> { client(http).search("alpha", PipedSearchFilter.MUSIC_SONGS) }
    }

    @Test
    fun `search percent-encodes the query`() = runTest {
        val http = FakeHttp().apply { onPath("/search", body = Fixtures.searchMusicSongs) }
        client(http).search("\uD83C\uDFB5 jazz & \u03AC", PipedSearchFilter.MUSIC_SONGS)
        val url = http.requests.single()
        assertTrue(url.contains("q=%F0%9F%8E%B5%20jazz%20%26%20%CE%AC"), url)
    }

    @Test
    fun `streams decodes a keyed body`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", body = Fixtures.streams) }
        val info = assertNotNull(client(http).streamsForMetadata("s00000000"))
        assertEquals("Alpha Song", info.title)
        assertEquals(1, info.relatedStreams.size)
    }

    @Test
    fun `streams returns null without a relatedStreams array`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", body = """{"audioStreams":[]}""") }
        assertNull(client(http).streamsForMetadata("s00000000"))
    }

    @Test
    fun `streams returns an empty page for a keyed empty array`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", body = """{"relatedStreams":[]}""") }
        assertNotNull(client(http).streamsForMetadata("s00000000"))
    }

    @Test
    fun `streams throws on a non-2xx status`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", status = 503, body = "") }
        assertFailsWith<IllegalStateException> { client(http).streamsForMetadata("s00000000") }
    }

    @Test
    fun `streamsForAudio gates on audioStreams, not relatedStreams`() = runTest {
        // Audio reads audioStreams, so a stripped related list must still decode (round-108).
        val http = FakeHttp().apply { onPath("/streams/", body = Fixtures.streams) }
        val info = assertNotNull(client(http).streamsForAudio("s00000000"))
        assertEquals(1, info.audioStreams.size)
    }

    @Test
    fun `streamsForAudio returns null without an audioStreams array`() = runTest {
        val http = FakeHttp().apply { onPath("/streams/", body = """{"relatedStreams":[]}""") }
        assertNull(client(http).streamsForAudio("s00000000"))
    }

    @Test
    fun `playlist decodes a keyed body`() = runTest {
        val http = FakeHttp().apply { onPath("/playlists/", body = Fixtures.playlistAlbumPage1) }
        val page = assertNotNull(client(http).playlist("OLAK5uy_test"))
        assertEquals("Album - Single", page.name)
        assertEquals(2, page.relatedStreams.size)
    }

    @Test
    fun `playlist returns null for a blank body`() = runTest {
        val http = FakeHttp().apply { onPath("/playlists/", body = "  ") }
        assertNull(client(http).playlist("OLAK5uy_test"))
    }

    @Test
    fun `playlist returns null for an error body`() = runTest {
        val http = FakeHttp().apply { onPath("/playlists/", body = """{"error":"nope"}""") }
        assertNull(client(http).playlist("OLAK5uy_test"))
    }

    @Test
    fun `playlist returns an empty page for a keyed empty array`() = runTest {
        val http = FakeHttp().apply { onPath("/playlists/", body = """{"relatedStreams":[],"nextpage":null}""") }
        val page = assertNotNull(client(http).playlist("OLAK5uy_empty"))
        assertTrue(page.relatedStreams.isEmpty())
    }

    @Test
    fun `playlist throws on a non-2xx status`() = runTest {
        val http = FakeHttp().apply { onPath("/playlists/", status = 500, body = "") }
        assertFailsWith<IllegalStateException> { client(http).playlist("OLAK5uy_test") }
    }

    @Test
    fun `playlist next page decodes a keyed body`() = runTest {
        val http = FakeHttp().apply { onPath("/nextpage/playlists/", body = Fixtures.playlistAlbumPage2) }
        val page = assertNotNull(client(http).playlistNextPage("OLAK5uy_test", "PT2"))
        assertNull(page.nextpage)
        assertEquals(1, page.relatedStreams.size)
    }

    @Test
    fun `playlist next page returns null without the key`() = runTest {
        val http = FakeHttp().apply { onPath("/nextpage/playlists/", body = "{}") }
        assertNull(client(http).playlistNextPage("OLAK5uy_test", "PT2"))
    }

    @Test
    fun `channel decodes a keyed body`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", body = Fixtures.channel) }
        val info = assertNotNull(client(http).channel("UCchannelalpha01a0000000"))
        assertEquals("Alpha Artist", info.name)
        assertEquals(2, info.relatedStreams.size)
    }

    @Test
    fun `channel returns null for a blank body`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", body = "") }
        assertNull(client(http).channel("UCchannelalpha01a0000000"))
    }

    @Test
    fun `channel returns null for an error body`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", body = """{"error":"throttled"}""") }
        assertNull(client(http).channel("UCchannelalpha01a0000000"))
    }

    @Test
    fun `channel returns an empty page for a keyed empty array`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", body = """{"relatedStreams":[],"nextpage":null}""") }
        assertNotNull(client(http).channel("UCchannelalpha01a0000000"))
    }

    @Test
    fun `channel throws on a non-2xx status`() = runTest {
        val http = FakeHttp().apply { onPath("/channel/", status = 404, body = "") }
        assertFailsWith<IllegalStateException> { client(http).channel("UCchannelalpha01a0000000") }
    }

    @Test
    fun `an unrouted request is a non-2xx throw`() = runTest {
        assertFailsWith<IllegalStateException> { client(FakeHttp()).search("x", PipedSearchFilter.ALL) }
    }

    @Test
    fun `a non-2xx error message carries no query string`() = runTest {
        val http = FakeHttp().apply { onPath("/search", status = 429, body = "slow down") }
        val error = assertFailsWith<IllegalStateException> {
            client(http).search("secret song \uD83C\uDFB5", PipedSearchFilter.MUSIC_SONGS)
        }
        // The message is logged verbatim by every orNull/catch upstream.
        assertFalse(error.message!!.contains("secret"), error.message!!)
        assertFalse(error.message!!.contains("%"), error.message!!)
        assertTrue(error.message!!.contains("/search"), error.message!!)
    }

    @Test
    fun `pathWithoutQuery drops everything after the question mark`() {
        assertEquals("/search", "/search?q=a%20b&filter=all".pathWithoutQuery())
        assertEquals("/streams/abc", "/streams/abc".pathWithoutQuery())
        assertEquals("/", "/?q=x".pathWithoutQuery())
    }
}
