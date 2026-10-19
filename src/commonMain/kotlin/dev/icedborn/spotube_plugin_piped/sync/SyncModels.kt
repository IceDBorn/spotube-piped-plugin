package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.client.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

internal const val REGISTRY_FILE = "spdevices"
internal const val MAX_FILE_BYTES = 500_000
private const val SYNC_FORMAT = 1
private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

internal fun manifestFile(device: String) = "sp${device}m"

internal fun dataFile(device: String, suffix: String) = "sp$device$suffix"

internal fun newDeviceId(random: Random = Random.Default): String = (1..8).map {
    ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]
}.joinToString("")

/** One key's state as one device last set it. A null value removes the key. */
@Serializable
internal data class LwwEntry(
    @SerialName("k") val key: String,
    @SerialName("a") val at: Long,
    @SerialName("v") val value: JsonElement? = null,
) {
    val removed: Boolean get() = value == null || value is JsonNull
}

@Serializable
internal data class SyncDoc(val v: Int = SYNC_FORMAT, val device: String, val entries: List<LwwEntry>)

@Serializable
internal data class RegisteredDevice(val id: String, val since: Long)

@Serializable
internal data class DeviceRegistry(val v: Int = SYNC_FORMAT, val devices: List<RegisteredDevice>)

@Serializable
internal data class DeviceManifest(val v: Int = SYNC_FORMAT, val device: String, val versions: Map<String, Int>)

internal data class Winner(val entry: LwwEntry, val device: String)

internal fun mergeLww(docs: List<SyncDoc>): Map<String, Winner> {
    val winners = HashMap<String, Winner>()
    for (doc in docs) {
        for (entry in doc.entries) {
            val current = winners[entry.key]
            val newer = current == null || entry.at > current.entry.at || (entry.at == current.entry.at && doc.device > current.device)
            if (newer) winners[entry.key] = Winner(entry, doc.device)
        }
    }
    return winners
}

internal fun encodeDoc(doc: SyncDoc): String = json.encodeToString(SyncDoc.serializer(), doc)

private fun bytes(doc: SyncDoc) = encodeDoc(doc).encodeToByteArray().size

/** Drops removals, oldest first, until [doc] fits [cap]. Null when the live entries alone do not fit. */
internal fun fitToCap(doc: SyncDoc, cap: Int = MAX_FILE_BYTES): SyncDoc? {
    var current = doc
    val removals = doc.entries.filter { it.removed }.sortedBy { it.at }.toMutableList()
    while (bytes(current) > cap) {
        if (removals.isEmpty()) return null
        val drop = removals.take(maxOf(1, removals.size / 4))
        removals.subList(0, drop.size).clear()
        val dropped = drop.toHashSet()
        current = current.copy(entries = current.entries.filterNot { it in dropped })
    }
    return current
}

private fun <T> decodeVersioned(body: String?, requiredKey: String, serializer: KSerializer<T>): T? {
    val element = body?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
    val obj = element as? JsonObject ?: return null
    if (requiredKey !in obj) return null
    val version = obj["v"]?.let { (it as? JsonPrimitive)?.intOrNull ?: return null } ?: SYNC_FORMAT
    if (version != SYNC_FORMAT) return null
    return runCatching { json.decodeFromJsonElement(serializer, obj) }.getOrNull()
}

internal fun decodeSyncDoc(body: String?): SyncDoc? = decodeVersioned(body, "entries", SyncDoc.serializer())

internal fun decodeRegistry(body: String?): DeviceRegistry? = decodeVersioned(body, "devices", DeviceRegistry.serializer())

internal fun decodeManifest(body: String?): DeviceManifest? = decodeVersioned(body, "versions", DeviceManifest.serializer())
