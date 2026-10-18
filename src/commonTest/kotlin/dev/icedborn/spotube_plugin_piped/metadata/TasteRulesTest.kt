package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.store.SKIP_KEEP_MS
import dev.icedborn.spotube_plugin_piped.store.Skip
import dev.icedborn.spotube_plugin_piped.store.trimSkips
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_800_000_000_000L
private const val DAY = 24 * 60 * 60_000L

class TasteRulesTest {

    @Test
    fun `a skip weighs 1 when new and fades to 0 over 30 days`() {
        assertEquals(1.0, skipWeight(NOW, NOW))
        assertEquals(0.5, skipWeight(NOW - 15 * DAY, NOW))
        assertEquals(0.0, skipWeight(NOW - 30 * DAY, NOW))
        assertEquals(0.0, skipWeight(NOW - 40 * DAY, NOW))
        // Another device's clock may run ahead.
        assertEquals(1.0, skipWeight(NOW + DAY, NOW))
    }

    @Test
    fun `an artist's weights add up across devices`() {
        val skips = listOf(Skip("a", "ar1", NOW), Skip("b", "ar1", NOW - 15 * DAY), Skip("c", "ar2", NOW - 31 * DAY))
        val penalties = artistPenalties(skips, NOW)
        assertEquals(0.5 * sqrt(0.5), penalties.getValue("ar1"), 1e-9)
        assertEquals(null, penalties["ar2"])
    }

    @Test
    fun `a song stays blocked for 30 days`() {
        val skips = listOf(Skip("a", "ar1", NOW - 29 * DAY), Skip("b", "ar2", NOW - 30 * DAY))
        assertEquals(setOf("a"), blockedSongs(skips, NOW))
    }

    @Test
    fun `trimming keeps the newest skips younger than 30 days, one per song and time`() {
        val skips = listOf(
            Skip("a", "ar1", NOW - DAY),
            Skip("b", "ar1", NOW),
            Skip("b", "ar1", NOW),
            Skip("c", "ar2", NOW - SKIP_KEEP_MS),
            Skip("d", "ar2", NOW - 2 * DAY),
        )
        assertEquals(listOf("b", "a"), trimSkips(skips, NOW, limit = 2).map { it.id })
        assertEquals(listOf("b", "a", "d"), trimSkips(skips, NOW).map { it.id })
    }
}
