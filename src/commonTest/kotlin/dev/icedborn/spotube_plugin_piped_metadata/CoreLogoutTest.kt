package dev.icedborn.spotube_plugin_piped_metadata

import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped_metadata.fakes.FakeWebView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val TEST_INSTANCE = "https://piped.example"

private class Harness {
    val http = FakeHttp()
    val webView = FakeWebView()
    val storage = FakeStorage()
    val store = EntityStore(storage)
    val session = AccountSession(store)
    val instanceSource = InstanceSource(store, TEST_INSTANCE)

    fun core(scope: CoroutineScope) = RealCoreAPI(
        httpClient = http,
        storage = storage,
        webView = webView,
        session = session,
        instanceSource = instanceSource,
        region = RegionSetting(store, null),
        libraryPlaylist = LibraryPlaylistSetting(store),
        coroutineScope = scope,
    )

    suspend fun saveSession() =
        session.save(PipedAccount(instance = TEST_INSTANCE, username = "ice", token = "t0"))
}

/** Runs [block] with the core on the test dispatcher, so the form loop and its delays stay virtual. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun withCore(
    signedIn: Boolean = true,
    block: suspend TestScope.(Harness, RealCoreAPI) -> Unit,
) = runTest {
    val harness = Harness()
    if (signedIn) harness.saveSession()
    val core = harness.core(CoroutineScope(coroutineContext))
    advanceTimeBy(FORM_GRACE_MS)
    runCurrent() // the session state is set in an init launch on the injected scope
    block(harness, core)
}

/** Enough virtual time for logout's clearData settle plus the form's own grace period. */
private const val FORM_GRACE_MS = 2_000L

/** Steps virtual time, so a waiting form loop makes progress. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.step(ms: Long = FORM_GRACE_MS) {
    advanceTimeBy(ms)
    runCurrent()
}

/** The nonce of the form now on screen. */
private fun FakeWebView.shownNonce(): String = nonceOf(pages.last())

/** Steps time until the form is shown, then returns its nonce. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.awaitForm(web: FakeWebView): String {
    repeat(10) {
        if (web.pages.isNotEmpty()) return web.shownNonce()
        step(500)
    }
    error("the form was never shown")
}

/** Runs logout() off the test body, so a form can be driven while it waits. */
private fun TestScope.logoutInBackground(core: RealCoreAPI): Job = launch { core.logout() }

/** Drives the form to its end, then waits for logout() to return. */
private suspend fun TestScope.finish(job: Job) {
    step()
    job.join()
}

/** The host logout button opens the form; only an explicit Log out (or an instance change) ends the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreLogoutTest {

    @Test
    fun `logout with no session makes no form and stays logged out`() = withCore(signedIn = false) { h, core ->
        core.logout()
        step()
        assertTrue(h.webView.pages.isEmpty())
        assertFalse(core.loggedInFlow.value)
        assertEquals(0, h.webView.exitCount)
    }

    @Test
    fun `an explicit log out clears the session`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        h.webView.post(awaitForm(h.webView), "logout")
        finish(logout)
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
    }

    @Test
    fun `Done keeps the session and the logged-in state`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        h.webView.post(awaitForm(h.webView), "close")
        finish(logout)
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
    }

    @Test
    fun `a region change then Done keeps the session`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"region","region":"$REGION_GLOBAL","nonce":"$nonce"}""")
        step()
        h.webView.post(nonce, "close")
        finish(logout)
        assertEquals(REGION_GLOBAL, RegionSetting(h.store, null).stored())
        assertNotNull(h.session.load())
    }

    @Test
    fun `a library change is stored`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"library","library":"OFF","nonce":"$nonce"}""")
        step()
        h.webView.post(nonce, "close")
        finish(logout)
        assertEquals(LibraryPlaylist.OFF, LibraryPlaylistSetting(h.store).stored())
    }

    @Test
    fun `saving a different instance clears the session`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"https://other.example","playback":"","nonce":"$nonce"}""")
        step()
        h.webView.post(nonce, "close")
        finish(logout)
        assertNull(h.session.load())
        assertFalse(core.loggedInFlow.value)
    }

    @Test
    fun `saving another instance tells the form the session is gone`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"https://other.example","playback":"","nonce":"$nonce"}""")
        step()
        assertTrue(h.webView.scripts.any { it.contains("\"https://other.example\",\"Instance saved.\",false") })
        h.webView.post(nonce, "close")
        finish(logout)
    }

    @Test
    fun `saving the same instance tells the form the session stays`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"$TEST_INSTANCE/","playback":"","nonce":"$nonce"}""")
        step()
        assertTrue(h.webView.scripts.any { it.contains("\"Instance saved.\",true") })
        h.webView.post(nonce, "close")
        finish(logout)
        assertNotNull(h.session.load())
    }

    @Test
    fun `saving the same instance keeps the session`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        val nonce = awaitForm(h.webView)
        h.webView.post("""{"action":"instance","instance":"$TEST_INSTANCE/","playback":"","nonce":"$nonce"}""")
        step()
        h.webView.post(nonce, "close")
        finish(logout)
        assertNotNull(h.session.load())
    }

    @Test
    fun `a message with the wrong nonce is ignored`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        awaitForm(h.webView)
        h.webView.post("stale", "logout")
        step()
        assertTrue(logout.isActive)
        assertNotNull(h.session.load())
        // The real message still ends it, so the loop is alive.
        h.webView.post(h.webView.shownNonce(), "logout")
        finish(logout)
        assertNull(h.session.load())
    }

    @Test
    fun `a replayed login from the previous form does not sign in`() = withCore { h, core ->
        // The host replays the last message of the previous form, carrying that form's nonce.
        h.webView.post("oldformnonce", "login")
        val logout = logoutInBackground(core)
        awaitForm(h.webView)
        step()
        assertTrue(logout.isActive)
        assertNotNull(h.session.load())
        assertTrue(h.http.requests.none { it.contains("/login") })
    }

    @Test
    fun `the manage form shows the signed-in line and no sign-in fields`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        awaitForm(h.webView)
        val html = h.webView.pages.last()
        assertTrue(html.contains("Signed in as <strong>ice</strong>"))
        assertTrue(html.contains("id=\"logOut\""))
        assertTrue(html.contains("data-manage=\"true\""))
        // It opens on Settings, where the sign-in fields are not.
        assertTrue(html.contains("showTab(manage ? 'settings'"))
        h.webView.post(h.webView.shownNonce(), "close")
        finish(logout)
    }

    @Test
    fun `the web view is closed on every path`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        h.webView.post(awaitForm(h.webView), "close")
        finish(logout)
        assertEquals(1, h.webView.exitCount)
    }

    @Test
    fun `a window closed without a press keeps the session`() = withCore { h, core ->
        val logout = logoutInBackground(core)
        awaitForm(h.webView)
        // Nothing is posted, so the form waits out its whole timeout and the loop throws.
        step(1_000_000)
        logout.join()
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.webView.exitCount)
    }

    @Test
    fun `a form that cannot open keeps the session and closes the web view`() = withCore { h, core ->
        h.webView.failOnNavigate = IllegalStateException("no web view")
        val logout = logoutInBackground(core)
        step()
        assertNotNull(h.session.load())
        assertTrue(core.loggedInFlow.value)
        assertEquals(1, h.webView.exitCount)
    }
}
