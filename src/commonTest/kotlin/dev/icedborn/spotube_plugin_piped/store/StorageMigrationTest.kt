package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.AccountSession
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-time rewrite of older storage shapes, and cache eviction at start. */
class StorageMigrationTest {

    private class Harness {
        val piped = FakePiped()
        val storage = FakeStorage()
        val store = EntityStore(storage)
        val library = LocalLibrary(store)
        val session = AccountSession(store)
        val instance = InstanceSource(store, FAKE_INSTANCE)

        suspend fun signIn() = session.save(PipedAccount(FAKE_INSTANCE, "ice", "token-ice"))

        val migration = StorageMigration(AccountHttp(piped), store, library, session, instance)

        suspend fun migrate() = migration.run()

        suspend fun binding(key: String): StoredPlaylistId? = store.get(key)?.let { json.decodeFromJsonElement(it) }
    }

    @Test
    fun `bare uuid bindings the listing holds are stamped with the signed-in account`() = runTest {
        val h = Harness()
        h.signIn()
        h.piped.accountPlaylist("Spotube - Favorites", emptyList(), id = "uuid-fav")
        h.piped.accountPlaylist("Mine", emptyList(), id = "uuid-x")
        h.store.put("saved.playlist:track", JsonPrimitive("uuid-fav"))
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.store.put("mirror.playlist:piped-x", JsonPrimitive("uuid-x"))
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-gone", name = "piped-gone"))
        h.store.put("mirror.playlist:piped-gone", JsonPrimitive("uuid-gone"))
        h.migrate()
        assertEquals(StoredPlaylistId(FAKE_INSTANCE, "ice", "uuid-fav"), h.binding("saved.playlist:track"))
        assertEquals(StoredPlaylistId(FAKE_INSTANCE, "ice", "uuid-x"), h.binding("mirror.playlist:piped-x"))
        // Another account may own the copy, so a binding the listing lacks stays for the next sign-in.
        assertEquals(JsonPrimitive("uuid-gone"), h.store.get("mirror.playlist:piped-gone"))
        h.session.save(PipedAccount(FAKE_INSTANCE, "bob", "token-bob"))
        h.piped.accountPlaylist("Theirs", emptyList(), id = "uuid-gone")
        h.migration.adoptBareBindings()
        assertEquals(StoredPlaylistId(FAKE_INSTANCE, "bob", "uuid-gone"), h.binding("mirror.playlist:piped-gone"))
    }

    @Test
    fun `bare uuid bindings wait for a session and a reachable listing`() = runTest {
        val h = Harness()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-x", name = "piped-x"))
        h.store.put("mirror.playlist:piped-x", JsonPrimitive("uuid-x"))
        h.migrate()
        assertEquals(JsonPrimitive("uuid-x"), h.store.get("mirror.playlist:piped-x"))
        h.signIn()
        h.piped.override("/user/playlists", status = 502, body = "")
        h.migration.adoptBareBindings()
        assertEquals(JsonPrimitive("uuid-x"), h.store.get("mirror.playlist:piped-x"))
    }

