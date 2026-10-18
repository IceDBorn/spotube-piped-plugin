package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.store.Play
import dev.icedborn.spotube_plugin_piped.store.PlaySnapshot
import dev.icedborn.spotube_plugin_piped.store.PlayTimes

/** The synced history of every device: each event is a play, and [playCounts] adds the folded and baseline counts.
 * This device's plays come from its own log, which also holds the ones from before the import. */
internal class StoragePlayTimes(
    private val local: PlayTimes? = null,
    private val self: suspend () -> String? = { null },
    private val docs: suspend () -> List<SyncDoc>,
) : PlayTimes {
    override suspend fun snapshot(): PlaySnapshot {
        val all = docs()
        val device = self()
        val own = local?.snapshot()?.plays.orEmpty()
        // Own events older than the log, which keeps only the newest plays, still count.
        val logStart = own.minOfOrNull { it.at }
        val synced = all.flatMap { doc ->
            val fromLog = doc.device == device && local != null
            doc.entries.mapNotNull { entry ->
                val id = eventVideoId(entry.key) ?: return@mapNotNull null
                if (fromLog && logStart != null && entry.at >= logStart) return@mapNotNull null
                Play(id, entry.at)
            }
        }
        return PlaySnapshot((own + synced).sortedByDescending { it.at }, playCounts(all))
    }
}

/** The storage plays while storage is active for the session, this device's log otherwise. */
internal class PlaySource(private val local: PlayTimes, private val sync: StorageSync?) {
    suspend fun current(): PlayTimes {
        val storage = sync ?: return local
        if (storage.activeAccount() == null) return local
        return StoragePlayTimes(local, { storage.device() }) { storage.historyDocs() }
    }
}
