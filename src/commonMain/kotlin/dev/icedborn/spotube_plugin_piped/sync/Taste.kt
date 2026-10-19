package dev.icedborn.spotube_plugin_piped.sync

import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.store.Skip
import dev.icedborn.spotube_plugin_piped.store.SkipLog
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger

private val tasteLog = Logger("PipedTaste")

/** Endless playback skips. Every skip goes to the local log, and to the taste file while storage is active. */
internal class Taste(private val log: SkipLog, private val sync: StorageSync?, private val now: () -> Long = { epochMillis() }) {

    /** Every device's skips while storage is active, plus the log, which holds the skips from before the import. */
    suspend fun skips(): List<Skip> {
        val local = log.all()
        val storage = sync ?: return local
        if (storage.activeAccount() == null) return local
        val docs = storage.tasteDocs()
        val synced = docs.flatMap { doc -> doc.entries.mapNotNull(::skipOf) }
        return (synced + local).distinctBy { it.id to it.at }
    }

    suspend fun record(skips: List<Skip>) {
        if (skips.isEmpty()) return
        val logged = log.append(skips, now())
        if (!logged) tasteLog.w { "skips could not be stored" }
        orNull("taste sync") { sync?.skipped(skips) }
    }
}
