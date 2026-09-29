package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.FakeWebView
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The login form: sign in, register, skip, instance changes and a form that never answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreLoginTest {

    private class Harness {
        val piped = FakePiped()
        val webView = FakeWebView()
        val storage = FakeStorage()
        val store = EntityStore(storage)
        val session = AccountSession(store)
        val instanceSource = InstanceSource(store, FAKE_INSTANCE)
        var logins = 0

        fun core(scope: CoroutineScope) = RealCoreAPI(
            httpClient = piped,
            storage = storage,
            webView = webView,
            session = session,
            instanceSource = instanceSource,
            region = RegionSetting(store, null),
            libraryPlaylist = LibraryPlaylistSetting(store),
            onLogin = { logins++ },
            coroutineScope = scope,
        )
    }

    private fun TestScope.step(ms: Long = 2_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.awaitForm(web: FakeWebView): String {
        repeat(10) {
            if (web.pages.isNotEmpty()) return web.nonceOf(web.pages.last())
            step(500)
        }
        error("the form was never shown")
    }

    private fun withLogin(signedIn: Boolean = false, block: suspend TestScope.(Harness, RealCoreAPI, Job) -> Unit) = runTest {
        val h = Harness()
        if (signedIn) h.session.save(PipedAccount(FAKE_INSTANCE, "ice", "token-ice"))
        val core = h.core(CoroutineScope(coroutineContext))
        step()
        val job = launch { runCatching { core.login() } }
        block(h, core, job)
    }

    private fun loginMessage(nonce: String, user: String, password: String, create: Boolean) =
        """{"action":"login","username":"$user","password":"$password","createAccount":$create,"nonce":"$nonce"}"""

    @Test
    fun `signing in saves the session and runs the login hook`() = withLogin { h, core, job ->
        h.piped.users["ice"] = "pw"
        h.webView.post(loginMessage(awaitForm(h.webView), "ice", "pw", create = false))
        step()
        job.join()
        assertEquals("token-ice", assertNotNull(h.session.load()).token)
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.logins)
        assertEquals(1, h.webView.exitCount)
    }

    @Test
    fun `a failed sign-in with the box checked registers`() = withLogin { h, core, job ->
        h.webView.post(loginMessage(awaitForm(h.webView), "new", "pw", create = true))
        step()
        job.join()
        assertEquals("new", assertNotNull(h.session.load()).username)
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.piped.count("/register"))
    }

    @Test
    fun `a wrong password without the box keeps the form open`() = withLogin { h, core, job ->
        h.piped.users["ice"] = "pw"
        val nonce = awaitForm(h.webView)
        h.webView.post(loginMessage(nonce, "ice", "bad", create = false))
        step()
        assertTrue(job.isActive)
        assertTrue(h.webView.scripts.any { it.contains("incorrect") })
        h.webView.post(nonce, "close")
        step()
        job.join()
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
    }

    @Test
    fun `continuing without an account stays logged out`() = withLogin { h, core, job ->
        h.webView.post(awaitForm(h.webView), "skip")
        step()
        job.join()
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
        assertEquals(0, h.logins)
    }

    @Test
    fun `Done with a reachable session keeps it and refreshes`() = withLogin(signedIn = true) { h, core, job ->
        h.webView.post(awaitForm(h.webView), "close")
        step()
        job.join()
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.logins)
        assertEquals(1, h.piped.count("/user/playlists"))
    }

    @Test
    fun `Done with a revoked session clears it`() = withLogin(signedIn = true) { h, core, job ->
        h.piped.override("/user/playlists", status = 401, body = """{"error":"Authentication failed."}""")
        h.webView.post(awaitForm(h.webView), "close")
        step()
        job.join()
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
    }

    @Test
    fun `Done while the instance fails keeps the session`() = withLogin(signedIn = true) { h, core, job ->
        h.piped.override("/user/playlists", status = 502, body = "")
        h.webView.post(awaitForm(h.webView), "close")
        step()
        job.join()
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
        assertEquals(0, h.logins)
    }

    @Test
    fun `Done keeps the session on a firewall page, a proxy 404 or a throttle`() = runTest {
        val keeps = listOf(403 to "<html>blocked</html>", 401 to "{}", 404 to "", 429 to "{}", 400 to """{"error":"x"}""")
        for ((status, body) in keeps) {
            val h = Harness()
            h.session.save(PipedAccount(FAKE_INSTANCE, "ice", "token-ice"))
            val core = h.core(CoroutineScope(coroutineContext))
            step()
            val job = launch { runCatching { core.login() } }
            h.piped.override("/user/playlists", status = status, body = body)
            h.webView.post(awaitForm(h.webView), "close")
            step()
            job.join()
            assertNotNull(h.session.load(), "status $status")
        }
    }

    @Test
    fun `Done with an error body on a 200 keeps the session`() = withLogin(signedIn = true) { h, core, job ->
        // A throttle can answer this way, so only a 401 or 403 proves the token was refused.
        h.piped.override("/user/playlists", body = """{"error":"Too many requests."}""")
        h.webView.post(awaitForm(h.webView), "close")
        step()
        job.join()
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
    }

    @Test
    fun `a refused check does not clear a newer session`() = withLogin(signedIn = true) { h, core, job ->
        h.piped.override("/user/playlists", status = 403, body = """{"error":"Authentication failed."}""")
        h.piped.latencyMs = 10_000
        h.webView.post(awaitForm(h.webView), "close")
        step()
        h.session.save(PipedAccount(FAKE_INSTANCE, "other", "token-other"))
        step(20_000)
        job.join()
        assertEquals("other", assertNotNull(h.session.load()).username)
    }

    @Test
    fun `saving another instance drops the session`() = withLogin(signedIn = true) { h, core, job ->
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"https://other.example","playback":"","nonce":"$nonce"}""")
        step()
        h.webView.post(nonce, "close")
        step()
        job.join()
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
        assertEquals("https://other.example", h.instanceSource.api())
    }

    @Test
    fun `a form that never answers keeps the stored session logged in`() = withLogin(signedIn = true) { h, core, job ->
        awaitForm(h.webView)
        step(1_000_000)
        job.join()
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.webView.exitCount)
    }

    @Test
    fun `the form closes before a slow login hook finishes`() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        val core = RealCoreAPI(
            httpClient = h.piped,
            storage = h.storage,
            webView = h.webView,
            session = h.session,
            instanceSource = h.instanceSource,
            region = RegionSetting(h.store, null),
            libraryPlaylist = LibraryPlaylistSetting(h.store),
            onLogin = { gate.await() },
            coroutineScope = CoroutineScope(coroutineContext),
        )
        step()
        h.piped.users["ice"] = "pw"
        val job = launch { core.login() }
        h.webView.post(loginMessage(awaitForm(h.webView), "ice", "pw", create = false))
        step()
        assertTrue(job.isCompleted)
        assertEquals(1, h.webView.exitCount)
        assertTrue(core.loggedInFlow.value)
        gate.complete(Unit)
    }

    @Test
    fun `an instance that does not answer is not saved`() = withLogin(signedIn = true) { h, core, job ->
        val nonce = awaitForm(h.webView)
        h.piped.override("/config", status = 502, body = "")
        h.webView.post("""{"action":"instance","instance":"https://dead.example","playback":"","nonce":"$nonce"}""")
        step()
        assertEquals(FAKE_INSTANCE, h.instanceSource.api())
        assertNotNull(h.session.load())
        assertTrue(h.webView.scripts.any { it.contains("Could not reach") })
        h.webView.post(nonce, "close")
        step()
        job.join()
    }

    @Test
    fun `an instance without a scheme is saved as https`() = withLogin { h, core, job ->
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"other.example/","playback":"","nonce":"$nonce"}""")
        step()
        assertEquals("https://other.example", h.instanceSource.api())
        h.webView.post(nonce, "close")
        step()
        job.join()
    }
}
