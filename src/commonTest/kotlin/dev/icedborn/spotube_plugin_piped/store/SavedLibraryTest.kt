package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataPlaylistAPI
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Account refresh, write-through saves and removals, and the account row cache. */
class SavedLibraryTest {

    private val artistId = "UCartistbeta000000000000"

    @Test
    fun `a refresh caches account playlists and rebuilds the saved sets from the mirrors`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val mixA = h.piped.accountPlaylist("Mix A", listOf(vid(1), vid(2), vid(3)))
        val mixB = h.piped.accountPlaylist("Mix B", listOf(vid(4)))
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10), vid(11), vid(12)))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, emptyList())
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, emptyList())

        h.mirror.refreshCache()

        val state = assertNotNull(h.mirror.cachedState())
        assertEquals(listOf(mixA.id to 3, mixB.id to 1), state.playlists.map { it.id to it.trackCount })
        assertEquals(listOf(vid(10), vid(11), vid(12)), state.savedTracks)
        assertTrue(state.savedAlbums.isEmpty())
        assertTrue(state.savedArtists.isEmpty())
        assertEquals(listOf(true, false), h.mirror.isSavedTracks(listOf(vid(11), vid(1))))
        val rows = assertNotNull(h.store.getDecoded(PLAYLIST_ROWS_PREFIX + mixA.id, CachedRows.serializer()))
        assertTrue(rows.complete)
        assertEquals(listOf(vid(1), vid(2), vid(3)), rows.tracks.map { it.id })
        // Listing, Mix A in two pages, Mix B, favorites in two pages, the two empty mirrors.
        assertEquals(8, h.piped.total)
    }

    @Test
    fun `album resolution runs after the refresh returns`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.publicPlaylist("OLAK5uy_A1", "Album - One", listOf(vid(1), vid(2)))
        h.piped.uploaders[vid(1)] = "Alpha Artist"
        h.piped.search("filter=music_albums", listOf(h.piped.albumRow("OLAK5uy_A1", "Album - One", "Alpha Artist")))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1)))

        h.mirror.refreshCache()
        assertTrue(assertNotNull(h.mirror.cachedState()).savedAlbums.isEmpty())

        testScheduler.runCurrent()
        assertEquals(listOf("OLAK5uy_A1"), assertNotNull(h.mirror.cachedState()).savedAlbums)
    }

    @Test
    fun `a mirror longer than one refresh walk continues on the next refresh`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val favorites = (0 until 45).map(::vid)
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, favorites)

        h.mirror.refreshCache()
        // Twenty pages of two rows: the walk stops at row 40 and keeps the previous (empty) set.
        assertTrue(assertNotNull(h.mirror.cachedState()).savedTracks.isEmpty())
        h.piped.reset()

        h.mirror.refreshCache()
        assertEquals(favorites, assertNotNull(h.mirror.cachedState()).savedTracks)
        // The listing and the three remaining pages.
        assertEquals(4, h.piped.total)
    }

    @Test
    fun `a refresh without a session makes no request`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.mirror.refreshCache()
        assertEquals(0, h.piped.total)
    }

    @Test
    fun `saving a track appends it to the favorites mirror`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10), vid(11), vid(12)))
        h.mirror.refreshCache()
        h.piped.reset()

        h.mirror.save(SavedKind.TRACK, listOf(vid(20)))

        assertEquals(listOf(vid(10), vid(11), vid(12), vid(20)), h.mirrorOf(SavedKind.TRACK).videos)
        assertEquals(listOf(true), h.mirror.isSavedTracks(listOf(vid(20))))
        // The listing and the add: the refresh left an index of the mirror.
        assertEquals(2, h.piped.total)
        assertEquals(1, h.piped.count("/user/playlists/add", HttpMethod.Post))
    }

    @Test
    fun `a mirror edited on the web is walked instead of trusting the index`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val favorites = h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10), vid(11), vid(12)))
        h.mirror.refreshCache()
        favorites.videos += vid(13)
        h.piped.reset()

        h.mirror.save(SavedKind.TRACK, listOf(vid(13), vid(20)))

        assertEquals(listOf(vid(10), vid(11), vid(12), vid(13), vid(20)), favorites.videos)
        // The listing count no longer matches the index: listing, two mirror pages, the add.
        assertEquals(4, h.piped.total)
    }

    @Test
    fun `a failed add drops the index so the next save walks`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.piped.override("/user/playlists/add", status = 500, body = "", times = 1)
        assertFailsWith<IllegalStateException> { h.mirror.save(SavedKind.TRACK, listOf(vid(20))) }
        assertNull(h.store.get("mirror.index:track"))
        h.piped.reset()
        h.mirror.save(SavedKind.TRACK, listOf(vid(20)))
        // Listing, the one-page mirror walk, the add.
        assertEquals(3, h.piped.total)
        assertNotNull(h.store.get("mirror.index:track"))
    }

    @Test
    fun `saving a track already in the mirror posts nothing`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.piped.reset()
        h.mirror.save(SavedKind.TRACK, listOf(vid(10)))
        assertEquals(listOf(vid(10)), h.mirrorOf(SavedKind.TRACK).videos)
        assertEquals(0, h.piped.count("/user/playlists/add"))
    }

    @Test
    fun `the first save creates the mirror playlist`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.mirror.save(SavedKind.TRACK, listOf(vid(20)))
        assertEquals(listOf(vid(20)), h.mirrorOf(SavedKind.TRACK).videos)
        // Listing, create and the add; a new mirror starts with an empty index.
        assertEquals(3, h.piped.total)
    }

    @Test
    fun `a failed mirror add fails the save`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.emptyMirrors()
        h.piped.override("/user/playlists/add", status = 500, body = "")
        val failure = runCatching { h.mirror.save(SavedKind.TRACK, listOf(vid(20))) }
        assertTrue(failure.isFailure)
        assertTrue(h.mirrorOf(SavedKind.TRACK).videos.isEmpty())
    }

    @Test
    fun `saving an album pushes a probed row of the album`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.emptyMirrors()
        h.piped.publicPlaylist("OLAK5uy_A1", "Album - One", listOf(vid(30), vid(31)))

        // The album API writes the library first, then the mirror.
        h.library.saveAlbums(listOf("OLAK5uy_A1"))
        h.mirror.save(SavedKind.ALBUM, listOf("OLAK5uy_A1"))

        assertEquals(listOf(vid(30)), h.mirrorOf(SavedKind.ALBUM).videos)
        assertEquals(listOf(true), h.mirror.isSavedAlbums(listOf("OLAK5uy_A1")))
        // Listing, the empty mirror walk, album page 1, one /streams probe, the add.
        assertEquals(5, h.piped.total)
    }

    @Test
    fun `saving an artist pushes a song found by name`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.emptyMirrors()
        h.piped.channels[artistId] = "Beta Artist"
        h.piped.search("filter=music_songs", listOf(h.piped.row(vid(40))))

        h.library.saveArtists(listOf(artistId))
        h.mirror.save(SavedKind.ARTIST, listOf(artistId))

        assertEquals(listOf(vid(40)), h.mirrorOf(SavedKind.ARTIST).videos)
        assertEquals(listOf(true), h.mirror.isSavedArtists(listOf(artistId)))
        // Listing, the empty mirror walk, channel, search, one /streams probe, the add.
        assertEquals(6, h.piped.total)
    }

    @Test
    fun `removing a saved track deletes its mirror row`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10), vid(11), vid(12)))
        h.mirror.refreshCache()
        h.library.saveTracks(listOf(vid(11)))
        h.piped.reset()

        h.mirror.remove(SavedKind.TRACK, listOf(vid(11)))
        h.mirror.removeLibraryEntries(SavedKind.TRACK, listOf(vid(11)))

        assertEquals(listOf(vid(10), vid(12)), h.mirrorOf(SavedKind.TRACK).videos)
        assertEquals(listOf(false), h.mirror.isSavedTracks(listOf(vid(11))))
        // The listing and one delete at the position the index holds.
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `removing a saved album deletes its representative row`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.emptyMirrors()
        h.piped.publicPlaylist("OLAK5uy_A1", "Album - One", listOf(vid(30), vid(31)))
        h.library.saveAlbums(listOf("OLAK5uy_A1"))
        h.mirror.save(SavedKind.ALBUM, listOf("OLAK5uy_A1"))
        h.piped.reset()

        h.mirror.remove(SavedKind.ALBUM, listOf("OLAK5uy_A1"))
        h.mirror.removeLibraryEntries(SavedKind.ALBUM, listOf("OLAK5uy_A1"))

        assertTrue(h.mirrorOf(SavedKind.ALBUM).videos.isEmpty())
        assertEquals(listOf(false), h.mirror.isSavedAlbums(listOf("OLAK5uy_A1")))
        // The listing and one delete.
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `removing a saved artist deletes its representative row`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.emptyMirrors()
        h.piped.channels[artistId] = "Beta Artist"
        h.piped.search("filter=music_songs", listOf(h.piped.row(vid(40))))
        h.library.saveArtists(listOf(artistId))
        h.mirror.save(SavedKind.ARTIST, listOf(artistId))
        h.piped.reset()

        h.mirror.remove(SavedKind.ARTIST, listOf(artistId))
        h.mirror.removeLibraryEntries(SavedKind.ARTIST, listOf(artistId))

        assertTrue(h.mirrorOf(SavedKind.ARTIST).videos.isEmpty())
        assertEquals(listOf(false), h.mirror.isSavedArtists(listOf(artistId)))
        // The listing and one delete.
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `an unreachable mirror keeps the id saved and fails the unsave`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.piped.override("playlists", status = 500, body = "", method = HttpMethod.Get)
        assertFailsWith<IllegalStateException> { h.mirror.remove(SavedKind.TRACK, listOf(vid(10))) }
        assertEquals(listOf(vid(10)), h.mirrorOf(SavedKind.TRACK).videos)
        assertEquals(listOf(true), h.mirror.isSavedTracks(listOf(vid(10))))
    }

    @Test
    fun `logged out, a save and a removal make no request`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.mirror.save(SavedKind.TRACK, listOf(vid(1)))
        h.mirror.remove(SavedKind.TRACK, listOf(vid(1)))
        assertEquals(0, h.piped.total)
    }

    // ── rowsFor ──────────────────────────────────────────────────────────────

    @Test
    fun `rowsFor on a first open reads page one`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val list = h.piped.accountPlaylist("Long", (0 until 15).map(::vid))
        val rows = h.mirror.rowsFor(list.id)
        assertEquals((0 until 2).map(::vid), rows.tracks.map { it.id })
        assertFalse(rows.complete)
        assertEquals(1, h.piped.total)
    }

    @Test
    fun `rowsFor on a short playlist completes it`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val list = h.piped.accountPlaylist("Short", (0 until 3).map(::vid))
        val rows = h.mirror.rowsFor(list.id, 50)
        assertTrue(rows.complete)
        h.piped.reset()
        // A complete cache is served without a request.
        assertEquals(3, h.mirror.rowsFor(list.id, 50).tracks.size)
        assertEquals(0, h.piped.total)
    }

    @Test
    fun `rowsFor scrolling past the cache costs one request per page`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val list = h.piped.accountPlaylist("Long", (0 until 15).map(::vid))
        h.mirror.rowsFor(list.id)
        h.piped.reset()

        val rows = h.mirror.rowsFor(list.id, 14)

        assertEquals((0 until 14).map(::vid), rows.tracks.map { it.id })
        // Six continuation pages; page 1 was anchored moments ago.
        assertEquals(6, h.piped.total)
    }

    @Test
    fun `rowsFor recovers from a dead stored token by restarting from page one`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val list = h.piped.accountPlaylist("Long", (0 until 15).map(::vid))
        h.mirror.rowsFor(list.id, 12)
        h.piped.reset()
        h.piped.override("nextpage=p%3A12", status = 500, body = "", times = 1)

        val rows = h.mirror.rowsFor(list.id, 14)

        assertEquals((0 until 14).map(::vid), rows.tracks.map { it.id })
        // The failed page, page 1, five pages back to the token, and the page that failed before.
        assertEquals(8, h.piped.total)
    }

    @Test
    fun `rowsFor drops rows removed on the web at the next anchor`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val list = h.piped.accountPlaylist("Long", (0 until 15).map(::vid))
        h.mirror.rowsFor(list.id, 14)
        list.videos.subList(3, 15).clear()
        val key = PLAYLIST_ROWS_PREFIX + list.id
        val cached = assertNotNull(h.store.getDecoded(key, CachedRows.serializer()))
        h.store.put(key, json.encodeToJsonElement(cached.copy(lastVerifiedAt = 1)))
        h.piped.reset()

        // The stale rows are served while the anchor runs in the background.
        assertEquals(14, h.mirror.rowsFor(list.id, 14).tracks.size)
        testScheduler.runCurrent()
        val rows = h.mirror.rowsFor(list.id, 14)

        assertEquals((0 until 3).map(::vid), rows.tracks.map { it.id })
        assertTrue(rows.complete)
        // The background anchor and one continuation page.
        assertEquals(2, h.piped.total)
    }

    // ── maybeRefresh ─────────────────────────────────────────────────────────

    @Test
    fun `maybeRefresh without a snapshot refreshes in the foreground`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist("Mix A", listOf(vid(1)))
        h.mirror.maybeRefresh()
        assertEquals(listOf("Mix A"), assertNotNull(h.mirror.cachedState()).playlists.map { it.name })
        assertNotNull(h.mirror.accountPlaylist(h.piped.account.keys.first()))
    }

    @Test
    fun `maybeRefresh with a fresh snapshot makes no request`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.mirror.refreshCache()
        h.piped.reset()
        h.mirror.maybeRefresh()
        assertEquals(0, h.piped.total)
    }

    @Test
    fun `maybeRefresh with a stale snapshot refreshes in the background`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.mirror.refreshCache()
        val state = assertNotNull(h.store.cachedAccountState())
        h.store.cacheAccountState(state.copy(refreshedAt = 1))
        h.piped.accountPlaylist("New", listOf(vid(1)))
        h.piped.reset()
        h.mirror.maybeRefresh()
        // Served from the stale snapshot right away; the refresh lands on the injected scope.
        assertTrue(assertNotNull(h.mirror.cachedState()).playlists.isEmpty())
        testScheduler.runCurrent()
        assertEquals(listOf("New"), assertNotNull(h.mirror.cachedState()).playlists.map { it.name })
    }

    @Test
    fun `saved ids union the device library and the account snapshot`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.library.saveTracks(listOf(vid(1), vid(10)))
        h.library.saveAlbums(listOf("OLAK5uy_A1"))
        h.library.saveArtists(listOf("channel:Alpha - Topic"))
        assertEquals(listOf(vid(1), vid(10)), h.mirror.allSavedTrackIds())
        assertEquals(listOf("OLAK5uy_A1"), h.mirror.allSavedAlbumIds())
        assertEquals(listOf("channel:Alpha"), h.mirror.allSavedArtistIds())
    }

    // ── created playlists ────────────────────────────────────────────────────

    @Test
    fun `a created playlist is mirrored and edits follow it`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val remoteId = assertNotNull(h.mirror.mirrorCreatePlaylist("piped-x", "Mine", listOf(vid(1), vid(2))))
        val remote = assertNotNull(h.piped.account[remoteId])
        assertEquals(listOf(vid(1), vid(2)), remote.videos)

        assertTrue(h.mirror.mirrorAddTracks("piped-x", listOf(vid(2), vid(3))))
        assertEquals(listOf(vid(1), vid(2), vid(3)), remote.videos)

        assertTrue(h.mirror.mirrorRemoveTracks("piped-x", listOf(vid(1))))
        assertEquals(listOf(vid(2), vid(3)), remote.videos)

        assertTrue(h.mirror.mirrorRenamePlaylist("piped-x", "Renamed"))
        assertEquals("Renamed", remote.name)

        assertTrue(h.mirror.mirrorDeletePlaylist("piped-x"))
        assertTrue(remoteId !in h.piped.account)
    }

    @Test
    fun `a playlist created while the mirror is down stays local only`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.override("/user/playlists/create", status = 500, body = "")
        assertNull(h.mirror.mirrorCreatePlaylist("piped-x", "Mine", listOf(vid(1))))
        // No binding, so later edits succeed locally without touching the account.
        assertTrue(h.mirror.mirrorAddTracks("piped-x", listOf(vid(2))))
        assertTrue(h.piped.account.isEmpty())
    }

    @Test
    fun `a playlist whose mirror create failed is mirrored by the next refresh`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val record = StoredPlaylist(id = "piped-x", name = "Mine", trackIds = listOf(vid(1)))
        h.library.upsertPlaylist(record)
        h.piped.override("/user/playlists/create", status = 500, body = "", times = 1)
        assertNull(h.mirror.mirrorCreatePlaylist(record.id, "Mine", listOf(vid(1))))
        assertNotNull(h.store.get("mirror.pending:${record.id}"))
        h.mirror.refreshCache()
        val remote = h.piped.account.values.single { it.name == "Mine" }
        assertEquals(listOf(vid(1)), remote.videos)
        assertNull(h.store.get("mirror.pending:${record.id}"))
    }

    @Test
    fun `a removal that throws reaches the caller`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.piped.throwOn("/user/playlists/remove")
        assertFailsWith<IllegalStateException> { h.mirror.remove(SavedKind.TRACK, listOf(vid(10))) }
        assertEquals(listOf(true), h.mirror.isSavedTracks(listOf(vid(10))))
    }

    @Test
    fun `deleting a playlist whose remote copy survives fails`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val api = RealMetadataPlaylistAPI(h.client, h.store, h.library, h.mirror, PlayHistory(h.store), LibraryPlaylistSetting(h.store))
        val created = api.createPlaylist("Mine", null, true, false, "", listOf(vid(1)))
        h.piped.override("/user/playlists/delete", status = 500, body = "")
        assertFailsWith<IllegalStateException> { api.deletePlaylist(created.id) }
        assertNotNull(h.library.storedPlaylists().singleOrNull { it.id == created.id })
    }

    @Test
    fun `a failed remote delete keeps the binding`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        assertNotNull(h.mirror.mirrorCreatePlaylist("piped-x", "Mine", emptyList()))
        h.piped.override("/user/playlists/delete", status = 500, body = "")
        assertFalse(h.mirror.mirrorDeletePlaylist("piped-x"))
        assertNotNull(h.store.get("mirror.playlist:piped-x"))
    }
}
