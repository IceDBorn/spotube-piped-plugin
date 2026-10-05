package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure rules of the history log: which rows are new, which are the device's own, and how plays merge. */
class HistoryLogTest {

    private fun track(index: Int, title: String = "Track $index") = MetadataTrack(
        id = vid(index),
        title = title,
        durationMs = 200_000,
        trackNumber = null,
        discNumber = null,
        artists = listOf(MetadataArtist.Basic(id = "UCa", name = "Alpha", thumbnails = emptyList(), externalUri = null)),
        album = null,
        thumbnails = emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    @Test
    fun `rows after the seen ones are new`() {
        assertEquals(listOf("d"), appendedRows(listOf("a", "b", "c"), listOf("a", "b", "c", "d")))
        assertEquals(emptyList(), appendedRows(listOf("a", "b"), listOf("a", "b")))
        assertEquals(listOf("a", "b"), appendedRows(emptyList(), listOf("a", "b")))
    }

    @Test
    fun `rows removed from the head do not hide the new ones`() {
        assertEquals(listOf("d", "e"), appendedRows(listOf("a", "b", "c"), listOf("c", "d", "e")))
        assertEquals(emptyList(), appendedRows(listOf("a", "b", "c"), listOf("b", "c")))
    }

    @Test
    fun `a row removed by hand does not make the rows after it new`() {
        assertEquals(listOf("f"), appendedRows(listOf("a", "b", "c", "d", "e"), listOf("a", "b", "d", "e", "f")))
    }

    @Test
    fun `a log with nothing in common is all new`() {
        assertEquals(listOf("x", "y"), appendedRows(listOf("a", "b"), listOf("x", "y")))
    }

    @Test
    fun `a repeated row is old once per seen row`() {
        assertEquals(listOf("a"), appendedRows(listOf("a", "a"), listOf("a", "a", "a")))
        assertEquals(listOf("a", "b"), appendedRows(listOf("a", "b", "c"), listOf("b", "c", "a", "b")))
    }

    @Test
    fun `own rows are taken out once per upload`() {
        assertEquals(listOf("b", "a"), withoutOwn(listOf("a", "b", "a", "c"), listOf("a", "c")))
        assertEquals(listOf("a"), withoutOwn(listOf("a"), listOf("z")))
    }

    @Test
    fun `new plays of other devices go on top and add to the count`() {
        val local = listOf(PlayedTrack(track(1), 2, 100), PlayedTrack(track(2), 1, 50))
        val merged = mergeRemotePlays(local, listOf(track(2), track(3), track(2)), firstContact = false)
        assertEquals(listOf(vid(2), vid(3), vid(1)), merged.map { it.track.id })
        assertEquals(listOf(3, 1, 2), merged.map { it.plays })
        // A merge is not a play on this device, so the play times the replay window reads stay as they were.
        assertEquals(listOf(50L, 0L, 100L), merged.map { it.lastPlayedAt })
    }

    @Test
    fun `first contact keeps the device history on top and only raises counts`() {
        val local = listOf(PlayedTrack(track(1), 1, 100), PlayedTrack(track(2), 5, 50))
        val log = listOf(track(3), track(1), track(4), track(1), track(2))
        val merged = mergeRemotePlays(local, log, firstContact = true)
        assertEquals(listOf(vid(1), vid(2), vid(4), vid(3)), merged.map { it.track.id })
        assertEquals(listOf(2, 5, 1, 1), merged.map { it.plays })
        assertEquals(listOf(100L, 50L, 0L, 0L), merged.map { it.lastPlayedAt })
    }

    @Test
    fun `a merge keeps the copy of a track the device already has`() {
        val local = listOf(PlayedTrack(track(1, "Device title"), 1, 100))
        val merged = mergeRemotePlays(local, listOf(track(1, "Row title")), firstContact = false)
        assertEquals("Device title", merged.single().track.title)
    }

    @Test
    fun `a merge cuts the history to its limit`() {
        val local = (1..200).map { PlayedTrack(track(it), 1, 1_000L - it) }
        val merged = mergeRemotePlays(local, listOf(track(300), track(301)), firstContact = false)
        assertEquals(200, merged.size)
        assertEquals(listOf(vid(301), vid(300), vid(1)), merged.take(3).map { it.track.id })
        assertEquals(vid(198), merged.last().track.id)
    }
}
