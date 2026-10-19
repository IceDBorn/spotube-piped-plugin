package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaylistOwnerTest {

    @Test
    fun `a created playlist is owned by the signed-in user, so the host lets it be edited`() = runTest {
        val h = AccountHarness(backgroundScope)
        h.signIn()
        val api = RealMetadataPlaylistAPI(h.client, h.store, h.library, h.mirror, PlayHistory(h.store), LibraryPlaylistSetting(h.store))
        val users = RealMetadataUserAPI(h.client, h.store) { h.session.load() }
        val created = api.createPlaylist("Mine", null, true, false, "", emptyList())

        val me = assertNotNull(users.getUser(""))

        assertEquals(me.id, created.owner?.id)
        assertEquals(me.id, api.getPlaylist(created.id).owner?.id)
        assertEquals(me.id, users.getUser(me.id)?.id)
        h.session.clear()
        assertNull(users.getUser(""))
    }
}
