package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rows whose lookups never finish, the unsaves they let through, and bindings adopted by a refresh. */
class ResolutionLatchTest {

    private val stamp = "$FAKE_INSTANCE|ice"

    @Test
    fun `a row whose lookup spends the whole budget is latched after 3 refreshes`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.uploaders[vid(1)] = "Alpha"
        // Two searches of 7 four-page albums without the video: each row's 17-request share is not enough.
        fun albums(prefix: String) = (0 until 7).map { i ->
            h.piped.publicPlaylist("OLAK5uy_$prefix$i", "$prefix $i", (0 until 7).map { vid(1000 + 100 * i + 10 * prefix.length + it) })
            h.piped.albumRow("OLAK5uy_$prefix$i", "$prefix $i", "Alpha")
        }
        h.piped.search("q=Alpha&", albums("A"))
        h.piped.search("q=Title", albums("TT"))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1)))
        repeat(2) {
            h.mirror.refreshCache()
            testScheduler.runCurrent()
        }
        assertEquals(JsonPrimitive(2), h.store.get("resolutionRetry:album:$stamp:${vid(1)}"))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertNotNull(h.store.get("unresolvable:album:$stamp:${vid(1)}"))
    }

    /** X saved here, its only mirror row latched, then unsaved past that row. */
    private suspend fun AccountHarness.unsavePastLatchedRow() {
        library.saveAlbums(listOf("OLAK5uy_X"))
        signIn()
        piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(5)))
        store.put("unresolvable:album:$stamp:${vid(5)}", JsonPrimitive(epochMillis()))
        mirror.refreshCache()
        mirror.remove(SavedKind.ALBUM, listOf("OLAK5uy_X"))
        // Every row turns out to be on X.
        piped.uploaders[vid(5)] = "Alpha"
        piped.uploaders[vid(6)] = "Alpha"
        piped.publicPlaylist("OLAK5uy_X", "X", listOf(vid(5), vid(6)))
        piped.search("filter=music_albums", listOf(piped.albumRow("OLAK5uy_X", "X", "Alpha")))
    }

    /** X saved here, its only mirror row proven to be on no album, then unsaved past that row. */
    private suspend fun AccountHarness.unsavePastNoneRow() {
        library.saveAlbums(listOf("OLAK5uy_X"))
        signIn()
        piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(5)))
        store.cacheTrackAlbum(vid(5), NO_ALBUM)
        mirror.refreshCache()
        mirror.remove(SavedKind.ALBUM, listOf("OLAK5uy_X"))
        // The verdict expires and the row turns out to be on X.
        store.remove("track-album:${vid(5)}")
        piped.uploaders[vid(5)] = "Alpha"
        piped.uploaders[vid(6)] = "Alpha"
        piped.publicPlaylist("OLAK5uy_X", "X", listOf(vid(5), vid(6)))
        piped.search("filter=music_albums", listOf(piped.albumRow("OLAK5uy_X", "X", "Alpha")))
    }

    @Test
    fun `an album unsaved past a fresh none row does not come back when the verdict expires`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.unsavePastNoneRow()
        assertNotNull(h.store.get("saved.gone:album:$stamp"))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertFalse("OLAK5uy_X" in h.mirror.allSavedAlbumIds())
    }

    /** Lookups that find no album and so spend the budget: 14 albums of 4 pages, searched by artist and by title. */
    private fun expensiveLookups(h: AccountHarness) {
        h.piped.uploaders[vid(2)] = "Alpha"
        fun albums(prefix: String) = (0 until 7).map { i ->
            val id = "OLAK5uy_$prefix$i"
            h.piped.publicPlaylist(id, "$prefix $i", (0 until 7).map { vid(1000 + 100 * i + 10 * prefix.length + it) })
            h.piped.albumRow(id, "$prefix $i", "Alpha")
        }
        h.piped.search("q=Alpha&", albums("A"))
        h.piped.search("q=Title", albums("TT"))
    }

    @Test
    fun `a row behind a cheap row still gets a full share and is charged for spending it`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.uploaders[vid(1)] = "Beta"
        h.piped.publicPlaylist("OLAK5uy_Cheap", "Cheap", listOf(vid(1)))
        h.piped.search("q=Beta&", listOf(h.piped.albumRow("OLAK5uy_Cheap", "Cheap", "Beta")))
        expensiveLookups(h)
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1), vid(2)))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertEquals(JsonPrimitive(vid(1)), h.store.get("saved.rep:album:OLAK5uy_Cheap"))
        assertEquals(JsonPrimitive(1), h.store.get("resolutionRetry:album:$stamp:${vid(2)}"))
    }

    @Test
    fun `a deep album chain is proven on a later refresh from cached pages`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 1))
        h.signIn()
        h.piped.uploaders[vid(1)] = "Alpha"
        // One candidate album of 8 pages, and the video is in none of them.
        h.piped.publicPlaylist("OLAK5uy_Deep", "Deep", (0 until 8).map { vid(100 + it) })
        h.piped.search("q=Alpha&", listOf(h.piped.albumRow("OLAK5uy_Deep", "Deep", "Alpha")))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1)))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        // The chain is deeper than one refresh walks, so nothing is proven yet.
        assertNull(h.store.get("track-album:${vid(1)}"))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        // The pages walked by the first refresh are cached, so the second one reaches the end.
        assertTrue(h.store.hasNoAlbumVerdict(vid(1)))
    }

    @Test
    fun `an empty last chain page is refetched instead of cached`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 1))
        h.signIn()
        h.piped.uploaders[vid(1)] = "Alpha"
        h.piped.publicPlaylist("OLAK5uy_Thin", "Thin", (0 until 3).map { vid(100 + it) })
        h.piped.search("q=Alpha&", listOf(h.piped.albumRow("OLAK5uy_Thin", "Thin", "Alpha")))
        // The last page of the chain answers as a throttle once, which proves nothing.
        h.piped.override("nextpage=p%3A2", body = """{"relatedStreams":[],"nextpage":null}""", times = 1)
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1)))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertFalse(h.store.hasNoAlbumVerdict(vid(1)))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        // The throttle was not cached, so the next refresh reaches the real end of the chain.
        assertTrue(h.store.hasNoAlbumVerdict(vid(1)))
    }

    @Test
    fun `a row cut short by the rows before it is not latched`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        // Two heavy rows spend two full shares, so the third gets less than a share of its own.
        h.piped.uploaders[vid(1)] = "Alpha"
        h.piped.uploaders[vid(2)] = "Alpha"
        h.piped.uploaders[vid(3)] = "Alpha"
        expensiveLookups(h)
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(1), vid(2), vid(3)))
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertEquals(JsonPrimitive(1), h.store.get("resolutionRetry:album:$stamp:${vid(1)}"))
        assertEquals(JsonPrimitive(1), h.store.get("resolutionRetry:album:$stamp:${vid(2)}"))
        assertNull(h.store.get("resolutionRetry:album:$stamp:${vid(3)}"))
        assertNull(h.store.get("unresolvable:album:$stamp:${vid(3)}"))
    }

    @Test
    fun `an album unsaved past a latched row does not come back when the row resolves`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.unsavePastLatchedRow()
        h.store.remove("unresolvable:album:$stamp:${vid(5)}")
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertFalse("OLAK5uy_X" in h.mirror.allSavedAlbumIds())
        // Saving it again lifts the mark.
        h.mirror.save(SavedKind.ALBUM, listOf("OLAK5uy_X"))
        assertNull(h.store.get("saved.gone:album:$stamp"))
    }

    @Test
    fun `a save on the web through another row brings the album back`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.unsavePastLatchedRow()
        h.mirrorOf(SavedKind.ALBUM).videos += vid(6)
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertTrue("OLAK5uy_X" in h.mirror.allSavedAlbumIds())
    }

    @Test
    fun `another account is not bound by the mark`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.unsavePastLatchedRow()
        h.signIn("bob")
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertTrue("OLAK5uy_X" in h.mirror.allSavedAlbumIds())
    }

    @Test
    fun `a mark goes once its row leaves the mirror`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.unsavePastLatchedRow()
        assertNotNull(h.store.get("saved.gone:album:$stamp"))
        h.mirrorOf(SavedKind.ALBUM).videos.clear()
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertNull(h.store.get("saved.gone:album:$stamp"))
    }

    @Test
    fun `an unproven mirror state leaves a gone record alone`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val bindings = SavedBindings(h.store)
        val resolver = SavedSetResolver(h.store, bindings, h.lookup, Representatives(AccountHttp(h.piped), h.store, h.instance))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, listOf(vid(5), vid(6)))
        val account = assertNotNull(h.session.load())
        // The mark belongs to a row this walk never reached, so it is not proven gone.
        bindings.markGone(SavedKind.ALBUM, account, listOf(vid(6)), listOf("OLAK5uy_X"))
        val start = AccountCacheState(instance = h.instance.api()!!, username = "ice")
        h.store.cacheAccountState(start)
        resolver.resolve(
            account,
            start,
            null,
            mapOf(SavedKind.ALBUM to listOf(vid(5))),
            emptyMap(),
        )
        testScheduler.runCurrent()
        assertNotNull(h.store.get("saved.gone:album:$stamp"))
    }

    @Test
    fun `a refresh adopts a bare binding that the start could not check`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist("Mine", emptyList(), id = "uuid-x")
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.store.put("mirror.playlist:piped-x", JsonPrimitive("uuid-x"))
        h.store.put("binding.miss:mirror.playlist:piped-x", JsonArray(listOf(JsonPrimitive("other|bob"))))
        h.mirror.refreshCache()
        assertNotNull(h.store.getDecoded("mirror.playlist:piped-x", StoredPlaylistId.serializer()))
        assertEquals(null, h.store.get("binding.miss:mirror.playlist:piped-x"))
    }

    @Test
    fun `deleting a playlist drops its miss record`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.store.put("mirror.playlist:piped-x", JsonPrimitive("uuid-elsewhere"))
        h.mirror.refreshCache()
        assertNotNull(h.store.get("binding.miss:mirror.playlist:piped-x"))
        assertTrue(h.mirror.mirrorDeletePlaylist("piped-x"))
        assertEquals(null, h.store.get("binding.miss:mirror.playlist:piped-x"))
    }

    @Test
    fun `a save that lands between the walk and the snapshot stays saved`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 100))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(11)))
        h.piped.accountPlaylist(SavedKind.ALBUM.playlistName, emptyList())
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, emptyList())
        h.piped.accountPlaylist("Extra", listOf(vid(1)))
        h.mirror.refreshCache()
        h.piped.latencyMs = 1_000
        val refresh = launch { h.mirror.refreshCache() }
        advanceTimeBy(500)
        val save = launch { h.mirror.save(SavedKind.TRACK, listOf(vid(10))) }
        refresh.join()
        save.join()
        assertEquals(listOf(true, true), h.mirror.isSavedTracks(listOf(vid(10), vid(11))))
    }
}
