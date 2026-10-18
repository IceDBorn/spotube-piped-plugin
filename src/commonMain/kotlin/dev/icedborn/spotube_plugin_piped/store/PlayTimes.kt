package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.sync.PlayCount

/** The plays Home rows read: each play with its time, newest first, and per track totals that may reach further back. */
internal class PlaySnapshot(val plays: List<Play>, val totals: Map<String, PlayCount>)

internal fun interface PlayTimes {
    suspend fun snapshot(): PlaySnapshot
}

/** This device's [PlayLog]. Its totals count the logged plays only. */
internal class LocalPlayTimes(private val log: PlayLog) : PlayTimes {
    override suspend fun snapshot(): PlaySnapshot {
        val plays = log.all()
        val totals = plays.groupBy { it.id }.mapValues { (_, own) -> PlayCount(own.size, own.maxOf { it.at }) }
        return PlaySnapshot(plays, totals)
    }
}
