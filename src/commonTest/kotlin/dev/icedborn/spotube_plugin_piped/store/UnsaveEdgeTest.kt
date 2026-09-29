package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unsaves of ids without a mirror row, bindings from before account stamps, and refreshes that race the user. */
class UnsaveEdgeTest {

    @Test
    fun `an album saved while signed out can be unsaved after sign-in`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.library.saveAlbums(listOf("OLAK5uy_X"))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(5)))
        h.store.cacheTrackAlbum(vid(5), "OLAK5uy_Other")
        h.mirror.refreshCache()
        h.mirror.remove(SavedKind.ALBUM, listOf("OLAK5uy_X"))
        assertFalse("OLAK5uy_X" in h.library.savedAlbums())
    }

    @Test
    fun `an artist saved while signed out can be unsaved after sign-in`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.library.saveArtists(listOf("channel:Alpha", "channel:Beta"))
        h.store.put("saved.rep:artist:channel:Beta", JsonPrimitive(vid(6)))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, listOf(vid(6)))
        h.mirror.refreshCache()
        h.mirror.remove(SavedKind.ARTIST, listOf("channel:Alpha"))
        assertEquals(listOf("channel:Beta"), h.library.savedArtists())
    }

    @Test
    fun `a legacy none verdict on a row does not block an album unsave`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.library.saveAlbums(listOf("OLAK5uy_X"))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(5)))
        h.storage.values["track-album:${vid(5)}"] = "\"none\""
        h.mirror.refreshCache()
        h.mirror.remove(SavedKind.ALBUM, listOf("OLAK5uy_X"))
        assertFalse("OLAK5uy_X" in h.library.savedAlbums())
    }

    @Test
    fun `a bare playlist binding blocks edits until this account's listing is checked`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.store.put("mirror.playlist:piped-x", JsonPrimitive("uuid-elsewhere"))
        assertFalse(h.mirror.mirrorAddTracks("piped-x", listOf(vid(1))))
        assertFalse(h.mirror.mirrorDeletePlaylist("piped-x"))
        val migration = StorageMigration(AccountHttp(h.piped), h.store, h.library, h.session, h.instance)
        migration.adoptBareBindings()
        // This account does not own the copy, so edits stay local and the other account's copy is left alone.
        assertTrue(h.mirror.mirrorAddTracks("piped-x", listOf(vid(1))))
        assertTrue(h.piped.account.isEmpty())
    }

    @Test
    fun `a copy that cannot be bound is deleted and not retried`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.storage.failPutsFor = "mirror.playlist:"
        assertNull(h.mirror.mirrorCreatePlaylist("piped-x", "piped-x", listOf(vid(1))))
        // The copy is deleted rather than orphaned, and the write is marked pending for the next refresh.
        assertTrue(h.piped.account.isEmpty())
        assertNotNull(h.store.get("mirror.pending:piped-x"))
        // A second edit in the same session does not create another copy.
        assertNull(h.mirror.mirrorCreatePlaylist("piped-x", "piped-x", listOf(vid(1))))
        assertTrue(h.piped.account.isEmpty())
        assertEquals(1, h.piped.count("/user/playlists/create"))
    }

    @Test
    fun `a refresh that ends after an account switch writes nothing`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist("Mine", listOf(vid(1)))
        h.piped.latencyMs = 1_000
        val refresh = launch { h.mirror.refreshCache() }
        advanceTimeBy(500)
        h.signIn("bob")
        refresh.join()
        assertNull(h.store.cachedAccountState())
        assertNull(h.store.get(bindingsPlaylistKey(SavedKind.TRACK)))
    }

    @Test
    fun `an unsave that lands between the walk and the snapshot stays unsaved`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 100))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10), vid(11)))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, emptyList())
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, emptyList())
        h.piped.accountPlaylist("Extra", listOf(vid(1)))
        h.mirror.refreshCache()
        h.piped.latencyMs = 1_000
        // The fourth walk waits for a free slot, so the snapshot comes a second after the track walk.
        val refresh = launch { h.mirror.refreshCache() }
        advanceTimeBy(500)
        val unsave = launch { h.mirror.remove(SavedKind.TRACK, listOf(vid(10))) }
        refresh.join()
        unsave.join()
        assertEquals(listOf(false, true), h.mirror.isSavedTracks(listOf(vid(10), vid(11))))
    }

    @Test
    fun `an album unsave during a stale walk does not come back`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val bindings = SavedBindings(h.store)
        val resolver = SavedSetResolver(h.store, bindings, h.lookup, Representatives(AccountHttp(h.piped), h.store, h.instance))
        val previous = AccountCacheState(savedAlbums = listOf("A", "B"), instance = h.instance.api()!!, username = "ice")
        val start = previous.copy(savedAlbums = listOf("A"))
        h.store.cacheAccountState(start)
        resolver.resolve(
            assertNotNull(h.session.load()),
            start,
            previous,
            mapOf(SavedKind.ALBUM to emptyList()),
            mapOf(SavedKind.ALBUM to false),
        )
        assertEquals(listOf("A"), assertNotNull(h.store.cachedAccountState()).savedAlbums)
    }

    @Test
    fun `failed album lookups spend the resolution budget`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 100))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, (1..ACCOUNT_RESOLVE_LIMIT + 5).map { vid(it) })
        (1..ACCOUNT_RESOLVE_LIMIT + 5).forEach { h.piped.uploaders[vid(it)] = "Alpha" }
        h.piped.override("/search", status = 500, body = "")
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        // Each failed lookup costs a streams fetch and a search.
        assertEquals(ACCOUNT_RESOLVE_LIMIT / 2, h.piped.count("/streams/"))
    }

    @Test
    fun `a refresh walk with an empty page 1 keeps the cached rows`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val playlist = h.piped.accountPlaylist("Mine", listOf(vid(1), vid(2)))
        h.mirror.refreshCache()
        val key = PLAYLIST_ROWS_PREFIX + playlist.id
        // The playlist is emptied on the web, but the listing still counts its rows, so the walk proves nothing.
        playlist.videos.clear()
        h.piped.override("/user/playlists", body = """[{"id":"${playlist.id}","name":"Mine","videos":2}]""")
        h.mirror.refreshCache()
        assertEquals(2, assertNotNull(h.store.getDecoded(key, CachedRows.serializer())).tracks.size)
    }

    @Test
    fun `a refresh walk keeps the listed total of an account playlist`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val playlist = h.piped.accountPlaylist("Mine", listOf(vid(1), vid(2)))
        h.mirror.refreshCache()
        val stored = assertNotNull(h.store.getDecoded(PLAYLIST_ROWS_PREFIX + playlist.id, CachedRows.serializer()))
        assertEquals(2, stored.listedTotal)
    }

    private fun bindingsPlaylistKey(kind: SavedKind) = "saved.playlist:${kind.key}"
}
