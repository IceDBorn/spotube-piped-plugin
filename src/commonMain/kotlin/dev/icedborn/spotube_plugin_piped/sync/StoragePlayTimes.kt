package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.store.Play
import dev.icedborn.spotube_plugin_piped.store.PlaySnapshot
import dev.icedborn.spotube_plugin_piped.store.PlayTimes

/** The synced history of every device: each event is a play, and [playCounts] adds the folded and baseline counts. */
internal class StoragePlayTimes(private val docs: suspend () -> List<SyncDoc>) : PlayTimes {
    override suspend fun snapshot(): PlaySnapshot {
        val all = docs()
        val plays = all.flatMap { doc -> doc.entries.mapNotNull { entry -> eventVideoId(entry.key)?.let { Play(it, entry.at) } } }
        return PlaySnapshot(plays.sortedByDescending { it.at }, playCounts(all))
    }
}

/** The storage plays while storage is active for the session, this device's log otherwise. */
internal class PlaySource(private val local: PlayTimes, private val sync: StorageSync?) {
    suspend fun current(): PlayTimes {
        val storage = sync ?: return local
        if (storage.activeAccount() == null) return local
        return StoragePlayTimes { storage.historyDocs() }
    }
}
