package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.client.YOUTUBE_VIDEO_ID
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.store.AccountCacheState
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.Skip
import dev.icedborn.spotube_plugin_piped.store.SkipLog
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

private const val IMPORTED_KEY = "storage.imported"
private const val HISTORY_IMPORTED_KEY = "storage.imported.history"
private const val TASTE_IMPORTED_KEY = "storage.imported.taste"
private val storageLog = Logger("PipedStorageSync")

/** Storage sync for the signed-in account: the switch, detection, the kinds and the engine. */
internal class StorageSync(
    httpClient: HttpClientAPI,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val history: PlayHistory,
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
    val playlistsKind = SyncedPlaylists(LwwStore(store, "pl"), store, library, marks)
    val dictionary = SyncedDictionary(store, marks, now)
    val historyKind = SyncedHistory(store, history, marks)
    val tasteKind = SyncedTaste(LwwStore(store, TASTE_SUFFIX), now)

    // The dictionary comes before the history, so the rebuild finds the metadata the pull just brought.
    private val kinds: List<SyncKind> = listOf(libraryKind, settingsKind, playlistsKind, dictionary, historyKind, tasteKind)
    private val engine = SyncEngine(http, store, owner, marks, kinds, now)
    private var flushJob: Job? = null
    private var markedDuringFlush = false

    init {
        marks.onMark = { scheduleFlush() }
    }

    suspend fun activeAccount(): PipedAccount? {
        val account = sessionProvider() ?: return null
        return account.takeIf { mode.active(it) }
    }

    /** The account whose changes go into storage. With the switch off after an earlier import they are recorded too,
     * and go out once it is back on. */
    private suspend fun recordingAccount(): PipedAccount? {
        val account = sessionProvider() ?: return null
        if (mode.active(account)) return account
        val imported = store.forAccount(account) { store.get(IMPORTED_KEY) }
        return account.takeIf { (imported as? JsonPrimitive)?.contentOrNull == accountStamp(account) }
    }

    suspend fun prepare(account: PipedAccount): Boolean {
        support.checkIfStale(account)
        return mode.active(account)
    }

    /** One account refresh in storage mode: import once, pull, then push. */
    suspend fun sync(account: PipedAccount, previous: AccountCacheState?) = store.forAccount(account) {
        importOnce(account, previous)
        importHistoryOnce(account)
        importTasteOnce(account)
        engine.pull(account)
        engine.flush(account)
    }

    /** Records saves or unsaves for storage. False means storage is off and the caller takes the playlist path too. */
    suspend fun librarySaved(kind: SavedKind, ids: List<String>, saved: Boolean): Boolean {
        val account = recordingAccount() ?: return false
        store.forAccount(account) {
            libraryKind.record(kind, ids, saved, now())
            if (saved && kind == SavedKind.TRACK) dictionary.note(ids)
            marks.mark(libraryKind.suffix)
        }
        return mode.active(account)
    }

    suspend fun savedPlaylistsChanged(ids: List<String>, saved: Boolean) {
        val account = recordingAccount() ?: return
        store.forAccount(account) {
            libraryKind.recordPlaylists(ids, saved, now())
            marks.mark(libraryKind.suffix)
            val touchedLocal = playlistsKind.touchListing(ids, now())
            if (touchedLocal) marks.mark(playlistsKind.suffix)
        }
    }

    suspend fun settingChanged(key: String) {
        if (key !in SYNCED_SETTING_KEYS) return
        val account = recordingAccount() ?: return
        store.forAccount(account) {
            settingsKind.record(key, now())
            marks.mark(settingsKind.suffix)
        }
    }

    suspend fun playlistTouched(localId: String): Boolean {
        val account = recordingAccount() ?: return false
        store.forAccount(account) {
            playlistsKind.touch(localId, now())
            marks.mark(playlistsKind.suffix)
        }
        return mode.active(account)
    }

    /** Records a scrobbled play when storage is active. False means the caller takes the history log path. */
    suspend fun played(track: MetadataTrack): Boolean {
        val account = activeAccount() ?: return false
        if (!YOUTUBE_VIDEO_ID.matches(track.id)) return true
        val imported = historyImported(account)
        store.forAccount(account) {
            store.rememberTrack(track)
            dictionary.note(listOf(track.id))
            // The import either turns the local counts into the baseline or restores files without this play.
            if (imported) historyKind.record(track.id, now()) else historyKind.hold(track.id, now())
        }
        return true
    }

    suspend fun historyImported(account: PipedAccount): Boolean = store.forAccount(account) {
        (store.get(HISTORY_IMPORTED_KEY) as? JsonPrimitive)?.contentOrNull == accountStamp(account)
    }

    suspend fun device(): String = owner.device()

    /** The synced plays of every device of the active account. Empty when storage is not active. */
    suspend fun historyDocs(): List<SyncDoc> {
        val account = activeAccount() ?: return emptyList()
        return store.forAccount(account) { engine.cachedDocs(historyKind) }
    }

    /** Records skips when storage is active. The caller has already written them to the local log. */
    suspend fun skipped(skips: List<Skip>) {
        val account = activeAccount() ?: return
        store.forAccount(account) {
            val recorded = tasteKind.record(skips)
            if (recorded) marks.mark(tasteKind.suffix)
        }
    }

    /** The synced skips of every device of the active account. Empty when storage is not active. */
    suspend fun tasteDocs(): List<SyncDoc> {
        val account = activeAccount() ?: return emptyList()
        return store.forAccount(account) { engine.cachedDocs(tasteKind) }
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
        playlistsKind.importLocal()
        listOf(libraryKind, settingsKind, playlistsKind).forEach { marks.mark(it.suffix) }
        store.put(IMPORTED_KEY, JsonPrimitive(stamp))
    }

    /** The local counts become this device's baseline, and the tracks behind them go into the dictionary. A device
     * that published plays for the account before gets its own files back instead, since the local counts hold them. */
    private suspend fun importHistoryOnce(account: PipedAccount) {
        // A play held while an earlier import was finishing is counted at the next refresh.
        if (historyImported(account)) {
            historyKind.releaseHeld(count = true)
            return
        }
        val published = engine.publishedOwn(account, historyKind.files.map { it.suffix })
        if (published == null) {
            storageLog.w { "own history files could not be read, the import waits for the next refresh" }
            return
        }
        if (published.isNotEmpty()) {
            val restored = historyKind.restore(published)
            if (!restored) {
                storageLog.w { "own history files could not be restored, the import waits for the next refresh" }
                return
            }
        }
        val local = history.all()
        store.rememberTracks(local.map { it.track })
        val saved = library.savedTracks()
        dictionary.note(local.map { it.track.id } + saved)
        if (published.isEmpty()) historyKind.importBaseline()
        val stamped = store.put(HISTORY_IMPORTED_KEY, JsonPrimitive(accountStamp(account)))
        if (stamped) historyKind.releaseHeld(count = published.isNotEmpty())
    }

    /** Copies this profile's skips into the own taste file once per account, so other devices see them too. */
    private suspend fun importTasteOnce(account: PipedAccount) {
        val stamp = accountStamp(account)
        if ((store.get(TASTE_IMPORTED_KEY) as? JsonPrimitive)?.contentOrNull == stamp) return
        val local = SkipLog(store).all()
        if (local.isNotEmpty()) {
            val imported = tasteKind.importLocal(local)
            if (!imported) return
            marks.mark(tasteKind.suffix)
        }
        store.put(TASTE_IMPORTED_KEY, JsonPrimitive(stamp))
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) {
            markedDuringFlush = true
            return
        }
        flushJob = scope.launch {
            do {
                markedDuringFlush = false
                delay(FLUSH_DELAY_MS)
                val account = activeAccount() ?: return@launch
                val left = store.forAccount(account) {
                    orNull("storage flush") { engine.flush(account) }
                    marks.all().isNotEmpty()
                }
                // A mark during the delay is pushed by this flush. One during the uploads stays, and gets another flush.
            } while (markedDuringFlush && left)
        }
    }
}
