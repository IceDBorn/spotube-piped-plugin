package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAuthClient
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Auth client, instance settings, the radio queue and play-history ranking. */
class SmallApisTest {

    @Test
    fun `login returns the token`() = runTest {
        val piped = FakePiped().apply { users["ice"] = "pw" }
        assertEquals("token-ice", PipedAuthClient(piped).login(FAKE_INSTANCE, "ice", "pw"))
    }

    @Test
    fun `a rejected login throws`() = runTest {
        assertFailsWith<IllegalStateException> { PipedAuthClient(FakePiped()).login(FAKE_INSTANCE, "ice", "pw") }
    }

    @Test
    fun `a 2xx without a token throws`() = runTest {
        val http = FakeHttp().apply { onPath("/login", body = "{}") }
        assertFailsWith<IllegalStateException> { PipedAuthClient(http).login(FAKE_INSTANCE, "ice", "pw") }
    }

    @Test
    fun `register returns the token`() = runTest {
        assertEquals("token-new", PipedAuthClient(FakePiped()).register(FAKE_INSTANCE, "new", "pw"))
    }

    @Test
    fun `the instance is stored without a trailing slash and survives a reload`() = runTest {
        val store = EntityStore(FakeStorage())
        InstanceSource(store, null).setApi(" https://a.example/ ")
        assertEquals("https://a.example", InstanceSource(store, null).api())
    }

    @Test
    fun `without a stored instance the session instance is used`() = runTest {
        val source = InstanceSource(EntityStore(FakeStorage()), "https://s.example/")
        assertEquals("https://s.example", source.api())
        assertNull(InstanceSource(EntityStore(FakeStorage()), null).api())
    }

    @Test
    fun `a blank playback instance clears it`() = runTest {
        val store = EntityStore(FakeStorage())
        val source = InstanceSource(store, null)
        source.setPlayback("https://p.example/")
        assertEquals("https://p.example", InstanceSource(store, null).playback())
        source.setPlayback("  ")
        assertNull(InstanceSource(store, null).playback())
    }

    private fun trackApi(piped: FakePiped): RealMetadataTrackAPI {
        val store = EntityStore(FakeStorage())
        val library = LocalLibrary(store)
        val client = PipedClient(piped) { FAKE_INSTANCE }
        val mirror =
            PipedSavedLibrary(piped, store, library, AlbumLookup(client, store), InstanceSource(store, null), noSessionScope) { null }
        return RealMetadataTrackAPI(client, store, library, mirror)
    }

    @Test
    fun `the radio queue skips the seed and stops at the limit`() = runTest {
        val piped = FakePiped(pageSize = 50)
        piped.publicPlaylist("RDAMVM${vid(0)}", "Mix", (0 until 6).map(::vid))
        val queue = trackApi(piped).recommendationsBasedOnTracks(listOf(vid(0)), 3)
        assertEquals(listOf(vid(1), vid(2), vid(3)), queue.map { it.id })
        assertEquals(1, piped.total)
    }

    @Test
    fun `a thin radio is topped up from the seed artist's songs`() = runTest {
        val piped = FakePiped(pageSize = 50)
        piped.publicPlaylist("RDAMVM${vid(0)}", "Mix", listOf(vid(0), vid(1)))
        piped.uploaders[vid(0)] = "Alpha Artist - Topic"
        piped.search("q=Alpha%20Artist", listOf(piped.row(vid(1)), piped.row(vid(7))))
        val queue = trackApi(piped).recommendationsBasedOnTracks(listOf(vid(0)), 5)
        assertEquals(listOf(vid(1), vid(7)), queue.map { it.id })
        // The mix, the seed's /streams and one catalog search.
        assertEquals(3, piped.total)
    }

    @Test
    fun `no seeds give no queue and no request`() = runTest {
        val piped = FakePiped()
        assertTrue(trackApi(piped).recommendationsBasedOnTracks(emptyList(), 5).isEmpty())
        assertEquals(0, piped.total)
    }

    @Test
    fun `search all skips plain videos when the songs page is full`() = runTest {
        val piped = FakePiped()
        piped.search("filter=music_songs", (0 until 20).map { piped.row(vid(it)) })
        RealMetadataSearchAPI(PipedClient(piped) { FAKE_INSTANCE }, EntityStore(FakeStorage())).search("alpha")
        assertEquals(0, piped.count("filter=videos"))
        assertEquals(4, piped.total)
    }

    @Test
    fun `search all asks for plain videos when the songs page is short`() = runTest {
        val piped = FakePiped()
        piped.search("filter=music_songs", listOf(piped.row(vid(1))))
        RealMetadataSearchAPI(PipedClient(piped) { FAKE_INSTANCE }, EntityStore(FakeStorage())).search("alpha")
        assertEquals(1, piped.count("filter=videos"))
    }

    private fun played(index: Int, artistId: String) = MetadataTrack(
        id = vid(index),
        title = "T$index",
        durationMs = 1,
        trackNumber = null,
        discNumber = null,
        artists = listOf(MetadataArtist.Basic(id = artistId, name = artistId, thumbnails = emptyList(), externalUri = null)),
        album = null,
        thumbnails = emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    @Test
    fun `top artists rank by plays across tracks, with Topic ids merged`() = runTest {
        val history = PlayHistory(EntityStore(FakeStorage()))
        history.record(played(1, "UCb"))
        history.record(played(2, "channel:Alpha - Topic"))
        history.record(played(3, "channel:Alpha"))
        history.record(played(4, "UCb"))
        history.record(played(5, "UCb"))
        assertEquals(listOf("UCb", "channel:Alpha"), history.topArtists(5).map { canonicalArtistId(it.id) })
        assertEquals(1, history.topArtists(1).size)
    }

    @Test
    fun `a replay inside a minute is one play`() = runTest {
        val history = PlayHistory(EntityStore(FakeStorage()))
        history.record(played(1, "UCb"))
        history.record(played(1, "UCb"))
        assertEquals(1, history.all().single().plays)
    }
}
