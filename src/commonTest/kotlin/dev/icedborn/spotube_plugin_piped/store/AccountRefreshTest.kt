package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.CachedRows
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Account sync edge cases: refresh writes that race saves and unsaves, failed unsaves and pending copies. */
class AccountRefreshTest {

    private val account = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")

    @Test
    fun `a merge keeps saves and unsaves made during the walk`() {
        val merged = mergeSaved(rebuilt = listOf("a", "b"), before = listOf("a", "b"), now = listOf("a", "c"))
        assertEquals(listOf("a", "c"), merged)
    }

    @Test
    fun `a refresh index is skipped when a writer changed the index during the walk`() = runTest {
        val bindings = SavedBindings(EntityStore(FakeStorage()))
        val since = bindings.generation(SavedKind.TRACK)
        bindings.writeIndex(account, SavedKind.TRACK, "pl", listOf(vid(1), vid(2)))
        bindings.refreshIndex(account, SavedKind.TRACK, "pl", listOf(vid(1)), since)
        assertEquals(listOf(vid(1), vid(2)), assertNotNull(bindings.indexOf(account, SavedKind.TRACK, "pl", 2)).videos)
        bindings.refreshIndex(account, SavedKind.TRACK, "pl", listOf(vid(3)), bindings.generation(SavedKind.TRACK))
        assertEquals(listOf(vid(3)), assertNotNull(bindings.indexOf(account, SavedKind.TRACK, "pl", 1)).videos)
    }

    @Test
    fun `an index write drops the stored partial walk`() = runTest {
        val store = EntityStore(FakeStorage())
        val bindings = SavedBindings(store)
        store.put(bindings.walkKey(SavedKind.TRACK), JsonPrimitive("partial"))
        bindings.writeIndex(account, SavedKind.TRACK, "pl", listOf(vid(1)))
        assertNull(store.get(bindings.walkKey(SavedKind.TRACK)))
    }

    @Test
    fun `a refresh stores the walk of an account playlist and a failed walk keeps it`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val playlist = h.piped.accountPlaylist("Mine", listOf(vid(1), vid(2)))
        h.mirror.refreshCache()
        val key = PLAYLIST_ROWS_PREFIX + playlist.id
        val stored = assertNotNull(h.store.getDecoded(key, CachedRows.serializer()))
        assertEquals(listOf(vid(1), vid(2)), stored.tracks.map { it.id })
        h.piped.override("/playlists/${playlist.id}", status = 500, body = "")
        h.mirror.refreshCache()
        assertEquals(stored.tracks, assertNotNull(h.store.getDecoded(key, CachedRows.serializer())).tracks)
    }

    @Test
    fun `failed artist lookups spend the resolution budget`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 100))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, (1..ACCOUNT_RESOLVE_LIMIT + 5).map { vid(it) })
        h.piped.override("/streams/", status = 500, body = "")
        h.mirror.refreshCache()
        testScheduler.runCurrent()
        assertEquals(ACCOUNT_RESOLVE_LIMIT, h.piped.count("/streams/"))
    }

    @Test
    fun `a resolution queued behind a newer refresh is skipped`() = runTest {
        val h = AccountHarness(backgroundScope, FakePiped(pageSize = 100))
        h.signIn()
        h.piped.accountPlaylist(SavedKind.ARTIST.playlistName, (1..10).map { vid(it) })
        h.piped.override("/streams/", status = 500, body = "")
        h.piped.latencyMs = 1_000
        h.mirror.refreshCache()
        // The first resolution runs for 10 seconds, so the next two queue behind it and only the newest runs.
        val refreshes = listOf(launch { h.mirror.refreshCache() }, launch { h.mirror.refreshCache() })
        refreshes.joinAll()
        advanceTimeBy(60_000)
        assertEquals(20, h.piped.count("/streams/"))
    }

    @Test
    fun `a throttled delete fails the unsave`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        h.piped.accountPlaylist(SavedKind.TRACK.playlistName, listOf(vid(10)))
        h.mirror.refreshCache()
        h.piped.override("/user/playlists/remove", body = "{}", method = HttpMethod.Post)
        assertFailsWith<IllegalStateException> { h.mirror.remove(SavedKind.TRACK, listOf(vid(10))) }
        assertEquals(listOf(true), h.mirror.isSavedTracks(listOf(vid(10))))
    }

    @Test
    fun `a pending copy of another account is not created again`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val record = StoredPlaylist(id = "piped-x", name = "Mine", trackIds = listOf(vid(1)))
        h.library.upsertPlaylist(record)
        h.piped.override("/user/playlists/add", status = 500, body = "", times = 1)
        assertNull(h.mirror.mirrorCreatePlaylist(record.id, "Mine", listOf(vid(1))))
        h.signIn("bob")
        h.mirror.refreshCache()
        assertEquals(1, h.piped.account.values.count { it.name == "Mine" })
        assertNotNull(h.store.get("mirror.pending:${record.id}"))
    }
}
