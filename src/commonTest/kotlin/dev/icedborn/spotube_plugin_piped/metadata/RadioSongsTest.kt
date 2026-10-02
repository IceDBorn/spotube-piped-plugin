package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioSongsTest {

    private fun trackApi(piped: FakePiped): RealMetadataTrackAPI {
        val store = EntityStore(FakeStorage())
        val library = LocalLibrary(store)
        val client = PipedClient(piped) { FAKE_INSTANCE }
        val mirror =
            PipedSavedLibrary(piped, store, library, AlbumLookup(client, store), InstanceSource(store, null), noSessionScope) { null }
        return RealMetadataTrackAPI(client, store, library, mirror)
    }

    @Test
    fun `video titles are told apart from song titles`() {
        assertTrue(looksLikeVideo("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official"))
        assertTrue(looksLikeVideo("Bon Jovi - Livin' On A Prayer", "Bon Jovi"))
        assertTrue(looksLikeVideo("Don McLean - American Pie (Lyric Video)", "Don McLean"))
        assertTrue(looksLikeVideo("Aerosmith - Dream On (Audio)", "Aerosmith"))
        assertFalse(looksLikeVideo("Bohemian Rhapsody", "Queen Official"))
        assertFalse(looksLikeVideo("Hotel California - 2013 Remaster", "Eagles"))
        assertFalse(looksLikeVideo("Sweet Child O' Mine", "Guns N' Roses - Topic"))
    }

    @Test
    fun `the radio queue drops rows that are videos`() = runTest {
        val piped = FakePiped(pageSize = 50)
        piped.publicPlaylist("RDAMVM${vid(0)}", "Mix", (0 until 4).map(::vid))
        piped.titles[vid(1)] = "Artist - Song One (Official Music Video)"
        piped.titles[vid(2)] = "Song Two"
        piped.titles[vid(3)] = "Artist - Song Three"
        val queue = trackApi(piped).recommendationsBasedOnTracks(listOf(vid(0)), 5)
        assertEquals(listOf(vid(2)), queue.map { it.id })
    }

    @Test
    fun `a video seed is swapped for its song before the mix is read`() = runTest {
        val piped = FakePiped(pageSize = 50)
        piped.titles[vid(0)] = "Alpha - Song (Official Video)"
        piped.uploaders[vid(9)] = "Alpha - Topic"
        piped.search("filter=music_songs", listOf(piped.row(vid(9), title = "Song")))
        piped.publicPlaylist("RDAMVM${vid(9)}", "Mix", listOf(vid(9), vid(1), vid(2)))
        val api = trackApi(piped)
        api.getTrack(vid(0))
        piped.reset()
        val queue = api.recommendationsBasedOnTracks(listOf(vid(0)), 2)
        assertEquals(listOf(vid(1), vid(2)), queue.map { it.id })
        // One song search and the song's mix; the video's own mix is never read.
        assertEquals(2, piped.total)
        assertEquals(0, piped.count("RDAMVM${vid(0)}"))
        // A second queue from the same video reuses the match.
        api.recommendationsBasedOnTracks(listOf(vid(0)), 2)
        assertEquals(1, piped.count("filter=music_songs"))
    }

    @Test
    fun `a song seed costs no song search`() = runTest {
        val piped = FakePiped(pageSize = 50)
        piped.publicPlaylist("RDAMVM${vid(0)}", "Mix", (0 until 3).map(::vid))
        val api = trackApi(piped)
        api.getTrack(vid(0))
        piped.reset()
        assertEquals(listOf(vid(1), vid(2)), api.recommendationsBasedOnTracks(listOf(vid(0)), 2).map { it.id })
        assertEquals(1, piped.total)
    }
}
