package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.string
import dev.icedborn.spotube_plugin_piped.core.AccountSession
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** The copy binding, pending marker and bare-miss record of a locally created playlist. */
internal const val MIRROR_PLAYLIST_PREFIX = "mirror.playlist:"
internal const val MIRROR_PENDING_PREFIX = "mirror.pending:"
internal const val MIRROR_NAME_PREFIX = "mirror.playlistName:"
internal const val BARE_MISS_PREFIX = "binding.miss:"

internal const val SCHEMA_VERSION_KEY = "schema.version"
internal const val SCHEMA_VERSION = 3

/** Entries kept per cache prefix; the least recently used beyond the cap are deleted at start. */
internal val CACHE_CAPS = mapOf(
    "track:" to 5_000,
    "streams:" to 2_000,
    "list:" to 500,
    PLAYLIST_ROWS_PREFIX to 200,
    ALBUM_ROWS_PREFIX to 200,
    ALBUM_CHAIN_PREFIX to 500,
)

private val migrationLog = Logger("PipedMigration")

/** Rewrites storage written by older plugin versions into the current shapes, once, before any service reads it. */
internal class StorageMigration(
    private val http: AccountHttp,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val session: AccountSession,
    private val instanceSource: InstanceSource,
) {

    suspend fun run() {
        val version = store.get(SCHEMA_VERSION_KEY)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        // Each step moves the version once it and its writes succeed; a failed one stops, and runs again next start.
        for (step in version + 1..SCHEMA_VERSION) {
            val failures = store.writeFailures
            val done = orNull("storage migration to $step") { upgrade(step) } == true && store.writeFailures == failures
            if (!done || !store.put(SCHEMA_VERSION_KEY, JsonPrimitive(step))) break
        }
        orNull("playlist binding adoption") { adoptBareBindings() }
        // Account playlist rows serve those playlists offline, so they do not compete with public ones.
        val accountRows = store.cachedAccountState()?.playlists.orEmpty().map { PLAYLIST_ROWS_PREFIX + it.id }.toSet()
        orNull("orphan binding cleanup") { dropOrphanBindings() }
        orNull("cache eviction") { store.evict(CACHE_CAPS, keep = accountRows) }
        orNull("latch cleanup") { dropExpiredLatches() }
    }

    private suspend fun upgrade(to: Int): Boolean {
        when (to) {
            1 -> migrate()

            // Version 2 changed the row cache format; the caches refill from page 1.
            2 -> removeKeys { it.startsWith(PLAYLIST_ROWS_PREFIX) || it.startsWith(ALBUM_ROWS_PREFIX) }

            // Version 3 keys "no album" verdicts by video, in track-album entries.
            3 -> {
                removeKeys { it.startsWith("album.none:") }
                store.stampLegacyNoAlbumVerdicts()
            }
        }
        return true
    }

    private suspend fun removeKeys(matches: (String) -> Boolean) = store.keys().filter(matches).forEach { store.remove(it) }

    /** Adopts playlist bindings from before owner stamps. The signed-in account takes the ones its listing holds
     * and is recorded as not owning the rest, which stay for another account. Signed out or offline they wait. */
    suspend fun adoptBareBindings() {
        val account = session.load() ?: return
        val listed = http.listing(account)?.mapNotNull { it.string("id") }?.toHashSet() ?: return
        store.adoptBareBindings(account, listed, bindingKeys(library.storedPlaylists().map { it.id }))
    }

    private suspend fun migrate() {
        val account = session.load()
        val keys = store.keys()
        for (key in keys.filter { it.startsWith(ARTIST_REP_PREFIX) }) {
            val id = key.removePrefix(ARTIST_REP_PREFIX)
            val canonical = canonicalArtistId(id)
            if (canonical == id) continue
            val value = store.get(key)
            store.remove(key)
            if (value != null && store.get(ARTIST_REP_PREFIX + canonical) == null) store.put(ARTIST_REP_PREFIX + canonical, value)
        }
        for (key in keys.filter { it.startsWith("saved.owner:") }) {
            val stamp = store.get(key) as? JsonPrimitive ?: continue
            store.put(key, JsonArray(listOfNotNull(stamp.contentOrNull).map(::JsonPrimitive)))
        }
        val artists = library.savedArtists()
        val canonicalArtists = artists.map(::canonicalArtistId).distinct()
        if (canonicalArtists != artists) {
            library.removeArtists(artists)
            library.saveArtists(canonicalArtists)
        }
        migrateAccountState(account)
        library.storedPlaylists().filter { it.id.startsWith("piped-acct-") }.forEach { record ->
            library.deleteStoredPlaylist(record.id)
            library.removePlaylists(listOf(record.id))
        }
        migrationLog.i { "storage migrated to schema $SCHEMA_VERSION" }
    }

    /** A snapshot from before identity stamps is claimed by the session, else by the configured instance. */
    private suspend fun migrateAccountState(account: PipedAccount?) {
        val state = store.cachedAccountState() ?: return
        val canonicalArtists = state.savedArtists.map(::canonicalArtistId).distinct()
        val stamped = when {
            state.instance.isNotBlank() || state.username.isNotBlank() -> state
            account != null -> state.copy(instance = account.instance, username = account.username)
            else -> instanceSource.api()?.let { state.copy(instance = it) }
        }
        if (stamped == null) {
            store.removeAccountState()
        } else {
            store.cacheAccountState(stamped.copy(savedArtists = canonicalArtists))
        }
    }

    /** Drops the copy bindings of playlists that no longer exist locally. Every reader starts from a stored
     * playlist, so a binding without one is unreachable. It runs after adoption, which stamps the bare ones. */
    private suspend fun dropOrphanBindings() {
        // A corrupt playlist list would read as empty and take every binding with it, so it is left alone.
        val local = library.storedPlaylistsOrNull()?.map { it.id }?.toHashSet() ?: return
        for (key in store.keys()) {
            val id = key.removePrefixIf(MIRROR_PLAYLIST_PREFIX)
                ?: key.removePrefixIf(BARE_MISS_PREFIX + MIRROR_PLAYLIST_PREFIX)
                ?: key.removePrefixIf(MIRROR_PENDING_PREFIX)
                ?: key.removePrefixIf(MIRROR_NAME_PREFIX)
                ?: continue
            if (id !in local) store.remove(key)
        }
    }

    private fun String.removePrefixIf(prefix: String) = if (startsWith(prefix)) removePrefix(prefix) else null

    private suspend fun dropExpiredLatches() {
        val now = epochMillis()
        for (key in store.keys().filter { it.startsWith("unresolvable:") }) {
            val at = store.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (at == null || now - at >= RESOLUTION_RETRY_COOLDOWN_MS) store.remove(key)
        }
    }

    private companion object {
        const val ARTIST_REP_PREFIX = "saved.rep:artist:"
    }
}
