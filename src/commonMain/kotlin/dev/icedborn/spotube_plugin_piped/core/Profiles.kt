package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal const val SIGNED_OUT_PROFILE = "out"

/** The account stamp, or `out`, whose profile reads the bare keys. Written once, by the schema 4 migration. */
internal const val LEGACY_PROFILE_KEY = "profiles.legacy"

private val profileLog = Logger("PipedProfiles")

private val PROFILE_KEYS = setOf("piped.library", "piped.playlists", "history.tracks", "history.pending", "history.seen")

/** Keys of one account's storage sync. They live in that account's profile. */
internal val SYNC_STATE_PREFIXES = listOf(
    "storage.own:", "storage.dirty", "storage.remote:", "storage.etag:", "storage.registry", "storage.registered",
    "storage.versions", "storage.manifest.dirty", "storage.imported", "storage.pl.touched",
    "storage.hist.cursor", "storage.dict.pending",
)

internal fun isProfileKey(key: String): Boolean = key in PROFILE_KEYS || SYNC_STATE_PREFIXES.any { key.startsWith(it) }

/** 64-bit FNV-1a of [stamp], as 16 hex digits. */
internal fun profileId(stamp: String): String {
    var hash = 0xcbf29ce484222325UL
    for (byte in stamp.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3UL
    }
    return hash.toString(16).padStart(16, '0')
}

/** One account's, or the signed-out state's, copy of the account-bound keys. */
internal class Profile(val id: String, val bare: Boolean) {
    fun key(base: String): String = if (bare) base else "p$id.$base"
}

/** The profile that owns the bare keys whatever the session, for the migration. */
internal val BARE_PROFILE = Profile("", bare = true)

/** Pins the profile for the coroutine and child coroutines launched on its scope. */
internal class ProfileElement(val profile: Profile) : AbstractCoroutineContextElement(ProfileElement) {
    companion object Key : CoroutineContext.Key<ProfileElement>
}

internal class Profiles(private val store: EntityStore, private val session: AccountSession) {
    private var warned = false

    suspend fun active(): Profile = of(session.load())

    suspend fun of(account: AccountIdentity?): Profile {
        val owner = account?.let(::accountStamp) ?: SIGNED_OUT_PROFILE
        val id = if (account == null) SIGNED_OUT_PROFILE else profileId(owner)
        return Profile(id, bare = legacyOwner() == owner)
    }

    suspend fun setLegacyOwner(owner: String): Boolean = store.put(LEGACY_PROFILE_KEY, JsonPrimitive(owner))

    // An unreadable value counts as unset, so no profile reads the bare keys and nothing is deleted.
    suspend fun legacyOwner(): String? {
        val raw = store.get(LEGACY_PROFILE_KEY) ?: return null
        val owner = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (owner == null && !warned) {
            warned = true
            profileLog.w { "$LEGACY_PROFILE_KEY is unreadable, the library from before profiles stays hidden" }
        }
        return owner
    }
}

/** Removes the bare storage sync keys. Prefixed copies of other profiles stay. */
internal suspend fun EntityStore.dropSyncState() = inProfile(BARE_PROFILE) {
    val stale = keys().filter { key -> SYNC_STATE_PREFIXES.any { key.startsWith(it) } }
    stale.forEach { remove(it) }
}
