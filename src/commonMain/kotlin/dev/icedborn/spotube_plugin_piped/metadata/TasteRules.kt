package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.store.SKIP_KEEP_MS
import dev.icedborn.spotube_plugin_piped.store.Skip
import kotlin.math.pow

private const val SKIP_FACTOR = 0.5

/** 1 for a skip at [now], falling linearly to 0 at [SKIP_KEEP_MS]. */
internal fun skipWeight(at: Long, now: Long): Double = (1.0 - (now - at).toDouble() / SKIP_KEEP_MS).coerceIn(0.0, 1.0)

/** The factor each skipped artist's scores take: [SKIP_FACTOR] to the power of its summed skip weights. */
internal fun artistPenalties(skips: List<Skip>, now: Long): Map<String, Double> {
    val sums = HashMap<String, Double>()
    for (skip in skips) {
        val weight = skipWeight(skip.at, now)
        if (weight > 0) sums[skip.artist] = (sums[skip.artist] ?: 0.0) + weight
    }
    return sums.mapValues { SKIP_FACTOR.pow(it.value) }
}

internal fun blockedSongs(skips: List<Skip>, now: Long): Set<String> =
    skips.filter { now - it.at < SKIP_KEEP_MS }.mapTo(HashSet()) { it.id }
