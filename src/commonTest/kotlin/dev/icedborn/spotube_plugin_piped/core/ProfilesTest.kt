package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Account-bound keys resolve through the profile of the signed-in account, or of the signed-out state. */
class ProfilesTest {
    private val ice = PipedAccount(FAKE_INSTANCE, "ice", "token-ice")
    private val bob = PipedAccount(FAKE_INSTANCE, "bob", "token-bob")

    private class Setup {
        val storage = FakeStorage()
        val store = EntityStore(storage)
        val session = AccountSession(store)
        val profiles = Profiles(store, session).also { store.useProfiles(it) }
        val library = LocalLibrary(store)
    }

    @Test
    fun `an account id is the FNV-1a hash of its stamp`() {
        assertEquals("cbf29ce484222325", profileId(""))
        assertEquals("af63dc4c8601ec8c", profileId("a"))
        assertNotEquals(profileId("$FAKE_INSTANCE|ice"), profileId("$FAKE_INSTANCE|bob"))
    }

    @Test
    fun `each account and the signed-out state keep their own library`() = runTest {
        val s = Setup()
        s.library.saveTracks(listOf(vid(1)))
        s.session.save(ice)
        s.library.saveTracks(listOf(vid(2)))
        s.session.save(bob)
        assertEquals(emptyList(), s.library.savedTracks())
        s.session.save(ice)
        assertEquals(listOf(vid(2)), s.library.savedTracks())
        s.session.clear()
        assertEquals(listOf(vid(1)), s.library.savedTracks())
        assertTrue("pout.piped.library" in s.storage.values)
        assertTrue("p${profileId(accountStamp(ice))}.piped.library" in s.storage.values)
    }

    @Test
    fun `the legacy owner reads the bare keys`() = runTest {
        val s = Setup()
        s.store.inProfile(BARE_PROFILE) { s.library.saveTracks(listOf(vid(1))) }
        s.profiles.setLegacyOwner(accountStamp(ice))
        s.session.save(ice)
        assertEquals(listOf(vid(1)), s.library.savedTracks())
        s.session.save(bob)
        assertEquals(emptyList(), s.library.savedTracks())
    }

    @Test
    fun `an unreadable legacy owner hides the bare keys without deleting them`() = runTest {
        val s = Setup()
        s.store.inProfile(BARE_PROFILE) { s.library.saveTracks(listOf(vid(1))) }
        s.store.put(LEGACY_PROFILE_KEY, JsonPrimitive(3))
        s.session.save(ice)
        assertEquals(emptyList(), s.library.savedTracks())
        assertTrue("piped.library" in s.storage.values)
    }

    @Test
    fun `account work stays on its account after a switch`() = runTest {
        val s = Setup()
        s.session.save(ice)
        s.store.forAccount(ice) {
            s.session.save(bob)
            s.library.saveTracks(listOf(vid(3)))
        }
        assertEquals(emptyList(), s.library.savedTracks())
        s.session.save(ice)
        assertEquals(listOf(vid(3)), s.library.savedTracks())
    }

    @Test
    fun `device-wide keys ignore the profile`() = runTest {
        val s = Setup()
        s.session.save(ice)
        s.store.put("piped.library.placeholder", JsonPrimitive("OFF"))
        s.store.put("storage.device", JsonPrimitive("aaaa0001"))
        s.session.save(bob)
        assertEquals(JsonPrimitive("OFF"), s.store.get("piped.library.placeholder"))
        assertEquals(JsonPrimitive("aaaa0001"), s.store.get("storage.device"))
    }

    @Test
    fun `dropping the sync state removes only bare sync keys`() = runTest {
        val s = Setup()
        s.storage.values["storage.own:lib"] = "[]"
        s.storage.values["p1.storage.own:lib"] = "[]"
        s.storage.values["storage.device"] = "\"aaaa0001\""
        s.store.dropSyncState()
        assertEquals(setOf("p1.storage.own:lib", "storage.device"), s.storage.values.keys)
    }
}
