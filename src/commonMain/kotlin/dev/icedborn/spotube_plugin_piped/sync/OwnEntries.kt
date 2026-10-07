package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

private const val DEVICE_KEY = "storage.device"
private const val OWNER_KEY = "storage.owner"
private const val OWN_PREFIX = "storage.own:"
private const val DIRTY_KEY = "storage.dirty"

/** Keys that belong to one account's storage. A different signed-in account clears them. */
internal val ACCOUNT_SYNC_PREFIXES = listOf(
    OWN_PREFIX, DIRTY_KEY, "storage.remote:", "storage.etag:", "storage.registry", "storage.registered",
    "storage.versions", "storage.manifest.dirty", "storage.imported", "storage.pl.touched",
)

internal class SyncOwner(private val store: EntityStore, private val random: Random = Random.Default) {
    private val lock = Mutex()

    suspend fun device(): String = lock.withLock {
        val stored = (store.get(DEVICE_KEY) as? JsonPrimitive)?.contentOrNull
        if (stored != null) return@withLock stored
        val created = newDeviceId(random)
        store.put(DEVICE_KEY, JsonPrimitive(created))
        created
    }

    suspend fun ensure(account: PipedAccount) {
        val stamp = accountStamp(account)
        if ((store.get(OWNER_KEY) as? JsonPrimitive)?.contentOrNull == stamp) return
        val stale = store.keys().filter { key -> ACCOUNT_SYNC_PREFIXES.any { key.startsWith(it) } }
        stale.forEach { store.remove(it) }
        store.put(OWNER_KEY, JsonPrimitive(stamp))
    }
}

/** The entries this device authored for one kind, one per key. */
internal class LwwStore(private val store: EntityStore, val suffix: String) {
    private val key = OWN_PREFIX + suffix
    private val lock = Mutex()
    private val serializer = ListSerializer(LwwEntry.serializer())

    suspend fun entries(): List<LwwEntry> = store.getDecoded(key, serializer).orEmpty()

    suspend fun record(updates: List<LwwEntry>): Boolean = write(updates, replace = true)

    suspend fun recordIfAbsent(updates: List<LwwEntry>): Boolean = write(updates, replace = false)

    private suspend fun write(updates: List<LwwEntry>, replace: Boolean): Boolean = lock.withLock {
        val current = entries()
        val byKey = LinkedHashMap<String, LwwEntry>()
        current.forEach { byKey[it.key] = it }
        updates.forEach { if (replace || it.key !in byKey) byKey[it.key] = it }
        val encoded = json.encodeToJsonElement(serializer, byKey.values.toList())
        val ok = store.put(key, encoded)
        ok
    }
}

internal class DirtyMarks(private val store: EntityStore) {
    var onMark: () -> Unit = {}
    private val generations = HashMap<String, Int>()
    private val lock = Mutex()
    private val serializer = ListSerializer(String.serializer())

    suspend fun all(): List<String> = store.getDecoded(DIRTY_KEY, serializer).orEmpty()

    fun generation(suffix: String): Int = generations[suffix] ?: 0

    suspend fun mark(suffix: String) {
        lock.withLock {
            generations[suffix] = generation(suffix) + 1
            val current = all()
            if (suffix !in current) {
                val encoded = json.encodeToJsonElement(serializer, current + suffix)
                store.put(DIRTY_KEY, encoded)
            }
        }
        onMark()
    }

    /** Clears [suffix] only when no mark came after [generation] was read. */
    suspend fun clear(suffix: String, generation: Int) = lock.withLock {
        if (generation(suffix) != generation) return@withLock
        val current = all()
        val remaining = current - suffix
        val encoded = json.encodeToJsonElement(serializer, remaining)
        store.put(DIRTY_KEY, encoded)
    }
}

internal interface SyncKind {
    val suffix: String
    val own: LwwStore

    suspend fun beforeFlush() {}

    /** Applies the merged winners of every device to the local state. */
    suspend fun apply(winners: Map<String, Winner>, self: String)
}
