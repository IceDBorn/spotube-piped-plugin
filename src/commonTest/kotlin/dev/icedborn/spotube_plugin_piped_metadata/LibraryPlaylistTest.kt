package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The generated Playlists-tab entry, its setting, and the guards on writing to a generated id. */
class LibraryPlaylistTest {

    private class Harness(val http: FakeHttp = FakeHttp(), val storage: FakeStorage = FakeStorage()) {
        val store = EntityStore(storage)
        val library = LocalLibrary(store)
        val instance = InstanceSource(store, null)
        val client = PipedClient(http) { "https://piped.example" }
        val mirror = PipedSavedLibrary(http, store, library, AlbumLookup(client, store), instance) { null }
        val history = PlayHistory(store)
        val setting = LibraryPlaylistSetting(store)
        val api = RealMetadataPlaylistAPI(client, store, library, mirror, history, setting)

        suspend fun setMode(mode: LibraryPlaylist) = setting.set(mode)
    }

    private fun videoId(index: Int): String = "vid" + index.toString().padStart(7, '0') + "0"

    /** A /streams body with a cover, so a resolved track paints a card. */
    private fun streamsBody(title: String) = """
        {"title":"$title","duration":200,"thumbnailUrl":"https://piped.example/cover.jpg",
         "uploader":"Alpha Artist","uploaderUrl":"https://www.youtube.com/channel/UCchannelalpha01a0000000",
         "relatedStreams":[]}
    """.trimIndent()

    /** A full track with a cover, as the audio API hands the history. */
    private fun historyTrack(index: Int, cover: Boolean = true) = MetadataTrack(
        id = videoId(index),
        title = "Track $index",
        durationMs = 200_000,
        trackNumber = null,
        discNumber = null,
        artists = listOf(
            MetadataArtist.Basic(id = "UCa", name = "Alpha", thumbnails = emptyList(), externalUri = null),
        ),
        album = null,
        thumbnails = if (cover) listOf(Thumbnail("https://piped.example/c$index.jpg", 300, 300)) else emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    /** Records [count] distinct tracks, oldest first, so [history] lists them newest first. */
    private suspend fun Harness.recordHistory(count: Int, cover: Boolean = true) {
        for (i in 0 until count) history.record(historyTrack(i, cover))
    }

    @Test
    fun `ALWAYS puts the item first on page 0 only and keeps the real page offsets`() = runTest {
        val h = Harness()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-a", name = "A", trackIds = listOf(videoId(0))))
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-b", name = "B", trackIds = listOf(videoId(1))))
        h.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))
        h.setMode(LibraryPlaylist.ALWAYS)
        h.recordHistory(3)

