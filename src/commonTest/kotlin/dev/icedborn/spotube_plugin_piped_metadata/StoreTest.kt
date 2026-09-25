package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

private const val BLANK_CHANNEL_ID = "UCblankchannel0000000000"

class StoreTest {

    private val track = MetadataTrack(
        id = "vid00000000",
        title = "Alpha Song",
        durationMs = 181_000,
        trackNumber = null,
        discNumber = null,
        artists = emptyList(),
        album = null,
        thumbnails = emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = "https://www.youtube.com/watch?v=vid00000000",
    )

    @Test
    fun `a cached track reads back equal`() = runTest {
        val store = EntityStore(FakeStorage())
        store.rememberTrack(track)
        assertEquals(track, assertNotNull(store.cachedTrack(track.id)))
    }

    @Test
    fun `a track survives a second store over the same storage`() = runTest {
        val storage = FakeStorage()
        EntityStore(storage).rememberTrack(track)
        assertEquals(track, assertNotNull(EntityStore(storage).cachedTrack(track.id)))
    }

    @Test
    fun `a missing track is null`() = runTest {
        assertNull(EntityStore(FakeStorage()).cachedTrack("nope"))
    }

    @Test
    fun `a blank cached channel makes the user API refetch`() = runTest {
        val store = EntityStore(FakeStorage())
        val http = FakeHttp().apply { onPath("/channel/", body = Fixtures.channel) }
        // Seed the blank record a throttled fetch would leave behind.
        store.cacheChannel(BLANK_CHANNEL_ID, PipedChannelInfo(id = BLANK_CHANNEL_ID, name = ""))
        val user = RealMetadataUserAPI(PipedClient(http) { "https://piped.example" }, store).getUser(BLANK_CHANNEL_ID)
        assertNotNull(user)
        assertEquals(1, http.countMatching("/channel/"), "a blank cached name must not be trusted")
    }

    @Test
    fun `a fetched channel reads back with its name`() = runTest {
        val store = EntityStore(FakeStorage())
        val info = PipedChannelInfo(id = "UCchannelalpha01a0000000", name = "Alpha Artist", subscriberCount = 12)
        store.cacheChannel(info.id, info)
        assertEquals(info, assertNotNull(store.cachedChannel(info.id)))
    }

    @Test
    fun `channel freshness is false without a stored timestamp`() = runTest {
        assertTrue(!EntityStore(FakeStorage()).channelFresh("UCchannelalpha01a0000000"))
    }

    @Test
    fun `a channel cached now reads as fresh`() = runTest {
        val store = EntityStore(FakeStorage())
        store.cacheChannel("UCchannelalpha01a0000000", PipedChannelInfo(id = "UCchannelalpha01a0000000", name = "A"))
        assertTrue(store.channelFresh("UCchannelalpha01a0000000"))
    }

    @Test
    fun `an album playlist page round trips`() = runTest {
        val store = EntityStore(FakeStorage())
        val page = json.decodeFromString<PipedPlaylistPage>(Fixtures.playlistAlbumPage1)
        store.cacheAlbumPlaylist("OLAK5uy_test", page)
        assertEquals(page, assertNotNull(store.cachedAlbumPlaylist("OLAK5uy_test")))
    }

    @Test
    fun `a cached page fetched now is inside the verify window`() = runTest {
        val store = EntityStore(FakeStorage())
        val page = json.decodeFromString<PipedPlaylistPage>(Fixtures.playlistAlbumPage1)
        store.cacheAlbumPlaylist("OLAK5uy_test", page)
        assertTrue(store.albumPlaylistFetchedWithin("OLAK5uy_test", 60_000))
        assertTrue(!store.albumPlaylistFetchedWithin("other", 60_000))
    }

    @Test
    fun `streams are cached without the related rows`() = runTest {
        val store = EntityStore(FakeStorage())
        val info = json.decodeFromString<PipedStreamsInfo>(Fixtures.streams)
        store.cacheStreams("s00000000", info)
        val cached = assertNotNull(store.cachedStreams("s00000000"))
        assertEquals(info.title, cached.title)
        // Neither list is read back, so both are dropped on write; the audio URLs also expire.
        assertTrue(cached.relatedStreams.isEmpty())
        assertTrue(cached.audioStreams.isEmpty(), "audioStreams leaked into the cache: ${cached.audioStreams.size}")
    }

    @Test
    fun `a track album verdict round trips, including the none marker`() = runTest {
        val store = EntityStore(FakeStorage())
        assertNull(store.cachedTrackAlbum("vid0"))
        store.cacheTrackAlbum("vid0", "OLAK5uy_album")
        assertEquals("OLAK5uy_album", store.cachedTrackAlbum("vid0"))
        store.cacheTrackAlbum("vid1", "none")
        assertEquals("none", store.cachedTrackAlbum("vid1"))
    }

    @Test
    fun `the account state round trips`() = runTest {
        val store = EntityStore(FakeStorage())
        val state = AccountCacheState(
            playlists = listOf(CachedAccountPlaylist("uuid1", "Spotube - Favorites", 3)),
            savedTracks = listOf("vid00000000"),
            savedAlbums = listOf("OLAK5uy_album"),
            savedArtists = listOf("UCchannelalpha01a0000000"),
            refreshedAt = 1_700_000_000_000,
            instance = "https://piped.example",
            username = "someone",
        )
        store.cacheAccountState(state)
        assertEquals(state, assertNotNull(store.cachedAccountState()))
    }

    @Test
    fun `a primitive value round trips through put and get`() = runTest {
        val store = EntityStore(FakeStorage())
        store.put("k", JsonPrimitive("v"))
        assertEquals(JsonPrimitive("v"), assertNotNull(store.get("k")))
    }

    @Test
    fun `remove drops the key`() = runTest {
        val store = EntityStore(FakeStorage())
        store.put("k", JsonPrimitive("v"))
        store.remove("k")
        assertNull(store.get("k"))
    }
}