    @Test
    fun `a failed migration keeps the old version so it runs again`() = runTest {
        val h = Harness()
        h.storage.throwOnKeys = true
        h.migrate()
        assertNull(h.store.get(SCHEMA_VERSION_KEY))
        h.storage.throwOnKeys = false
        h.migrate()
        assertEquals(JsonPrimitive(SCHEMA_VERSION), h.store.get(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `a migration with a failed write keeps the old version`() = runTest {
        val h = Harness()
        h.store.put("saved.owner:track:${vid(1)}", JsonPrimitive("x|y"))
        h.storage.failPutsFor = "saved.owner:"
        h.migrate()
        assertNull(h.store.get(SCHEMA_VERSION_KEY))
        h.storage.failPutsFor = null
        h.migrate()
        assertEquals(JsonPrimitive(SCHEMA_VERSION), h.store.get(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `a failed step keeps the steps before it`() = runTest {
        val h = Harness()
        h.store.put(SCHEMA_VERSION_KEY, JsonPrimitive(1))
        h.store.put("track-album:${vid(1)}", JsonPrimitive("none"))
        h.storage.failPutsFor = "track-album:"
        h.migrate()
        assertEquals(JsonPrimitive(2), h.store.get(SCHEMA_VERSION_KEY))
        // The row cache wipe of version 2 does not run again.
        h.store.put(PLAYLIST_ROWS_PREFIX + "PL1", JsonPrimitive("rows"))
        h.migrate()
        assertNotNull(h.store.get(PLAYLIST_ROWS_PREFIX + "PL1"))
        h.storage.failPutsFor = null
        h.migrate()
        assertEquals(JsonPrimitive(SCHEMA_VERSION), h.store.get(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `version 3 drops album none keys and stamps plain none verdicts`() = runTest {
        val h = Harness()
        h.store.put(SCHEMA_VERSION_KEY, JsonPrimitive(2))
        h.store.put("album.none:Alpha|Song", JsonPrimitive(1L))
        h.store.put("track-album:${vid(1)}", JsonPrimitive("none"))
        h.store.put("track-album:${vid(2)}", JsonPrimitive("OLAK5uy_A"))
        h.migrate()
        assertNull(h.store.get("album.none:Alpha|Song"))
        assertEquals(NO_ALBUM, h.store.cachedTrackAlbum(vid(1)))
        assertEquals("OLAK5uy_A", h.store.cachedTrackAlbum(vid(2)))
    }

    @Test
    fun `account playlist rows are not evicted with public ones`() = runTest {
        val h = Harness()
        h.store.put(SCHEMA_VERSION_KEY, JsonPrimitive(SCHEMA_VERSION))
        h.store.cacheAccountState(AccountCacheState(playlists = listOf(CachedAccountPlaylist("uuid-a", "Mine", 1))))
        h.store.put(PLAYLIST_ROWS_PREFIX + "uuid-a", JsonPrimitive("rows"))
        (0 until 201).forEach { h.store.put(PLAYLIST_ROWS_PREFIX + "PL$it", JsonPrimitive("rows")) }
        h.migrate()
        assertNotNull(h.store.get(PLAYLIST_ROWS_PREFIX + "uuid-a"))
        assertEquals(201, h.storage.values.keys.count { it.startsWith(PLAYLIST_ROWS_PREFIX) })
    }

    @Test
    fun `entries from before the access index age from their first eviction`() = runTest {
        val storage = FakeStorage()
        (0 until 3).forEach { storage.values["track:$it"] = "\"t$it\"" }
        EntityStore(storage).evict(mapOf("track:" to 5))
        val store = EntityStore(storage)
        store.put("track:3", json.encodeToJsonElement("t3"))
        store.evict(mapOf("track:" to 3))
        assertEquals(3, storage.values.keys.count { it.startsWith("track:") })
        assertTrue("track:3" in storage.values)
    }

    @Test
    fun `Topic artist ids become canonical in reps and the library`() = runTest {
        val h = Harness()
        h.store.put("saved.rep:artist:channel:Alpha - Topic", JsonPrimitive(vid(1)))
        h.library.saveArtists(listOf("channel:Alpha - Topic"))
        h.migrate()
        assertNull(h.store.get("saved.rep:artist:channel:Alpha - Topic"))
        assertEquals(JsonPrimitive(vid(1)), h.store.get("saved.rep:artist:channel:Alpha"))
        assertEquals(listOf("channel:Alpha"), h.library.savedArtists())
    }

    @Test
    fun `a single owner stamp becomes a list`() = runTest {
        val h = Harness()
        h.store.put("saved.owner:track:${vid(1)}", JsonPrimitive("$FAKE_INSTANCE|ice"))
        h.migrate()
        assertEquals(JsonArray(listOf(JsonPrimitive("$FAKE_INSTANCE|ice"))), h.store.get("saved.owner:track:${vid(1)}"))
    }

    @Test
    fun `a snapshot without identity is claimed by the session`() = runTest {
        val h = Harness()
        h.signIn()
        h.store.cacheAccountState(AccountCacheState(savedTracks = listOf(vid(1))))
        h.migrate()
        val state = assertNotNull(h.store.cachedAccountState())
        assertEquals(FAKE_INSTANCE to "ice", state.instance to state.username)
        assertEquals(listOf(vid(1)), state.savedTracks)
    }

    @Test
    fun `a snapshot without identity is claimed by the configured instance when logged out`() = runTest {
        val h = Harness()
        h.store.cacheAccountState(AccountCacheState(savedTracks = listOf(vid(1))))
        h.migrate()
        assertEquals(FAKE_INSTANCE, assertNotNull(h.store.cachedAccountState()).instance)
    }

    @Test
    fun `old account playlist copies are deleted`() = runTest {
        val h = Harness()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-acct-1", name = "Old"))
        h.library.savePlaylists(listOf("piped-acct-1"))
        h.migrate()
        assertTrue(h.library.storedPlaylists().isEmpty())
    }

    @Test
    fun `the migration runs once`() = runTest {
        val h = Harness()
        h.migrate()
        h.store.put("saved.playlist:track", JsonPrimitive("uuid-late"))
        h.migrate()
        assertEquals(JsonPrimitive("uuid-late"), h.store.get("saved.playlist:track"))
        assertEquals(JsonPrimitive(SCHEMA_VERSION), h.store.get(SCHEMA_VERSION_KEY))
    }

    @Test
    fun `eviction keeps the most recently used entries under the cap`() = runTest {
        val storage = FakeStorage()
        var store = EntityStore(storage)
        (0 until 5).forEach { store.put("track:$it", json.encodeToJsonElement("t$it")) }
        store.evict(mapOf("track:" to 5))
        // A fresh store reads the persisted access index, as after a restart.
        store = EntityStore(storage)
        store.get("track:0")
        store.put("track:5", json.encodeToJsonElement("t5"))
        store.evict(mapOf("track:" to 3))
        assertEquals(3, storage.values.keys.count { it.startsWith("track:") })
        assertTrue("track:0" in storage.values)
        assertTrue("track:5" in storage.values)
    }

    @Test
    fun `eviction keeps entries whose stamps were never flushed`() = runTest {
        val storage = FakeStorage()
        val first = EntityStore(storage)
        (0 until 3).forEach { first.put("track:$it", json.encodeToJsonElement("t$it")) }
        first.evict(mapOf("track:" to 5))
        // The second run writes two entries and ends before its access stamps reach storage.
        val second = EntityStore(storage)
        (3 until 5).forEach { second.put("track:$it", json.encodeToJsonElement("t$it")) }
        EntityStore(storage).evict(mapOf("track:" to 3))
        assertEquals(setOf("track:2", "track:3", "track:4"), storage.values.keys.filter { it.startsWith("track:") }.toSet())
    }

    @Test
    fun `a copy binding of a deleted playlist is dropped at start`() = runTest {
        val h = Harness()
        h.signIn()
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-keep", name = "keep"))
        h.library.upsertPlaylist(StoredPlaylist(id = "piped-gone", name = "gone"))
        h.store.put("mirror.playlist:piped-keep", JsonPrimitive("uuid-keep"))
        h.store.put("mirror.playlist:piped-gone", JsonPrimitive("uuid-gone"))
        h.store.put("binding.miss:mirror.playlist:piped-gone", JsonArray(listOf(JsonPrimitive("other|bob"))))
        h.store.put("mirror.pending:piped-gone", JsonPrimitive("uuid-old"))
        h.store.put("binding.miss:mirror.playlist:piped-keep", JsonArray(listOf(JsonPrimitive("other|bob"))))
        h.store.put("mirror.pending:piped-keep", JsonPrimitive(true))
        h.library.deleteStoredPlaylist("piped-gone")
        h.migrate()
        assertNotNull(h.store.get("mirror.playlist:piped-keep"))
        assertNull(h.store.get("mirror.playlist:piped-gone"))
        assertNull(h.store.get("binding.miss:mirror.playlist:piped-gone"))
        assertNull(h.store.get("mirror.pending:piped-gone"))
        // A kept playlist's records are left alone, or its retry would be dropped.
        assertNotNull(h.store.get("binding.miss:mirror.playlist:piped-keep"))
        assertNotNull(h.store.get("mirror.pending:piped-keep"))
    }

    @Test
    fun `a corrupt playlist list leaves every copy binding alone`() = runTest {
        val h = Harness()
        h.signIn()
        h.store.put("piped.playlists", JsonPrimitive("not a list"))
        h.store.put("mirror.playlist:piped-gone", JsonPrimitive("uuid-gone"))
        h.migrate()
        assertNotNull(h.store.get("mirror.playlist:piped-gone"))
    }

    @Test
    fun `expired resolution latches are cleared at start`() = runTest {
        val h = Harness()
        h.store.put("unresolvable:album:x|y:${vid(1)}", JsonPrimitive(1L))
        h.store.put("unresolvable:album:x|y:${vid(2)}", JsonPrimitive(epochMillis()))
        h.migrate()
        assertNull(h.store.get("unresolvable:album:x|y:${vid(1)}"))
        assertNotNull(h.store.get("unresolvable:album:x|y:${vid(2)}"))
    }
}
