package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ConvertersTest {

    private val testJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val page = decodeSearchPage(Fixtures.searchMusicSongs)

    private fun decodeSearchPage(body: String): PipedSearchPage = testJson.decodeFromString(body)

    @Test
    fun `toTrack reads the id, title, artist id and duration`() {
        val track = assertNotNull(page.items[0].toTrack())
        assertEquals("vid00000000", track.id)
        assertEquals("Alpha Song", track.title)
        assertEquals(181_000L, track.durationMs)
        val artist = assertNotNull(track.artists.firstOrNull())
        assertEquals("UCchannelalpha01a0000000", artist.id)
        // The synthetic "- Topic" suffix is host-visible noise, so it never reaches the artist name.
        assertEquals("Alpha Artist", artist.name)
        assertEquals("https://www.youtube.com/watch?v=vid00000000", track.externalUri)
    }

    @Test
    fun `toTrack falls back to name when the title is blank`() {
        val track = assertNotNull(page.items[0].copy(title = "").toTrack())
        assertEquals("Alpha Song", track.title)
    }

    @Test
    fun `toTrack drops a row with no usable video id`() {
        assertNull(page.items[0].copy(url = "https://piped.example/live/xyz").toTrack())
    }

    @Test
    fun `toTrack keeps a real track number when given one`() {
        assertEquals(4, assertNotNull(page.items[0].toTrack(trackNumber = 4)).trackNumber)
    }

    @Test
    fun `toTrack without a duration reports zero`() {
        assertEquals(0L, assertNotNull(page.items[0].copy(duration = 0).toTrack()).durationMs)
    }

    @Test
    fun `cleanArtistName strips the topic suffix`() {
        assertEquals("Alpha Artist", cleanArtistName("Alpha Artist - Topic"))
        assertEquals("Alpha Artist", cleanArtistName("Alpha Artist"))
        // Only the suffix goes: a name that merely contains the word keeps it.
        assertEquals("Topic Radio", cleanArtistName("Topic Radio"))
    }

    @Test
    fun `channelIdOf parses a channel url`() {
        assertEquals(
            "UCchannelalpha01a0000000",
            channelIdOf("https://www.youtube.com/channel/UCchannelalpha01a0000000"),
        )
        assertEquals("UCchannelalpha01a0000000", channelIdOf("https://www.youtube.com/channel/UCchannelalpha01a0000000/videos"))
    }

    @Test
    fun `channelIdOf returns empty for junk`() {
        assertEquals("", channelIdOf("https://www.youtube.com/@handle"))
        assertEquals("", channelIdOf("https://www.youtube.com/channel/short"))
        assertEquals("", channelIdOf(""))
    }

    @Test
    fun `playlistIdOf parses a playlist url`() {
        assertEquals("OLAK5uy_abcdefg", playlistIdOf("https://www.youtube.com/playlist?list=OLAK5uy_abcdefg"))
        assertEquals("OLAK5uy_abcdefg", playlistIdOf("https://www.youtube.com/watch?v=x&list=OLAK5uy_abcdefg&index=2"))
    }

    @Test
    fun `playlistIdOf returns empty for junk`() {
        assertEquals("", playlistIdOf("https://www.youtube.com/playlist"))
        assertEquals("", playlistIdOf("https://www.youtube.com/playlist?list="))
    }

    @Test
    fun `videoIdOf parses a watch url`() {
        assertEquals("vid00000000", videoIdOf("https://www.youtube.com/watch?v=vid00000000&t=30"))
        assertEquals("", videoIdOf("https://piped.example/live/abc"))
    }

    @Test
    fun `an album row converts to a basic album`() {
        val album = assertNotNull(page.items[3].toAlbumBasic())
        assertEquals("OLAK5uy_abcdefg", album.id)
        // The row is named "Album - Single", so the prefix rule reads the type as Album and keeps "Single".
        assertEquals("Single", album.title)
        assertEquals(MetadataAlbumType.Album, album.albumType)
    }

    @Test
    fun `an artist url converts to a basic artist`() {
        val artist = assertNotNull(page.items[0].copy(url = "https://www.youtube.com/channel/UCchannelalpha01a0000000").toArtist())
        assertEquals("UCchannelalpha01a0000000", artist.id)
    }

    @Test
    fun `splitAlbumTitle strips the type prefix`() {
        assertEquals(Pair("Blue", MetadataAlbumType.Album), splitAlbumTitle("Album \u2013 Blue"))
        assertEquals(Pair("Blue", MetadataAlbumType.Single), splitAlbumTitle("Single - Blue"))
        assertEquals(Pair("Blue", MetadataAlbumType.Album), splitAlbumTitle("Blue"))
    }

    @Test
    fun `thumbnailDimsOf reads piped size segments`() {
        assertEquals(300 to 300, thumbnailDimsOf("https://piped.example/t.jpg=s300"))
        assertEquals(600 to 400, thumbnailDimsOf("https://piped.example/t.jpg=w600-h400"))
        assertEquals(0 to 0, thumbnailDimsOf("https://piped.example/t.jpg"))
    }
}
