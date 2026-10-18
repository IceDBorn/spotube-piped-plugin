package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.store.Play
import dev.icedborn.spotube_plugin_piped.sync.PlayCount

internal const val DAY_MS = 24 * 60 * 60_000L
internal const val DAILY_MIXES = 3
internal const val MIX_TRACKS = 25
internal const val MIX_REST_MS = 6 * 60 * 60_000L
private const val RECENT_MS = 30 * DAY_MS
private const val FORGOTTEN_MS = 60 * DAY_MS
private const val FORGOTTEN_PLAYS = 5
private const val REPEAT_PLAYS = 2
private const val SESSION_GAP_MS = 30 * 60_000L
private const val SHARED_SESSIONS = 2
private const val GROUP_SIZE = 5
private const val WINDOW_HOURS = 4

/** The six local time windows, from midnight on. */
internal val TIME_WINDOWS = listOf("late night", "early morning", "morning", "afternoon", "evening", "night")

internal fun recentPlays(plays: List<Play>, now: Long): List<Play> = plays.filter { now - it.at < RECENT_MS }

/** Ids by play count, ties going to the newest play. */
internal fun rankedIds(plays: List<Play>, minPlays: Int = 1): List<String> = plays.groupBy { it.id }.entries
    .filter { it.value.size >= minPlays }
    .sortedWith(compareByDescending<Map.Entry<String, List<Play>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.at } })
    .map { it.key }

internal fun onRepeat(plays: List<Play>, now: Long): List<String> = rankedIds(recentPlays(plays, now), REPEAT_PLAYS)

internal fun forgottenFavorites(totals: Map<String, PlayCount>, now: Long): List<String> = totals.entries
    .filter { it.value.plays >= FORGOTTEN_PLAYS && now - it.value.lastAt > FORGOTTEN_MS }
    .sortedWith(compareByDescending<Map.Entry<String, PlayCount>> { it.value.plays }.thenByDescending { it.value.lastAt })
    .map { it.key }

/** The index in [TIME_WINDOWS] of the local hour at [at]. */
internal fun timeWindow(at: Long, offsetMinutes: Int): Int {
    val minuteOfDay = (at.floorDiv(60_000L) + offsetMinutes).mod(1_440L)
    return (minuteOfDay / 60 / WINDOW_HOURS).toInt()
}

internal fun timeOfDay(plays: List<Play>, now: Long, offsetMinutes: Int): List<String> {
    val window = timeWindow(now, offsetMinutes)
    return rankedIds(recentPlays(plays, now).filter { timeWindow(it.at, offsetMinutes) == window })
}

/** [plays] oldest first, split where two plays are more than 30 minutes apart. */
internal fun sessions(plays: List<Play>): List<List<Play>> {
    val out = mutableListOf<MutableList<Play>>()
    for (play in plays.sortedBy { it.at }) {
        val current = out.lastOrNull()
        if (current != null && play.at - current.last().at <= SESSION_GAP_MS) current += play else out += mutableListOf(play)
    }
    return out
}

/** Up to [DAILY_MIXES] groups of artist keys. Each starts with the most played artist left and takes up to 4 more
 * that share at least 2 sessions with it. [artistOf] gives a track's artist key, or null to skip the play. */
internal fun artistGroups(plays: List<Play>, artistOf: (String) -> String?): List<List<String>> {
    val counts = HashMap<String, Int>()
    for (play in plays) artistOf(play.id)?.let { counts[it] = (counts[it] ?: 0) + 1 }
    val sessionArtists = sessions(plays).map { session -> session.mapNotNull { artistOf(it.id) }.toSet() }
    val left = counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it }).toMutableList()
    val groups = mutableListOf<List<String>>()
    while (left.isNotEmpty() && groups.size < DAILY_MIXES) {
        val seed = left.removeAt(0)
        val shared = left.associateWith { other -> sessionArtists.count { seed in it && other in it } }
        val mates = left.filter { shared.getValue(it) >= SHARED_SESSIONS }
            .sortedWith(compareByDescending<String> { shared.getValue(it) }.thenByDescending { counts.getValue(it) })
            .take(GROUP_SIZE - 1)
        left.removeAll(mates.toSet())
        groups += listOf(seed) + mates
    }
    return groups
}

/** Known and new ids taking turns, known first, without repeats, up to [limit]. */
internal fun alternate(known: List<String>, fresh: List<String>, limit: Int = MIX_TRACKS): List<String> {
    val out = LinkedHashSet<String>()
    val knownLeft = known.iterator()
    val freshLeft = fresh.iterator()
    while (out.size < limit && (knownLeft.hasNext() || freshLeft.hasNext())) {
        if (knownLeft.hasNext()) out += knownLeft.next()
        if (out.size < limit && freshLeft.hasNext()) out += freshLeft.next()
    }
    return out.toList()
}
