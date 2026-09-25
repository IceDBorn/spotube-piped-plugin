package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A one-artist track, so ranking tests can say who plays how often. */
internal fun radioTrack(artistId: String, name: String = artistId): MetadataTrack = MetadataTrack(
    id = "t-$artistId-$name",
    title = "Song $name",
    durationMs = 200_000,
    trackNumber = null,
    discNumber = null,
    artists = listOf(
        MetadataArtist.Basic(id = artistId, name = name, thumbnails = emptyList(), externalUri = null),
    ),
    album = null,
    thumbnails = emptyList(),
    explicit = null,
    popularity = null,
    isrcCode = null,
    externalUri = null,
)

/** Radio mixes are the only related-artists source, so their artists are ranked by radio frequency. */
class RelatedArtistsTest {

    @Test
    fun `ranks artists by how often they appear`() {
        val radio = listOf(
            radioTrack("UCb"), radioTrack("UCc"), radioTrack("UCb"),
            radioTrack("UCd"), radioTrack("UCd"), radioTrack("UCb"),
        )
        assertEquals(listOf("UCb", "UCd", "UCc"), rankRadioArtists(radio, emptySet()).map { it.id })
    }

    @Test
    fun `ties keep the radio order`() {
        val radio = listOf(radioTrack("UCz"), radioTrack("UCy"), radioTrack("UCx"))
        assertEquals(listOf("UCz", "UCy", "UCx"), rankRadioArtists(radio, emptySet()).map { it.id })
    }

    @Test
    fun `drops the excluded artist`() {
        val radio = listOf(radioTrack("UCa"), radioTrack("UCb"), radioTrack("UCa"))
        assertEquals(listOf("UCb"), rankRadioArtists(radio, setOf("UCa")).map { it.id })
    }

    @Test
    fun `a channel id is dropped`() {
        val radio = listOf(radioTrack("channel:Someone"), radioTrack("UCb"))
        assertEquals(listOf("UCb"), rankRadioArtists(radio, emptySet()).map { it.id })
    }

    @Test
    fun `caps the result at the limit`() {
        val radio = (1..10).map { radioTrack("UCx$it") }
        assertEquals(3, rankRadioArtists(radio, emptySet(), limit = 3).size)
    }

    @Test
    fun `keeps the first row of a repeated artist`() {
        val ranked = rankRadioArtists(listOf(radioTrack("UCb", "First"), radioTrack("UCb", "Second")), emptySet())
        assertEquals("First", ranked.single().name)
    }

    @Test
    fun `an empty radio gives no artists`() {
        assertTrue(rankRadioArtists(emptyList(), emptySet()).isEmpty())
    }
}
