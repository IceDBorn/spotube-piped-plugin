package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The history log at a refresh: plays of other devices merge in, the log is trimmed, and waiting plays go up. */
class HistoryMergeTest {

    private suspend fun AccountHarness.recent(): List<String> = history.recentTracks(50).map { it.id }

    private suspend fun AccountHarness.playsOf(index: Int): Int = history.all().firstOrNull { it.track.id == vid(index) }?.plays ?: 0

    @Test
    fun `a play on one device reaches the history of another and adds to its count`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        play(a, 1)
        refresh(b)
        assertEquals(listOf(vid(1)), b.recent())
        play(a, 2)
        play(a, 1)
        refresh(b)
        assertEquals(listOf(vid(1), vid(2)), b.recent())
        assertEquals(2, b.playsOf(1))
        // A refresh without new rows changes nothing.
        refresh(b)
        assertEquals(2, b.playsOf(1))
    }

    @Test
    fun `a device does not read its own rows back as plays`() = runTest {
        val h = logDevice()
        h.signIn()
        h.history.record(logTrack(1))
        play(h, 2)
        refresh(h)
        refresh(h)
        assertEquals(listOf(vid(2), vid(1)), h.recent())
        assertEquals(1, h.playsOf(1))
        assertEquals(1, h.playsOf(2))
    }

    @Test
    fun `a track two devices play counts on each for the play of the other`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        play(a, 1)
        refresh(a)
        refresh(b)
        play(b, 1)
        refresh(a)
        refresh(b)
        assertEquals(2, a.playsOf(1))
        assertEquals(2, b.playsOf(1))
    }

    @Test
    fun `a device with a history joins a filled log below its own plays and does not upload them`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        play(a, 1)
        play(a, 2)
        b.history.record(logTrack(10))
        b.history.record(logTrack(11))
        refresh(b)
        assertEquals(listOf(vid(11), vid(10), vid(2), vid(1)), b.recent())
        piped.reset()
        play(b, 12)
        assertEquals(listOf(vid(1), vid(2), vid(12)), assertNotNull(a.logPlaylist()).videos)
        assertEquals(2, piped.total)
        refresh(a)
        assertEquals(listOf(vid(12), vid(2), vid(1)), a.recent())
    }

    @Test
    fun `a device whose first contact with a filled log is an upload joins it below its own plays`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        play(a, 1)
        play(a, 2)
        b.history.record(logTrack(10))
        play(b, 12)
        refresh(b)
        assertEquals(listOf(vid(12), vid(10), vid(2), vid(1)), b.recent())
    }

    @Test
    fun `a device that refreshes on an empty log reads the rows that follow as new plays`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        piped.accountPlaylist(HISTORY_PLAYLIST_NAME, emptyList())
        b.history.record(logTrack(10))
        refresh(b)
        play(a, 1)
        refresh(b)
        assertEquals(listOf(vid(1), vid(10)), b.recent())
        assertEquals(1, piped.account.size)
    }

    @Test
    fun `a device that uploads to an empty log reads the rows that follow as new plays`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        piped.accountPlaylist(HISTORY_PLAYLIST_NAME, emptyList())
        play(a, 1)
        play(b, 2)
        refresh(a)
        assertEquals(listOf(vid(2), vid(1)), a.recent())
    }

    @Test
    fun `a play uploaded during a refresh is not read back as a play of another device`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        repeat(3) { h.piped.accountPlaylist("Mine $it", listOf(vid(50 + it))) }
        h.piped.latencyMs = 10
        // The listing answers at 10 ms, the log walk at 20 ms and the fourth walk at 30 ms.
        val running = launch { h.mirror.refreshCache() }
        advanceTimeBy(15)
        h.history.record(logTrack(2))
        h.mirror.played(logTrack(2))
        running.join()
        h.piped.latencyMs = 0
        refresh(h)
        assertEquals(listOf(vid(1), vid(2)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.playsOf(2))
    }

    @Test
    fun `a merge that cannot store the rows it read does not count them`() = runTest {
        val piped = FakePiped(pageSize = 100)
        val a = logDevice(piped)
        val b = logDevice(piped)
        a.signIn()
        b.signIn()
        play(a, 1)
        refresh(b)
        play(a, 2)
        b.storage.failPutsFor = HISTORY_SEEN_KEY
        refresh(b)
        assertEquals(listOf(vid(1)), b.recent())
        b.storage.failPutsFor = null
        refresh(b)
        assertEquals(listOf(vid(2), vid(1)), b.recent())
        assertEquals(1, b.playsOf(2))
    }

    @Test
    fun `a log joined again after an account switch does not count its rows twice`() = runTest {
        val h = logDevice()
        h.signIn("ice")
        play(h, 1)
        refresh(h)
        // The fake keeps one set of playlists, so the second account finds the same log and joins it.
        h.signIn("bob")
        refresh(h)
        h.signIn("ice")
        refresh(h)
        assertEquals(listOf(vid(1)), h.recent())
        assertEquals(1, h.playsOf(1))
    }

    @Test
    fun `a refresh uploads the plays that are still waiting`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.override("/user/playlists/add", body = "{}", times = 1)
        play(h, 2)
        assertEquals(listOf(vid(1)), assertNotNull(h.logPlaylist()).videos)
        refresh(h)
        assertEquals(listOf(vid(1), vid(2)), assertNotNull(h.logPlaylist()).videos)
        refresh(h)
        assertEquals(1, h.playsOf(2))
    }

    @Test
    fun `of two logs every device uses the one with the smallest id`() = runTest {
        val h = logDevice()
        h.signIn()
        val later = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, listOf(vid(8)), id = "pl-b")
        play(h, 1)
        assertEquals(listOf(vid(8), vid(1)), later.videos)
        // A second device created its own log at the same moment, and its id sorts first.
        val first = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, listOf(vid(9)), id = "pl-a")
        refresh(h)
        play(h, 2)
        assertEquals(listOf(vid(9), vid(2)), first.videos)
        assertEquals(listOf(vid(8), vid(1)), later.videos)
    }

    @Test
    fun `a refresh removes the oldest rows above the cap`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, (1..HISTORY_LOG_CAP + 3).map { vid(it) })
        refresh(h)
        assertEquals(HISTORY_LOG_CAP, log.videos.size)
        assertEquals(vid(4), log.videos.first())
        assertEquals(3, h.piped.count("/user/playlists/remove"))
        // The next refresh lines the shorter log up with the rows it has seen and finds no new play.
        val before = h.history.all()
        refresh(h)
        assertEquals(before, h.history.all())
    }

    @Test
    fun `one refresh removes no more rows than the trim limit`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, (1..HISTORY_LOG_CAP + HISTORY_TRIM_LIMIT + 10).map { vid(it) })
        refresh(h)
        assertEquals(HISTORY_TRIM_LIMIT, h.piped.count("/user/playlists/remove"))
        assertEquals(HISTORY_LOG_CAP + 10, log.videos.size)
    }

    @Test
    fun `a trim finds the oldest row when position 0 holds none`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, (1..HISTORY_LOG_CAP + 2).map { vid(it) })
        log.firstPosition = 1
        refresh(h)
        assertEquals(HISTORY_LOG_CAP, log.videos.size)
        assertEquals(vid(3), log.videos.first())
        assertEquals(4, h.piped.count("/user/playlists/remove"))
    }

    @Test
    fun `a trim stops when the instance does not answer clearly`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, (1..HISTORY_LOG_CAP + 5).map { vid(it) })
        h.piped.override("/user/playlists/remove", body = "{}")
        refresh(h)
        assertEquals(HISTORY_LOG_CAP + 5, log.videos.size)
        assertEquals(1, h.piped.count("/user/playlists/remove"))
    }

    @Test
    fun `the log stays out of the library playlists and costs one walk per refresh`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.accountPlaylist("Mine", listOf(vid(5)))
        h.piped.reset()
        refresh(h)
        assertEquals(listOf("Mine"), assertNotNull(h.mirror.cachedState()).playlists.map { it.name })
        assertEquals(1, h.piped.count("/playlists/${assertNotNull(h.logPlaylist()).id}", HttpMethod.Get))
        assertEquals(3, h.piped.total)
    }
}