        val first = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10))
        assertEquals(RECENTLY_PLAYED_ID, first.items.first().id)
        assertEquals(3, first.items.size)
        // totalCount stays the real list size, so page 1 offsets do not shift.
        assertEquals(2, first.totalCount)

        val second = h.api.savedPlaylists(PaginationStrategy.Offset(10, 10))
        assertTrue(second.items.none { it.id == RECENTLY_PLAYED_ID })
    }

    @Test
    fun `WHEN_EMPTY shows the item only without playlists`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.WHEN_EMPTY)
        h.library.saveTracks(listOf(videoId(0)))
        h.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))

        val empty = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10))
        assertEquals(listOf(LIKED_SONGS_ID), empty.items.map { it.id })

        h.library.upsertPlaylist(StoredPlaylist(id = "piped-a", name = "A", trackIds = listOf(videoId(1))))
        val withOne = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10))
        assertTrue(withOne.items.none { isSynthetic(it.id) })
    }

    @Test
    fun `OFF never shows the item`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.OFF)
        h.library.saveTracks(listOf(videoId(0)))
        h.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))
        assertTrue(h.api.savedPlaylists(PaginationStrategy.Offset(0, 10)).items.isEmpty())
    }

    @Test
    fun `no history with saved tracks falls back to liked songs with a cover`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.ALWAYS)
        h.library.saveTracks(listOf(videoId(0)))
        h.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))

        val item = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10)).items.single()
        assertEquals(LIKED_SONGS_ID, item.id)
        assertEquals("Liked Songs", item.title)
        assertEquals(1, item.thumbnails.size)
    }

    @Test
    fun `both empty returns nothing`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.ALWAYS)
        val page = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10))
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.totalCount)
    }

    @Test
    fun `the cover comes from the newest track that has one`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.ALWAYS)
        // The newest play has no cover, so the item falls back to the older one that has.
        h.history.record(historyTrack(0, cover = false))
        h.history.record(historyTrack(1))

        val item = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10)).items.single()
        assertEquals(RECENTLY_PLAYED_ID, item.id)
        assertEquals(listOf("https://piped.example/c1.jpg"), item.thumbnails.map { it.url })
    }

    @Test
    fun `no cover at all gives an empty list instead of a crash`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.ALWAYS)
        h.recordHistory(1, cover = false)
        assertTrue(h.api.savedPlaylists(PaginationStrategy.Offset(0, 10)).items.single().thumbnails.isEmpty())
    }

    @Test
    fun `the recent item pages the history`() = runTest {
        val h = Harness()
        h.recordHistory(3)
        val page = h.api.getPlaylistTracks(RECENTLY_PLAYED_ID, PaginationStrategy.Offset(0, 2))
        assertEquals(2, page.items.size)
        assertEquals(3, page.totalCount)
        assertTrue(page.nextPagination != null)
    }

    @Test
    fun `a generated id with an empty source resolves without a request`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.OFF)
        // No history and no saved tracks, so neither entry has anything to serve.
        assertEquals(RECENTLY_PLAYED_ID, h.api.getPlaylist(RECENTLY_PLAYED_ID).id)
        assertEquals(LIKED_SONGS_ID, h.api.getPlaylist(LIKED_SONGS_ID).id)
        assertTrue(h.http.requests.isEmpty())
    }

    @Test
    fun `a generated id stored as an old bookmark is not listed`() = runTest {
        val h = Harness()
        h.library.savePlaylists(listOf(LIKED_SONGS_ID, "piped-real"))
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-real", name = "Real", trackIds = listOf(videoId(0))))
        h.setMode(LibraryPlaylist.OFF)
        val page = h.api.savedPlaylists(PaginationStrategy.Offset(0, 10))
        assertEquals(listOf("piped-real"), page.items.map { it.id })
        assertEquals(1, page.totalCount)
    }

    @Test
    fun `both synthetic ids resolve with the setting OFF`() = runTest {
        val h = Harness()
        h.setMode(LibraryPlaylist.OFF)
        h.library.saveTracks(listOf(videoId(0)))
        h.http.on({ it.contains("/streams/") }, body = streamsBody("Track"))
        h.recordHistory(1)

        assertEquals(RECENTLY_PLAYED_ID, h.api.getPlaylist(RECENTLY_PLAYED_ID).id)
        assertEquals(LIKED_SONGS_ID, h.api.getPlaylist(LIKED_SONGS_ID).id)
    }

    @Test
    fun `writes on a synthetic id make no request and store nothing`() = runTest {
        val h = Harness()
        h.library.saveTracks(listOf(videoId(0)))
        h.recordHistory(1)
        val before = h.http.requests.size

        h.api.deletePlaylist(RECENTLY_PLAYED_ID)
        h.api.deletePlaylist(LIKED_SONGS_ID)
        h.api.updatePlaylist(LIKED_SONGS_ID, "Renamed", null, null, null, null, null)
        h.api.savePlaylists(listOf(RECENTLY_PLAYED_ID, LIKED_SONGS_ID))
        assertEquals(before, h.http.requests.size)
        assertTrue(h.library.savedPlaylists().isEmpty())
    }

    @Test
    fun `adding tracks to a synthetic id names the entry`() = runTest {
        val h = Harness()
        val error = assertFailsWith<IllegalStateException> {
            h.api.addTracksToPlaylist(RECENTLY_PLAYED_ID, listOf(videoId(0)))
        }
        assertTrue(error.message.orEmpty().contains("Recently played"))
        assertTrue(h.http.requests.isEmpty())
    }

    @Test
    fun `an unknown stored setting reads as ALWAYS`() = runTest {
        val h = Harness()
        h.storage.putString("piped.library.placeholder", "NONSENSE")
        assertEquals(LibraryPlaylist.ALWAYS, h.setting.stored())
    }
}
