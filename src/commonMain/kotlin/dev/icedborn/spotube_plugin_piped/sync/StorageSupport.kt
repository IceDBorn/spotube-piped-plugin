package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.client.StorageStat
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.AccountIdentity
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.core.sameAccount
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val STORAGE_SUPPORT_KEY = "storage.support"
internal const val STORAGE_SYNC_KEY = "piped.storage.sync"
internal const val SUPPORT_RECHECK_MS = 24 * 3_600_000L

@Serializable
internal data class StorageVerdict(
    override val instance: String,
    override val username: String,
    val supported: Boolean,
    val checkedAt: Long,
) : AccountIdentity

internal enum class StorageState { ACTIVE, OFF, SIGNED_OUT, UNSUPPORTED, UNCHECKED }

/** Whether the account's instance serves /storage. A check that proves nothing keeps the stored verdict. */
internal class StorageSupport(
    private val http: StorageHttp,
    private val store: EntityStore,
    private val now: () -> Long = { epochMillis() },
) {
    suspend fun verdict(account: PipedAccount): StorageVerdict? =
        store.getDecoded(STORAGE_SUPPORT_KEY, StorageVerdict.serializer())?.takeIf { sameAccount(it, account) }

    suspend fun checkIfStale(account: PipedAccount) {
        val last = verdict(account)
        if (last != null && now() - last.checkedAt < SUPPORT_RECHECK_MS) return
        check(account)
    }

    suspend fun check(account: PipedAccount): Boolean? {
        val supported = when (http.s3Enabled(account.instance)) {
            null -> return null

            false -> false

            true -> when (http.stat(account, REGISTRY_FILE)) {
                null -> return null
                StorageStat.Unsupported -> false
                else -> true
            }
        }
        val verdict = StorageVerdict(account.instance, account.username, supported, now())
        store.put(STORAGE_SUPPORT_KEY, json.encodeToJsonElement(StorageVerdict.serializer(), verdict))
        return supported
    }
}

/** The "Sync through Piped storage" switch. Absent means on. */
internal class StorageSyncSetting(private val store: EntityStore) {
    suspend fun enabled(): Boolean = (store.get(STORAGE_SYNC_KEY) as? JsonPrimitive)?.contentOrNull != "OFF"

    suspend fun set(enabled: Boolean) {
        if (enabled) store.remove(STORAGE_SYNC_KEY) else store.put(STORAGE_SYNC_KEY, JsonPrimitive("OFF"))
    }
}

internal class StorageMode(
    private val setting: StorageSyncSetting,
    private val support: StorageSupport,
    private val sessionProvider: suspend () -> PipedAccount?,
) {
    suspend fun state(): StorageState {
        if (!setting.enabled()) return StorageState.OFF
        val account = sessionProvider() ?: return StorageState.SIGNED_OUT
        val verdict = support.verdict(account) ?: return StorageState.UNCHECKED
        return if (verdict.supported) StorageState.ACTIVE else StorageState.UNSUPPORTED
    }

    suspend fun active(account: PipedAccount): Boolean {
        val session = sessionProvider() ?: return false
        return sameAccount(session, account) && setting.enabled() && support.verdict(account)?.supported == true
    }
}
