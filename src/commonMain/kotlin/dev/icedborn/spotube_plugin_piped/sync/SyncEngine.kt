package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.StorageHttp
import dev.icedborn.spotube_plugin_piped.client.StoragePut
import dev.icedborn.spotube_plugin_piped.client.StorageStat
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal const val FLUSH_DELAY_MS = 30_000L
private const val REGISTRY_ATTEMPTS = 3
private const val REGISTERED_KEY = "storage.registered"
private const val REGISTRY_KEY = "storage.registry"
private const val ETAG_PREFIX = "storage.etag:"
private const val REMOTE_PREFIX = "storage.remote:"
private const val VERSIONS_KEY = "storage.versions"
private const val MANIFEST_DIRTY_KEY = "storage.manifest.dirty"
private val syncLog = Logger("PipedSync")

@Serializable
internal data class RemoteDoc(val version: Int, val doc: SyncDoc)

/** Moves own entries to this device's files and other devices' files into the local merge. */
internal class SyncEngine(
    private val http: StorageHttp,
    private val store: EntityStore,
    private val owner: SyncOwner,
    private val marks: DirtyMarks,
    private val kinds: List<SyncKind>,
    private val now: () -> Long = { epochMillis() },
) {
    private val flushLock = Mutex()
    private val versionsSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val files: List<LwwStore> get() = kinds.flatMap { it.files }

    // Kinds applied in this process and remote files stored since, so a pull with nothing new skips the apply.
    private val appliedKinds = HashSet<String>()
    private val movedSuffixes = HashSet<String>()
    private var appliedScope: List<String>? = null

    suspend fun ensureRegistered(account: PipedAccount): Boolean {
        val registeredElem = store.get(REGISTERED_KEY)
        if ((registeredElem as? JsonPrimitive)?.booleanOrNull == true) return true
        val self = owner.device()
        repeat(REGISTRY_ATTEMPTS) {
            val res = registerOnce(account, self)
            when (res) {
                StoragePut.OK -> {
                    store.put(REGISTERED_KEY, JsonPrimitive(true))
                    return true
                }

                StoragePut.CONFLICT -> Unit

                else -> return false
            }
        }
        return false
    }

    private suspend fun registerOnce(account: PipedAccount, self: String): StoragePut {
        val stat = http.stat(account, REGISTRY_FILE) ?: return StoragePut.FAILED
        val (registry, etag) = when (stat) {
            StorageStat.Unsupported -> return StoragePut.UNSUPPORTED

            StorageStat.Missing -> DeviceRegistry(devices = emptyList()) to null

            is StorageStat.Exists -> {
                val body = http.get(account, REGISTRY_FILE) ?: return StoragePut.FAILED
                // A registry that does not decode is never overwritten, or every other device would drop out.
                val decoded = decodeRegistry(body) ?: return StoragePut.FAILED
                decoded to stat.etag
            }
        }
        if (registry.devices.any { it.id == self }) return StoragePut.OK
        val updated = registry.copy(devices = registry.devices + RegisteredDevice(self, now()))
        val encodedRegistry = json.encodeToString(DeviceRegistry.serializer(), updated)
        return http.put(account, REGISTRY_FILE, encodedRegistry, etag)
    }

    suspend fun flush(account: PipedAccount) {
        val registered = ensureRegistered(account)
        if (!registered) return
        flushLock.withLock { flushLocked(account) }
    }

    private suspend fun flushLocked(account: PipedAccount) {
        val self = owner.device()
        for (kind in kinds) {
            kind.beforeFlush()
        }
        val loadedVersions = loadVersions() ?: publishedVersions(account, self) ?: return
        val versions = loadedVersions.toMutableMap()
        val dirtySuffixes = marks.all().toSet()
        for (kind in kinds) {
            for ((index, storeFile) in kind.files.withIndex()) {
                if (storeFile.suffix !in dirtySuffixes) continue
                val sent = pushFile(account, self, storeFile, versions)
                if (!sent && index == 0 && kind.firstHoldsBack) break
            }
        }
        val dirtyElem = store.get(MANIFEST_DIRTY_KEY)
        if ((dirtyElem as? JsonPrimitive)?.booleanOrNull != true) return
        val manifest = json.encodeToString(DeviceManifest.serializer(), DeviceManifest(device = self, versions = versions))
        val mFile = manifestFile(self)
        val manifestOk = putOwn(account, mFile, manifest)
        if (manifestOk) store.remove(MANIFEST_DIRTY_KEY)
    }

    /** A file over the size cap is skipped and stays marked, like one whose put failed. False when it was not sent. */
    private suspend fun pushFile(account: PipedAccount, self: String, storeFile: LwwStore, versions: MutableMap<String, Int>): Boolean {
        val suffix = storeFile.suffix
        val generation = marks.generation(suffix)
        val entries = storeFile.entries()
        val doc = fitToCap(SyncDoc(device = self, entries = entries))
        if (doc == null) {
            syncLog.w { "storage file $suffix is over the size cap, not sent" }
            return false
        }
        val encodedDoc = encodeDoc(doc)
        val putOk = putOwn(account, dataFile(self, suffix), encodedDoc)
        if (!putOk) return false
        versions[suffix] = (versions[suffix] ?: 0) + 1
        val versionsJson = json.encodeToJsonElement(versionsSerializer, versions)
        store.put(VERSIONS_KEY, versionsJson)
        store.put(MANIFEST_DIRTY_KEY, JsonPrimitive(true))
        marks.clear(suffix, generation)
        return true
    }

    /** Only this device writes its files, so a conflict means an earlier put landed late. One fresh stat settles it. */
    private suspend fun putOwn(account: PipedAccount, file: String, body: String): Boolean {
        repeat(2) {
            val stat = http.stat(account, file) ?: return false
            val etag = when (stat) {
                StorageStat.Unsupported -> return false
                StorageStat.Missing -> null
                is StorageStat.Exists -> stat.etag
            }
            val putResult = http.put(account, file, body, etag)
            when (putResult) {
                StoragePut.OK -> return true
                StoragePut.CONFLICT -> Unit
                else -> return false
            }
        }
        return false
    }

    suspend fun pull(account: PipedAccount) {
        val self = owner.device()
        val registry = readRegistry(account) ?: return
        val others = registry.devices.map { it.id }.filter { it != self }
        for (device in others) {
            refreshDevice(account, device)
        }
        val scope = listOf(accountStamp(account)) + others
        if (scope != appliedScope) {
            appliedKinds.clear()
            appliedScope = scope
        }
        for (kind in kinds) {
            val suffixes = kind.files.map { it.suffix }.toSet()
            if (kind.suffix in appliedKinds && suffixes.none { it in movedSuffixes }) continue
            appliedKinds -= kind.suffix
            movedSuffixes -= suffixes
            val docs = docsOf(kind, self, others)
            kind.applyDocs(docs, self)
            appliedKinds += kind.suffix
        }
    }

    private suspend fun docsOf(kind: SyncKind, self: String, others: List<String>): List<SyncDoc> {
        val docs = mutableListOf<SyncDoc>()
        for (storeFile in kind.files) {
            val ownEntries = storeFile.entries()
            docs.add(SyncDoc(device = self, entries = ownEntries))
            for (device in others) {
                val remote = remoteDoc(device, storeFile.suffix)
                if (remote != null) docs.add(remote.doc)
            }
        }
        return docs
    }

    /** The own and cached remote documents of [kind], read from the store without a request. */
    suspend fun cachedDocs(kind: SyncKind): List<SyncDoc> {
        val self = owner.device()
        val registry = store.getDecoded(REGISTRY_KEY, DeviceRegistry.serializer())
        val others = registry?.devices.orEmpty().map { it.id }.filter { it != self }
        return docsOf(kind, self, others)
    }

    private suspend fun readRegistry(account: PipedAccount): DeviceRegistry? {
        val cached = store.getDecoded(REGISTRY_KEY, DeviceRegistry.serializer())
        val stat = http.stat(account, REGISTRY_FILE)
        val exists = stat as? StorageStat.Exists ?: return cached
        val storedEtag = etagOf(REGISTRY_FILE)
        if (exists.etag == storedEtag && cached != null) return cached
        val body = http.get(account, REGISTRY_FILE)
        val fresh = decodeRegistry(body) ?: return cached
        val encoded = json.encodeToJsonElement(DeviceRegistry.serializer(), fresh)
        store.put(REGISTRY_KEY, encoded)
        store.put(ETAG_PREFIX + REGISTRY_FILE, JsonPrimitive(exists.etag))
        return fresh
    }

    private suspend fun refreshDevice(account: PipedAccount, device: String) {
        val file = manifestFile(device)
        val stat = http.stat(account, file) as? StorageStat.Exists ?: return
        val storedEtag = etagOf(file)
        if (stat.etag == storedEtag) return
        val manifestBody = http.get(account, file)
        val manifest = decodeManifest(manifestBody) ?: return
        var complete = true
        for (storeFile in files) {
            val suffix = storeFile.suffix
            val version = manifest.versions[suffix] ?: continue
            val cachedDoc = remoteDoc(device, suffix)
            if (cachedDoc?.version == version) continue
            val docBody = http.get(account, dataFile(device, suffix))
            val doc = decodeSyncDoc(docBody)
            if (doc == null) {
                complete = false
                continue
            }
            val encodedRemote = json.encodeToJsonElement(RemoteDoc.serializer(), RemoteDoc(version, doc))
            val stored = store.put(remoteKey(device, suffix), encodedRemote)
            if (stored) movedSuffixes += suffix else complete = false
        }
        // The etag is kept only when every moved file was stored, so the next pull retries the rest.
        if (complete) store.put(ETAG_PREFIX + file, JsonPrimitive(stat.etag))
    }

    private fun remoteKey(device: String, suffix: String) = "$REMOTE_PREFIX$device:$suffix"

    private suspend fun remoteDoc(device: String, suffix: String): RemoteDoc? =
        store.getDecoded(remoteKey(device, suffix), RemoteDoc.serializer())

    private suspend fun etagOf(file: String): String? = (store.get(ETAG_PREFIX + file) as? JsonPrimitive)?.contentOrNull

    private suspend fun loadVersions(): Map<String, Int>? = store.getDecoded(VERSIONS_KEY, versionsSerializer)

    /** Versions start from the published manifest, so a reset counter never reuses a number other devices cached. */
    private suspend fun publishedVersions(account: PipedAccount, self: String): Map<String, Int>? {
        val file = manifestFile(self)
        val stat = http.stat(account, file) ?: return null
        if (stat !is StorageStat.Exists) return emptyMap()
        val body = http.get(account, file)
        return decodeManifest(body)?.versions
    }

    /** This device's published copies of the [suffixes] its manifest lists, or null when a read failed. */
    suspend fun publishedOwn(account: PipedAccount, suffixes: List<String>): Map<String, List<LwwEntry>>? {
        val self = owner.device()
        val versions = publishedVersions(account, self) ?: return null
        val published = LinkedHashMap<String, List<LwwEntry>>()
        for (suffix in suffixes.filter { it in versions }) {
            val body = http.get(account, dataFile(self, suffix))
            val doc = decodeSyncDoc(body) ?: return null
            published[suffix] = doc.entries
        }
        return published
    }
}
