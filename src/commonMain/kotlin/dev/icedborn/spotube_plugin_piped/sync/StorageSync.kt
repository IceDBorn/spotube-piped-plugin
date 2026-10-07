package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.store.AccountCacheState
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

private const val IMPORTED_KEY = "storage.imported"

/** Storage sync for the signed-in account: the switch, detection, the kinds and the engine. */
internal class StorageSync(
    httpClient: HttpClientAPI,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val region: RegionSetting,
    private val libraryPlaylist: LibraryPlaylistSetting,
    private val scope: CoroutineScope,
    private val now: () -> Long = { epochMillis() },
    random: Random = Random.Default,
    private val sessionProvider: suspend () -> PipedAccount?,
) {
    private val http = StorageHttp(httpClient)
    val setting = StorageSyncSetting(store)
    val support = StorageSupport(http, store, now)
    val mode = StorageMode(setting, support, sessionProvider)
    private val owner = SyncOwner(store, random)
    private val marks = DirtyMarks(store)
    val libraryKind = SyncedLibrary(LwwStore(store, "lib"), library)
    val settingsKind = SyncedSettings(LwwStore(store, "set"), region, libraryPlaylist)
    private val kinds: List<SyncKind> = listOf(libraryKind, settingsKind)
    private val engine = SyncEngine(http, store, owner, marks, kinds, now)
    private var flushJob: Job? = null

    init {
        marks.onMark = { scheduleFlush() }
    }

    suspend fun activeAccount(): PipedAccount? {
        val account = sessionProvider() ?: return null
        return account.takeIf { mode.active(it) }
    }

    suspend fun prepare(account: PipedAccount): Boolean {
        support.checkIfStale(account)
        return mode.active(account)
    }

    /** One account refresh in storage mode: import once, pull, then push. */
    suspend fun sync(account: PipedAccount, previous: AccountCacheState?) {
        owner.ensure(account)
        importOnce(account, previous)
        engine.pull(account)
        engine.flush(account)
    }

    /** Records saves or unsaves when storage is active. False means the caller takes the playlist path. */
    suspend fun librarySaved(kind: SavedKind, ids: List<String>, saved: Boolean): Boolean {
        val account = activeAccount() ?: return false
        owner.ensure(account)
        libraryKind.record(kind, ids, saved, now())
        marks.mark(libraryKind.suffix)
        return true
    }

    suspend fun savedPlaylistsChanged(ids: List<String>, saved: Boolean) {
        val account = activeAccount() ?: return
        owner.ensure(account)
        libraryKind.recordPlaylists(ids, saved, now())
        marks.mark(libraryKind.suffix)
    }

    suspend fun settingChanged(key: String) {
        if (key !in SYNCED_SETTING_KEYS) return
        val account = activeAccount() ?: return
        owner.ensure(account)
        settingsKind.record(key, now())
        marks.mark(settingsKind.suffix)
    }

    private suspend fun importOnce(account: PipedAccount, previous: AccountCacheState?) {
        val stamp = accountStamp(account)
        if ((store.get(IMPORTED_KEY) as? JsonPrimitive)?.contentOrNull == stamp) return
        val snapshot = previous?.takeIf { sameAccount(it, account) }
        val extra = mapOf(
            SavedKind.TRACK to snapshot?.savedTracks.orEmpty(),
            SavedKind.ALBUM to snapshot?.savedAlbums.orEmpty(),
            SavedKind.ARTIST to snapshot?.savedArtists.orEmpty(),
        )
        libraryKind.importLocal(extra)
        settingsKind.importLocal()
        kinds.forEach { marks.mark(it.suffix) }
        store.put(IMPORTED_KEY, JsonPrimitive(stamp))
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            val account = activeAccount() ?: return@launch
            orNull("storage flush") { engine.flush(account) }
        }
    }
}
