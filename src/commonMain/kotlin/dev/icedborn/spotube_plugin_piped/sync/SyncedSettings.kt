package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylist
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val REGION = "piped.region"
private const val LIBRARY_PLAYLIST = "piped.library.placeholder"
internal val SYNCED_SETTING_KEYS = listOf(REGION, LIBRARY_PLAYLIST)

/** Settings that follow the account. Instance, session, update channel and theme stay per device. */
internal class SyncedSettings(
    override val own: LwwStore,
    private val region: RegionSetting,
    private val libraryPlaylist: LibraryPlaylistSetting,
) : SyncKind {
    override val suffix: String get() = own.suffix

    suspend fun record(key: String, at: Long) {
        own.record(listOf(LwwEntry(key, at, JsonPrimitive(current(key)))))
    }

    suspend fun importLocal() {
        own.recordIfAbsent(SYNCED_SETTING_KEYS.map { LwwEntry(it, 0, JsonPrimitive(current(it))) })
    }

    private suspend fun current(key: String): String = when (key) {
        REGION -> region.stored()
        else -> libraryPlaylist.stored().name
    }

    override suspend fun apply(winners: Map<String, Winner>, self: String) {
        for (key in SYNCED_SETTING_KEYS) {
            // An imported value (at 0) is no choice the user made, so it never overrides another device.
            val winner = winners[key]?.takeIf { it.device != self && it.entry.at > 0 && !it.entry.removed } ?: continue
            val value = (winner.entry.value as? JsonPrimitive)?.contentOrNull ?: continue
            if (value == current(key)) continue
            when (key) {
                REGION -> region.set(value)
                else -> LibraryPlaylist.entries.firstOrNull { it.name == value }?.let { libraryPlaylist.set(it) }
            }
        }
    }
}
