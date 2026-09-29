package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.AccountSession
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import kotlinx.coroutines.CoroutineScope

/** The account side wired over a [FakePiped]: store, library, session and the mirror. */
internal class AccountHarness(scope: CoroutineScope, val piped: FakePiped = FakePiped()) {
    val storage = FakeStorage()
    val store = EntityStore(storage)
    val library = LocalLibrary(store)
    val session = AccountSession(store)
    val instance = InstanceSource(store, FAKE_INSTANCE)
    val client = PipedClient(piped) { FAKE_INSTANCE }
    val lookup = AlbumLookup(client, store)
    val mirror = PipedSavedLibrary(piped, store, library, lookup, instance, scope) { session.load() }

    suspend fun signIn(username: String = "ice") =
        session.save(PipedAccount(instance = FAKE_INSTANCE, username = username, token = "token-$username"))

    /** The three mirrors, empty, so a refresh binds them without creating any. */
    fun emptyMirrors() {
        SavedKind.entries.forEach { piped.accountPlaylist(it.playlistName, emptyList()) }
    }

    fun mirrorOf(kind: SavedKind): FakePiped.Playlist = piped.account.values.first { it.name == kind.playlistName }
}
