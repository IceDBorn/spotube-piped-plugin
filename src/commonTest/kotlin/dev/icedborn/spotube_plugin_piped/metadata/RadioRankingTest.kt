package dev.icedborn.spotube_plugin_piped.metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The batch picker and the version keys, without any request. */
class RadioRankingTest {

    private fun song(id: String, artist: String, title: String = "Song $id") = MetadataTrack(
        id = id,
        title = title,
        durationMs = 200_000,
        trackNumber = null,
        discNumber = null,
        artists = listOf(MetadataArtist.Basic(id = "UC$artist", name = artist, thumbnails = emptyList(), externalUri = null)),
        album = null,
        thumbnails = emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    /** Songs s0, s1 and on, one per entry of [artists], scored highest first. */
    private fun candidates(vararg artists: String) =
        artists.mapIndexed { index, artist -> Candidate(song("s$index", artist), 1.0 - index * 0.01) }

    private fun List<Candidate>.ids() = map { it.track.id }

    @Test
    fun `an artist gets 2 songs a batch and 3 songs between them`() {
        val open = candidates("a", "a", "a", "b", "c", "d", "b", "e")
        val picks = pickBatch(open, emptyList(), 8, emptyList())
        assertEquals(listOf("s0", "s3", "s4", "s5", "s1", "s6", "s7"), picks.ids())
        // Only the fill takes a third song by a.
        assertEquals(listOf("s0", "s3", "s4", "s5", "s1", "s6", "s7", "s2"), fillBatch(picks, open, 8).ids())
    }

    @Test
    fun `every 4th slot takes the best familiar song`() {
        val familiar = listOf(Candidate(song("f0", "x"), 5.0), Candidate(song("f1", "y"), 4.0))
        val picks = pickBatch(candidates("a", "b", "c", "d", "e", "f"), familiar, 6, emptyList())
        assertEquals(listOf("s0", "s1", "s2", "f0", "s3", "s4"), picks.ids())
    }

    @Test
    fun `a familiar song keeps the artist rules and takes no other slot`() {
        val sameArtist = listOf(Candidate(song("f0", "a"), 5.0))
        assertEquals(listOf("s0", "s1", "s2", "s3"), pickBatch(candidates("a", "b", "c", "d"), sameArtist, 4, emptyList()).ids())
        val short = pickBatch(candidates("a", "a"), listOf(Candidate(song("f0", "b"), 5.0)), 3, emptyList())
        assertEquals(listOf("s0"), short.ids())
        assertEquals(listOf("s0", "s1"), fillBatch(short, candidates("a", "a"), 3).ids())
    }

    @Test
    fun `the gap counts the artists that ended the last batch`() {
        val picks = pickBatch(candidates("a", "b", "c", "d"), emptyList(), 4, listOf("a"))
        assertEquals(listOf("s1", "s2", "s3", "s0"), picks.ids())
    }

    @Test
    fun `version tags come only from brackets and dash suffixes`() {
        assertEquals(emptySet<String>(), versionTags(song("x", "y", "Live Forever")))
        assertEquals(setOf("live"), versionTags(song("x", "y", "Song (Live at Wembley)")))
        assertEquals(setOf("sped up"), versionTags(song("x", "y", "Song - Sped Up")))
        assertEquals(setOf("session"), versionTags(song("x", "y", "Song (Acoustic Session)")))
        assertEquals(emptySet<String>(), versionTags(song("x", "y", "Song (Remastered 2011)")))
    }

    @Test
    fun `a base title drops brackets and dash suffixes`() {
        assertEquals("hotelcalifornia", baseTitle("Hotel California - 2013 Remaster"))
        assertEquals("satisfaction", baseTitle("(I Can't Get No) Satisfaction"))
        assertEquals("intro", baseTitle("[Intro]"))
    }

    @Test
    fun `versions of a song share one key across an artist's channels`() {
        assertEquals(versionKey(song("a", "Queen", "Song")), versionKey(song("b", "Queen - Topic", "Song (Remastered)")))
    }

    @Test
    fun `an upload title drops its artist prefix before the key and the tags`() {
        val first = song("a", "Alpha", "Alpha - Song (Official Video)")
        val second = song("b", "Alpha", "Alpha - Other Song (Official Video)")
        assertNotEquals(versionKey(first), versionKey(second))
        assertEquals("alpha|song", versionKey(first))
        assertEquals(emptySet<String>(), versionTags(song("c", "Oasis", "Oasis - Live Forever (Official Video)")))
        // The cut lands on the original title, whose lowercase can be a different length.
        assertTrue(versionKey(song("d", "İlyas Yalçıntaş", "İlyas Yalçıntaş - Bu Ruh (Official Video)")).endsWith("|buruh"))
    }
}
