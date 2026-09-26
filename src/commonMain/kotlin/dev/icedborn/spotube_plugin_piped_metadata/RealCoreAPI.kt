package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.host_apis.WebViewAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.core.CoreAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.core.PluginUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.swiftzer.semver.SemVer
import kotlin.coroutines.cancellation.CancellationException

private const val FORM_WAIT_MS = 900_000L

private const val FORM_THEME_KEY = "piped.form.theme"

/** How long the "instance saved" confirmation stays up before the form closes. */
private const val FORM_CLOSE_GRACE_MS = 800L

private enum class SettingsFormResult { LoggedIn, InstanceOnly }

private val coreLog = Logger("PipedCore")

/** Piped needs no account for metadata; a per-instance account enables write-through saves to the
 * "Spotube - Albums/Artists/Favorites" playlists and an offline cache of the account state. */
class RealCoreAPI(
    private val httpClient: HttpClientAPI,
    private val storage: PersistedStorageAPI,
    private val webView: WebViewAPI,
    private val session: AccountSession,
    private val instanceSource: InstanceSource,
    private val region: RegionSetting,
    private val channel: UpdateChannelSetting? = null,
    private val libraryPlaylist: LibraryPlaylistSetting? = null,
    private val onLogin: suspend () -> Unit = {},
    private val updateChecker: UpdateChecker = UpdateChecker(httpClient, channel),
) : CoreAPI {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val loggedInStateFlow = MutableStateFlow(false)

    override val requiresAuthentication: Boolean = true

    override val loggedInFlow: StateFlow<Boolean> = loggedInStateFlow.asStateFlow()

    init {
        scope.launch {
            loggedInStateFlow.value = session.load() != null
        }
    }

    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo? =
        updateChecker.check(currentVersion)

    override fun supportMarkdownText(currentVersion: SemVer): String =
        "YouTube Music metadata (tracks, albums, artists, playlists) via a Piped instance." +
            " There is no default instance: open Settings, choose Piped and save the URL" +
            " of an instance from the TeamPiped list (github.com/TeamPiped/Piped/wiki/Instances)." +
            " Optionally give it a separate playback instance for audio." +
            " Log in on that instance to sync saved items to the account and keep an" +
            " offline cache on this device; without an account everything stays local." +
            " Source: https://github.com/IceDBorn/spotube-piped-plugin"

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun login() {
        try {
            loggedInStateFlow.value = false
            val existing = session.load()
            when (showSettingsForm(
                instanceSource.api().orEmpty(),
                instanceSource.playback().orEmpty(),
                existing?.username.orEmpty(),
            )) {
                SettingsFormResult.LoggedIn -> {
                    val account = session.load() ?: throw IllegalStateException("Piped login failed")
                    coreLog.i { "logged in to the instance as ${account.username}" }
                    loggedInStateFlow.value = true
                    onLogin()
                }
                // Account-less setup: drop an existing session when the instance changed — a session is only
                // valid on the instance it was created on.
                SettingsFormResult.InstanceOnly -> {
                    var loggedIn = false
                    val saved = session.load()
                    if (saved != null) {
                        val savedInstance = instanceSource.api()
                        if (savedInstance == null || saved.instance.trim().trimEnd('/') == savedInstance.trimEnd('/')) {
                            loggedIn = true
                            coreLog.i { "instance-only setup kept the existing session for ${saved.username}" }
                        } else {
                            coreLog.i { "instance changed to $savedInstance, cleared the session for ${saved.username}" }
                            session.clear()
                        }
                    }
                    // Verify the RETAINED token before asserting logged-in: doRefresh() returns silently on a
                    // failed listing, so onLogin() cannot surface a revoked session (round-104 corr-2).

                    if (loggedIn && saved != null && !listingReachable(saved)) {
                        coreLog.w { "session for ${saved.username} on ${saved.instance} is no longer reachable, clearing it" }
                        session.clear()
                        loggedIn = false
                    }
                    loggedInStateFlow.value = loggedIn
                    // Same post-login hook as the LoggedIn branch: refresh NOW — a form-completed loggedIn=true
                    // with an expired token leaves saves silently failing until the TTL-bound refresh.

                    if (loggedIn) onLogin()
                }
            }
        } finally {
            webView.exitWebView()
        }
    }

    override suspend fun logout() {
        session.clear()
        loggedInStateFlow.value = false
    }

    /** True when [account]'s token still answers the authenticated listing. A revoked/expired token yields a
     * non-2xx or a throttle/error 2xx — the silent-null path onLogin() cannot surface (round-104 corr-2). */
    private suspend fun listingReachable(account: PipedAccount): Boolean {
        // try/catch, not runCatching: a cancelled login must NOT read as an unreachable session,
        // which would clear a still-valid one.
        val response = try {
            httpClient.request(
                method = HttpMethod.Get,
                url = account.instance.trimEnd('/') + "/user/playlists",
                requestHeaders = mapOf("Accept" to "application/json", "Authorization" to account.token),
                body = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coreLog.w { "session check on ${account.instance} threw: ${e.message}" }
            return false
        }
        if (response.statusCode !in 200..299) {
            coreLog.w { "session check on ${account.instance} returned ${response.statusCode}" }
            return false
        }
        val body = response.body
        if (body == null || body.isBlank()) {
            coreLog.w { "session check on ${account.instance} returned a blank body" }
            return false
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonArray) {
            coreLog.w { "session check on ${account.instance} returned a non-array body: ${body.take(200)}" }
            return false
        }
        return true
    }

    private suspend fun showSettingsForm(instance: String, playback: String, username: String): SettingsFormResult {
        // UNLIMITED: a theme toggle posted mid-login must not replace the queued login message.
        val messages = Channel<String>(Channel.UNLIMITED)
        val subscriber = scope.launch {
            webView.postMessagesFlow().onEach { messages.trySend(it) }.launchIn(this)
            val lightTheme = runCatching { storage.getString(FORM_THEME_KEY) }.getOrNull() == "light"
            val html = settingsFormHtml(
                instance, playback, username, lightTheme,
                region.stored(), region.detected(),
                channel?.stored()?.name ?: UpdateChannel.AUTO.name,
                library = libraryPlaylist?.stored()?.name ?: LibraryPlaylist.ALWAYS.name,
            )
            webView.navigateToHTML(html)
        }
        try {
            while (true) {
                val message = withTimeoutOrNull(FORM_WAIT_MS) { messages.receive() }
                    ?: throw IllegalStateException("the settings form was closed without saving")
                val fields = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: continue
                val action = fields["action"]?.jsonPrimitive?.contentOrNull
                if (action == "region") {
                    runCatching { region.set(fields["region"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
                    continue
                }
                if (action == "channel") {
                    runCatching {
                        channel?.set(
                            UpdateChannel.entries.firstOrNull {
                                it.name == fields["channel"]?.jsonPrimitive?.contentOrNull
                            } ?: UpdateChannel.AUTO
                        )
                    }
                    // The cached result belongs to the old channel, so the next check has to ask again.
                    updateChecker.clearCache()
                    continue
                }
                if (action == "library") {
                    val name = fields["library"]?.jsonPrimitive?.contentOrNull
                    val mode = LibraryPlaylist.entries.firstOrNull { it.name == name }
                    if (mode != null) runCatching { libraryPlaylist?.set(mode) }
                    else coreLog.w { "unknown library playlist setting: $name" }
                    continue
                }
                if (action == "theme") {
                    saveTheme(fields)
                    continue
                }
                if (action == "instance") {
                    saveInstance(fields)
                    continue
                }
                val activeInstance = instanceSource.api()
                if (activeInstance == null) {
                    coreLog.w { "sign-in attempted before an instance was configured" }
                    setFormStatus("Set up a Piped instance first.")
                    continue
                }
                if (action == "skip") {
                    // The caller keeps the instance-only user logged out.
                    delay(FORM_CLOSE_GRACE_MS)
                    return SettingsFormResult.InstanceOnly
                }
                if (action != "login") continue
                val enteredUsername = fieldOr(fields, "username", username)
                val password = fields["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val createIfMissing = fields["createAccount"]?.jsonPrimitive?.contentOrNull == "true"
                if (enteredUsername.isEmpty() || password.isEmpty()) {
                    coreLog.w { "sign-in on $activeInstance was sent with a missing username or password" }
                    setFormStatus("Username and password are required.")
                    continue
                }
                val auth = PipedAuthClient(httpClient)
                val token = try {
                    auth.login(activeInstance, enteredUsername, password)
                } catch (e: Exception) {
                    coreLog.w { "sign-in on $activeInstance as $enteredUsername failed: ${e.message}" }
                    if (createIfMissing) {
                        try {
                            auth.register(activeInstance, enteredUsername, password)
                        } catch (e2: Exception) {
                            coreLog.w { "registration on $activeInstance as $enteredUsername failed: ${e2.message}" }
                            setFormStatus("Sign-in failed, and registration was rejected by the instance.")
                            continue
                        }
                    } else {
                        setFormStatus("The username or password is incorrect.")
                        continue
                    }
                }
                session.save(PipedAccount(instance = activeInstance, username = enteredUsername, token = token))
                return SettingsFormResult.LoggedIn
            }
            error("unreachable")
        } finally {
            subscriber.cancel()
        }
    }

    /** The host cannot reply to the form directly, so feedback is pushed with evaluateJavaScript. */
    private suspend fun setFormStatus(text: String) {
        // The form disables its buttons while a message is in flight; an error status must re-enable them.
        val script = "var s=document.getElementById('status');" +
            "if(s){s.className='error';s.textContent=${json.encodeToString(text)};}" +
            "if(window.disableButtons){window.disableButtons(false);}"
        runCatching { webView.evaluateJavaScript(script) }
    }

    private suspend fun saveTheme(fields: JsonObject) {
        val light = fields["light"]?.jsonPrimitive?.contentOrNull == "true"
        runCatching { storage.putString(FORM_THEME_KEY, if (light) "light" else "dark") }
    }

    private suspend fun saveInstance(fields: JsonObject) {
        val entered = fields["instance"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (entered.isEmpty()) {
            setFormStatus("Enter the URL of the Piped instance to use.")
            return
        }
        instanceSource.setApi(entered)
        instanceSource.setPlayback(fields["playback"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val active = instanceSource.api().orEmpty()
        // A session only works on the instance it was created on.
        val saved = session.load()
        if (saved != null && saved.instance.trim().trimEnd('/') != active) session.clear()
        val script = "if(window.onInstanceSaved){window.onInstanceSaved(" +
            "${json.encodeToString(active)}," +
            "${json.encodeToString("Instance saved. Sign in, or continue without an account.")});}"
        runCatching { webView.evaluateJavaScript(script) }
    }

    private fun fieldOr(fields: JsonObject, name: String, fallback: String): String =
        fields[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}