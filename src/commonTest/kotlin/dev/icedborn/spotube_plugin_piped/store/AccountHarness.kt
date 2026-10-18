package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.AccountSession
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.sync.StorageSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive

/** The account side wired over a [FakePiped]: store, library, session and the mirror. */
internal class AccountHarness(
    scope: CoroutineScope,
    val piped: FakePiped = FakePiped(),
    val storage: FakeStorage = FakeStorage(),
    storageSync: Boolean = false,
    var clock: Long = 1_000L,
) {
    val store = EntityStore(storage)
    val library = LocalLibrary(store)
    val session = AccountSession(store)
    val instance = InstanceSource(store, FAKE_INSTANCE)
    val client = PipedClient(piped) { FAKE_INSTANCE }
    val lookup = AlbumLookup(client, store)
    val history = PlayHistory(store)
    val sync: StorageSync? = if (storageSync) {
        StorageSync(
            piped,
            store,
            library,
            history,
            RegionSetting(store, null),
            LibraryPlaylistSetting(store),
            scope,
            now = { clock },
        ) { session.load() }
    } else {
        null
    }
    val mirror = PipedSavedLibrary(piped, store, library, lookup, instance, scope, history, sync) { session.load() }

    /** Fixes the device id, so two harnesses on one FakePiped act as two devices. */
    suspend fun device(id: String) = store.put("storage.device", JsonPrimitive(id))

    suspend fun signIn(username: String = "ice") =
        session.save(PipedAccount(instance = FAKE_INSTANCE, username = username, token = "token-$username"))

    /** The three mirrors, empty, so a refresh binds them without creating any. */
    fun emptyMirrors() {
        SavedKind.entries.forEach { piped.accountPlaylist(it.playlistName, emptyList()) }
    }

    fun mirrorOf(kind: SavedKind): FakePiped.Playlist = piped.account.values.first { it.name == kind.playlistName }
}
