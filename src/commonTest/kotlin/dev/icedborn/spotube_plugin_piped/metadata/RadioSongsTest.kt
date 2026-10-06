package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioSongsTest {

    private fun trackApi(piped: FakePiped, store: EntityStore = EntityStore(FakeStorage())): RealMetadataTrackAPI {
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
        assertTrue(looksLikeVideo("The Band - Song", "TheBandTV"))
        assertFalse(looksLikeVideo("U - Turn", "Queen"))
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
        val queue = trackApi(piped).recommendationsBasedOnTracks(listOf(vid(0)), 3)
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

    private fun mixRow(videoId: String, uploader: String, channel: String) = buildJsonObject {
        put("type", "stream")
        put("url", "/watch?v=$videoId")
        put("title", "Song $videoId")
        put("uploaderName", uploader)
        put("uploaderUrl", "/channel/$channel")
        put("duration", 200)
    }

    private fun song(videoId: String, artist: String, channel: String): MetadataTrack {
        val row = mixRow(videoId, artist, channel)
        return Json.decodeFromJsonElement(PipedSearchItem.serializer(), row).toTrack()!!
    }

    @Test
    fun `mix rows take the YouTube Music artist of a cached song`() = runTest {
        val fan = "UCbandfanchannel00000000"
        val label = "UClabelchannel0000000000"
        val topic = "UCbandtopicchannel000000"
        val piped = FakePiped(pageSize = 50)
        val mix = buildJsonObject {
            put("name", "Mix")
            put("nextpage", null as String?)
            put(
                "relatedStreams",
                JsonArray(
                    listOf(
                        mixRow(vid(0), "TheBandTV", fan),
                        mixRow(vid(1), "TheBandTV", fan),
                        mixRow(vid(2), "Some Label", label),
                        mixRow(vid(3), "Some Label", label),
                        mixRow(vid(4), "Some Label", label).let { JsonObject(it + ("title" to JsonPrimitive("The Band - Song"))) },
                    ),
                ),
            )
        }
        piped.override("RDAMVM", body = mix.toString())
        val store = EntityStore(FakeStorage())
        // The seed came from a music_songs search, and the label's first song from an album.
        store.rememberTrack(song(vid(0), "The Band", topic))
        store.rememberTrack(song(vid(2), "Other Artist", "UCotherartist00000000000"))
        val queue = trackApi(piped, store).recommendationsBasedOnTracks(listOf(vid(0)), 3)
        val artists = queue.associate { it.id to it.artists.single() }
        assertEquals("The Band" to topic, artists.getValue(vid(1)).let { it.name to it.id })
        assertEquals("Other Artist", artists.getValue(vid(2)).name)
        // A label uploads many artists, so its channel is not mapped to the one it was seen with.
        assertEquals("Some Label", artists.getValue(vid(3)).name)
        // The label's "The Band - Song" upload is dropped, since the mix lists The Band's songs.
        assertFalse(vid(4) in artists)
        assertEquals("The Band", store.cachedTrack(vid(1))?.artists?.single()?.name)
        assertEquals(1, piped.total)
    }
}
