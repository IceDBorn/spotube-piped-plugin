package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.vid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L

class SkipLogTest {

    @Test
    fun `skips read back newest first`() = runTest {
        val log = SkipLog(EntityStore(FakeStorage()))
        assertTrue(log.append(listOf(Skip(vid(1), "ar1", NOW - 10)), NOW))
        assertTrue(log.append(listOf(Skip(vid(2), "ar2", NOW)), NOW))
        assertEquals(listOf(Skip(vid(2), "ar2", NOW), Skip(vid(1), "ar1", NOW - 10)), log.all())
    }

    @Test
    fun `a write drops skips 30 days old`() = runTest {
        val log = SkipLog(EntityStore(FakeStorage()))
        log.append(listOf(Skip(vid(1), "ar1", NOW)), NOW)
        log.append(listOf(Skip(vid(2), "ar2", NOW + SKIP_KEEP_MS)), NOW + SKIP_KEEP_MS)
        assertEquals(listOf(vid(2)), log.all().map { it.id })
    }

    @Test
    fun `a corrupt log reads as empty and the next skip replaces it`() = runTest {
        val storage = FakeStorage()
        storage.values[SKIPS_KEY] = "{\"not\":\"a list\"}"
        val log = SkipLog(EntityStore(storage))
        assertEquals(emptyList(), log.all())
        assertTrue(log.append(listOf(Skip(vid(1), "ar1", NOW)), NOW))
        assertEquals(listOf(Skip(vid(1), "ar1", NOW)), log.all())
    }

    @Test
    fun `a failed write returns false`() = runTest {
        val storage = FakeStorage()
        storage.failPutsFor = SKIPS_KEY
        assertFalse(SkipLog(EntityStore(storage)).append(listOf(Skip(vid(1), "ar1", NOW)), NOW))
    }

    @Test
    fun `each profile has its own log`() = runTest {
        val h = AccountHarness(backgroundScope, profiles = true)
        h.signIn("ice")
        SkipLog(h.store).append(listOf(Skip(vid(1), "ar1", NOW)), NOW)
        h.signIn("bob")
        assertEquals(emptyList(), SkipLog(h.store).all())
        h.signIn("ice")
        assertEquals(listOf(vid(1)), SkipLog(h.store).all().map { it.id })
    }
}
