package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uploads to the history log: the queue, the creation of the log, and answers that are not a yes. */
class HistoryUploadTest {

    @Test
    fun `the first play creates the log and uploads that play, not the older history`() = runTest {
        val h = logDevice()
        h.signIn()
        h.history.record(logTrack(1))
        h.history.record(logTrack(2))
        play(h, 3)
        assertEquals(listOf(vid(3)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.piped.count("/user/playlists", HttpMethod.Get))
        assertEquals(1, h.piped.count("/user/playlists/create"))
        assertEquals(1, h.piped.count("/user/playlists/add"))
        assertEquals(3, h.piped.total)
        assertEquals(emptyList(), assertNotNull(h.waitingPlays()).plays)
    }

    @Test
    fun `a later play costs one request`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.reset()
        play(h, 2)
        assertEquals(listOf(vid(1), vid(2)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.piped.total)
    }

    @Test
    fun `a play while signed out stays on the device`() = runTest {
        val h = logDevice()
        play(h, 1)
        assertEquals(0, h.piped.total)
        assertNull(h.waitingPlays())
    }

    @Test
    fun `the first play after a restart reads the listing once`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        val restarted = AccountHarness(backgroundScope, h.piped, h.storage)
        h.piped.reset()
        play(restarted, 2)
        assertEquals(listOf(vid(1), vid(2)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.piped.count("/user/playlists", HttpMethod.Get))
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `the first play on an account with a log reads the listing and adds one row`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, listOf(vid(9)))
        play(h, 2)
        assertEquals(listOf(vid(9), vid(2)), log.videos)
        assertEquals(2, h.piped.total)
    }

    @Test
    fun `an unclear answer keeps the play, and the next upload reads the listing first`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.override("/user/playlists/add", body = "{}", times = 1)
        play(h, 2)
        assertEquals(listOf(PendingPlay(vid(2))), assertNotNull(h.waitingPlays()).plays)
        h.piped.reset()
        play(h, 3)
        assertEquals(listOf(vid(1), vid(2), vid(3)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.piped.count("/user/playlists", HttpMethod.Get))
        assertEquals(3, h.piped.total)
    }

    @Test
    fun `a failed request keeps the play and does not reach the caller`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.throwOn("/user/playlists/add")
        play(h, 2)
        assertEquals(listOf(PendingPlay(vid(2))), assertNotNull(h.waitingPlays()).plays)
        assertEquals(listOf(vid(1)), assertNotNull(h.logPlaylist()).videos)
    }

    @Test
    fun `a refused video waits, lets later plays pass and is dropped after the last try`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.unfetchable += vid(2)
        h.piped.reset()
        play(h, 2)
        // The add, then the listing that shows the playlist is not the reason.
        assertEquals(1, h.piped.count("/user/playlists/add"))
        assertEquals(1, h.piped.count("/user/playlists", HttpMethod.Get))
        val refused = assertNotNull(h.waitingPlays()).plays.single()
        assertEquals(1, refused.attempts)
        assertTrue(refused.retryAt > 0)
        h.piped.reset()
        play(h, 3)
        assertEquals(listOf(vid(1), vid(3)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(1, h.piped.total)
        repeat(HISTORY_PUSH_ATTEMPTS - 1) {
            h.endWaits()
            play(h, 4 + it)
        }
        assertEquals(emptyList(), assertNotNull(h.waitingPlays()).plays)
        assertEquals(listOf(vid(1), vid(3), vid(4), vid(5), vid(6)), assertNotNull(h.logPlaylist()).videos)
    }

    @Test
    fun `a run ends after three refusals in a row`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        (2..5).forEach { h.piped.unfetchable += vid(it) }
        // Four plays the instance will refuse are already waiting when one more play starts a single run.
        val waiting = PendingPlays(FAKE_INSTANCE, "ice", (2..5).map { PendingPlay(vid(it)) })
        h.store.put(HISTORY_PENDING_KEY, json.encodeToJsonElement(waiting))
        h.piped.reset()
        play(h, 6)
        assertEquals(3, h.piped.count("/user/playlists/add"))
        val plays = assertNotNull(h.waitingPlays()).plays
        assertEquals(listOf(vid(2), vid(3), vid(4)), plays.filter { it.attempts == 1 }.map { it.id })
        assertEquals(listOf(PendingPlay(vid(5)), PendingPlay(vid(6))), plays.filter { it.attempts == 0 })
    }

    @Test
    fun `a log deleted on the web is created again and gets the new play, not the older ones`() = runTest {
        val h = logDevice()
        h.signIn()
        play(h, 1)
        h.piped.account.remove(assertNotNull(h.logPlaylist()).id)
        play(h, 2)
        assertEquals(listOf(vid(2)), assertNotNull(h.logPlaylist()).videos)
        assertEquals(emptyList(), assertNotNull(h.waitingPlays()).plays)
    }

    @Test
    fun `a switched account does not upload the plays waiting for the other account`() = runTest {
        val h = logDevice()
        h.signIn("ice")
        h.piped.override("/user/playlists", status = 500, body = "", times = 1, method = HttpMethod.Get)
        play(h, 1)
        assertEquals(listOf(PendingPlay(vid(1))), assertNotNull(h.waitingPlays()).plays)
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, listOf(vid(9)))
        h.signIn("bob")
        play(h, 2)
        assertEquals(listOf(vid(9), vid(2)), log.videos)
        assertEquals("bob", assertNotNull(h.waitingPlays()).username)
        assertEquals(emptyList(), assertNotNull(h.waitingPlays()).plays)
    }

    @Test
    fun `a play that cannot be taken off the queue is not uploaded twice`() = runTest {
        val h = logDevice()
        h.signIn()
        val log = h.piped.accountPlaylist(HISTORY_PLAYLIST_NAME, listOf(vid(9)))
        h.mirror.played(logTrack(1))
        h.storage.failPutsFor = HISTORY_PENDING_KEY
        testScheduler.runCurrent()
        h.storage.failPutsFor = null
        play(h, 2)
        assertEquals(1, h.piped.count("/user/playlists/add"))
        assertEquals(listOf(vid(9), vid(1)), log.videos)
    }
}
