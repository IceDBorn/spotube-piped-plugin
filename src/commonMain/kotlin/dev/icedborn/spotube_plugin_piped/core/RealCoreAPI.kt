package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylist
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.settings.UpdateChannel
import dev.icedborn.spotube_plugin_piped.settings.UpdateChannelSetting
import dev.icedborn.spotube_plugin_piped.settings.settingsFormHtml
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.host_apis.WebViewAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.core.CoreAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.core.PluginUpdateInfo
import kotlinx.coroutines.CoroutineScope
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

/** The host runs clearData next to logout(); the form must not be shown before it lands. */
private const val CLEAR_DATA_SETTLE_MS = 500L

/** Sentinel the form loop gets when the web view refused to open. Not valid JSON, so it never parses. */
private const val FORM_OPEN_FAILED = "\u0000form-open-failed"

/** [Closed] is the Done button: the form ends with nothing changed. [LoggedOut] is the Log out button. */
private enum class SettingsFormResult { LoggedIn, InstanceOnly, Closed, LoggedOut }

private val coreLog = Logger("PipedCore")

/** Piped needs no account for metadata; a per-instance account enables write-through saves to the
 * "Spotube - Albums/Artists/Favorites" playlists and an offline cache of the account state. */
internal class RealCoreAPI(
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
    // The plugin passes its main scope; tests pass one on their own dispatcher.
    coroutineScope: CoroutineScope,
) : CoreAPI {

    private val scope = coroutineScope
    private val loggedInStateFlow = MutableStateFlow(false)

    override val requiresAuthentication: Boolean = true

    override val loggedInFlow: StateFlow<Boolean> = loggedInStateFlow.asStateFlow()

    init {
        scope.launch {
            loggedInStateFlow.value = session.load() != null
        }
    }

    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo? = updateChecker.check(currentVersion)

    override fun supportMarkdownText(currentVersion: SemVer): String =
        "YouTube Music audio and metadata (tracks, albums, artists, playlists) via a Piped instance." +
            " There is no default instance: open Settings, choose Piped and save the URL" +
            " of an instance from the TeamPiped list (github.com/TeamPiped/Piped/wiki/Instances)." +
            " Optionally give it a separate playback instance for audio." +
            " Scrobbles feed a play history on this device that drives the For you section of Home." +
            " Log in on that instance to sync saved items to the account and keep an" +
            " offline cache on this device; without an account everything stays local." +
            " Source: https://github.com/IceDBorn/spotube-piped-plugin"

    override suspend fun login() {
        loggedInStateFlow.value = false
        val existing = session.load()
        // No suspend call sits in a catch with a finally: Zipline's QuickJS cannot compile the generator for it.
        val attempt = runCatching {
            showSettingsForm(
                instanceSource.api().orEmpty(),
                instanceSource.playback().orEmpty(),
                existing?.username.orEmpty(),
                signedIn = existing,
            )
        }
        // The form closes before any account work, so the user never waits on a refresh.
        webView.exitWebView()
        val result = attempt.getOrElse { e ->
            // A closed or failed form changes nothing, so the stored session decides the state.
            loggedInStateFlow.value = session.load() != null
            throw e
        }
        when (result) {
            SettingsFormResult.LoggedIn -> {
                val account = session.load() ?: throw IllegalStateException("Piped login failed")
                coreLog.i { "logged in to the instance as ${account.username}" }
                loggedInStateFlow.value = true
                scope.launch { runLoginHook() }
            }

            SettingsFormResult.LoggedOut -> {
                session.clear()
                coreLog.i { "signed out from the settings form" }
            }

            // A session is only valid on the instance it was created on.
            SettingsFormResult.InstanceOnly, SettingsFormResult.Closed -> {
                val saved = session.load() ?: return
                val savedInstance = instanceSource.api()
                if (savedInstance != null && normalizeInstance(saved.instance) != normalizeInstance(savedInstance)) {
                    coreLog.i { "instance changed to $savedInstance, cleared the session for ${saved.username}" }
                    session.clear()
                    return
                }
                coreLog.i { "the form kept the existing session for ${saved.username}" }
                loggedInStateFlow.value = true
                scope.launch { verifyThenRefresh(saved) }
            }
        }
    }

    /** A kept token may have been revoked, and the refresh fails silently, so the listing is checked first. */
    private suspend fun verifyThenRefresh(account: PipedAccount) {
        when (checkSession(account)) {
            SessionCheck.VALID -> runLoginHook()

            // Offline or a failing instance proves nothing, so the session stays for the next refresh.
            SessionCheck.UNKNOWN -> coreLog.w { "could not check the session for ${account.username}, keeping it" }

            SessionCheck.REVOKED -> {
                val current = session.load()
                if (current == null || current.token != account.token || !sameAccount(current, account)) return
                coreLog.w { "session for ${account.username} on ${account.instance} was refused, clearing it" }
                session.clear()
                loggedInStateFlow.value = false
            }
        }
    }

    private suspend fun runLoginHook() {
        try {
            onLogin()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coreLog.w { "the post-login refresh failed: ${e.message}" }
        }
    }

    override suspend fun logout() {
        val account = session.load()
        if (account == null) {
            session.clear()
            loggedInStateFlow.value = false
            return
        }
        // The host runs clearData next to logout(), and that nulls the page, so wait past it before the form.
        delay(CLEAR_DATA_SETTLE_MS)
        val result = try {
            showSettingsForm(
                instance = instanceSource.api() ?: account.instance,
                playback = instanceSource.playback().orEmpty(),
                username = account.username,
                manage = true,
                signedIn = account,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A closed window must not cost the user their session.
            coreLog.w { "the logout form did not finish, kept the session: ${e.message}" }
            SettingsFormResult.Closed
        } finally {
            webView.exitWebView()
        }
        loggedInStateFlow.value = endLogoutForm(account, result)
    }

    /** Only an explicit Log out, or an instance change, ends the session. */
    private suspend fun endLogoutForm(account: PipedAccount, result: SettingsFormResult): Boolean = when (result) {
        SettingsFormResult.LoggedOut -> {
            session.clear()
            false
        }

        // Done, and anything else the manage form can return: a sign-in or a skip there can only be a replayed
        // message. An instance change already cleared the session in saveInstance.
        else -> sessionStillThere(account)
    }

    private suspend fun sessionStillThere(account: PipedAccount): Boolean {
        val kept = session.load() != null
        if (!kept) coreLog.w { "the logout form found no session left for ${account.username}; staying logged out" }
        return kept
    }

    /** Asks the authenticated listing whether [account]'s token still works. Only a 401 or 403 with a JSON "error"
     * counts as revoked; a 200 error may be a throttle, and a proxy or firewall page proves nothing. */
    private suspend fun checkSession(account: PipedAccount): SessionCheck {
        // try/catch, not runCatching: a cancelled login must not read as an unreachable session,
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
            return SessionCheck.UNKNOWN
        }
        val status = response.statusCode
        val body = response.body.orEmpty()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (status !in 200..299) {
            coreLog.w { "session check on ${account.instance} returned $status" }
            val refused = (status == 401 || status == 403) && root is JsonObject && "error" in root
            return if (refused) SessionCheck.REVOKED else SessionCheck.UNKNOWN
        }
        return when {
            root is JsonArray -> SessionCheck.VALID

            else -> {
                coreLog.w { "session check on ${account.instance} returned an unreadable body: ${body.take(200)}" }
                SessionCheck.UNKNOWN
            }
        }
    }

    private enum class SessionCheck { VALID, REVOKED, UNKNOWN }

    private suspend fun showSettingsForm(
        instance: String,
        playback: String,
        username: String,
        manage: Boolean = false,
        signedIn: PipedAccount? = null,
    ): SettingsFormResult {
        // An unlimited buffer, so a theme toggle posted mid-login does not replace the queued login message.
        val messages = Channel<String>(Channel.UNLIMITED)
        // A new subscriber first receives the last message of the previous form; the nonce drops that replay.
        val nonce = randomFormNonce()
        val subscriber = openForm(messages, nonce, instance, playback, username, manage, signedIn)
        try {
            while (true) {
                val message = withTimeoutOrNull(FORM_WAIT_MS) { messages.receive() }
                    ?: throw IllegalStateException("the settings form was closed without saving")
                if (message == FORM_OPEN_FAILED) {
                    throw IllegalStateException("the settings form could not be shown")
                }
                val fields = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: continue
                if (fields["nonce"]?.jsonPrimitive?.contentOrNull != nonce) continue
                val action = fields["action"]?.jsonPrimitive?.contentOrNull
                if (applySetting(action, fields, manage)) continue
                when (action) {
                    "close" -> {
                        delay(FORM_CLOSE_GRACE_MS)
                        return SettingsFormResult.Closed
                    }

                    "logout" -> {
                        delay(FORM_CLOSE_GRACE_MS)
                        return SettingsFormResult.LoggedOut
                    }
                }
                if (manage) {
                    // The manage form has no sign-in fields, so a login message can only be a replay.
                    coreLog.w { "ignoring a $action message on the logout form" }
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
                if (action == "login" && signIn(fields, username, activeInstance)) return SettingsFormResult.LoggedIn
            }
            error("unreachable")
        } finally {
            subscriber.cancel()
        }
    }

    /** Subscribes to the form's messages and shows it. A form that cannot open posts [FORM_OPEN_FAILED]. */
    private fun openForm(
        messages: Channel<String>,
        nonce: String,
        instance: String,
        playback: String,
        username: String,
        manage: Boolean,
        signedIn: PipedAccount?,
    ) = scope.launch {
        try {
            webView.postMessagesFlow().onEach { messages.trySend(it) }.launchIn(this)
            val lightTheme = runCatching { storage.getString(FORM_THEME_KEY) }.getOrNull() == "light"
            val html = settingsFormHtml(
                instance, playback, username, lightTheme,
                region.stored(), region.detected(),
                channel?.stored()?.name ?: UpdateChannel.AUTO.name,
                library = libraryPlaylist?.stored()?.name ?: LibraryPlaylist.ALWAYS.name,
                nonce = nonce,
                manage = manage,
                signedInAs = signedIn?.username,
                signedInOn = signedIn?.instance,
            )
            webView.navigateToHTML(html)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The host could not open the form. End the loop at once rather than wait out the timeout.
            coreLog.w { "the settings form could not be shown: ${e.message}" }
            messages.trySend(FORM_OPEN_FAILED)
        }
    }

    /** Applies a setting the form changed in place. False for any other action. */
    private suspend fun applySetting(action: String?, fields: JsonObject, manage: Boolean): Boolean {
        when (action) {
            "region" -> runCatching { region.set(fields["region"]?.jsonPrimitive?.contentOrNull.orEmpty()) }

            "channel" -> {
                val name = fields["channel"]?.jsonPrimitive?.contentOrNull
                runCatching { channel?.set(UpdateChannel.entries.firstOrNull { it.name == name } ?: UpdateChannel.AUTO) }
                // The cached result belongs to the old channel, so the next check has to ask again.
                updateChecker.clearCache()
            }

            "library" -> {
                val name = fields["library"]?.jsonPrimitive?.contentOrNull
                val mode = LibraryPlaylist.entries.firstOrNull { it.name == name }
                if (mode != null) runCatching { libraryPlaylist?.set(mode) } else coreLog.w { "unknown library playlist setting: $name" }
            }

            "theme" -> saveTheme(fields)

            "instance" -> saveInstance(fields, manage)

            else -> return false
        }
        return true
    }

    /** Signs in, or registers when the box is checked, and saves the session. False keeps the form open. */
    private suspend fun signIn(fields: JsonObject, username: String, activeInstance: String): Boolean {
        val enteredUsername = fieldOr(fields, "username", username)
        val password = fields["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val createIfMissing = fields["createAccount"]?.jsonPrimitive?.contentOrNull == "true"
        if (enteredUsername.isEmpty() || password.isEmpty()) {
            coreLog.w { "sign-in on $activeInstance was sent with a missing username or password" }
            setFormStatus("Username and password are required.")
            return false
        }
        val auth = PipedAuthClient(httpClient)
        val token = try {
            auth.login(activeInstance, enteredUsername, password)
        } catch (e: Exception) {
            coreLog.w { "sign-in on $activeInstance as $enteredUsername failed: ${e.message}" }
            if (!createIfMissing) {
                setFormStatus("The username or password is incorrect.")
                return false
            }
            try {
                auth.register(activeInstance, enteredUsername, password)
            } catch (e2: Exception) {
                coreLog.w { "registration on $activeInstance as $enteredUsername failed: ${e2.message}" }
                setFormStatus("Sign-in failed, and registration was rejected by the instance.")
                return false
            }
        }
        session.save(PipedAccount(instance = activeInstance, username = enteredUsername, token = token))
        return true
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

    private suspend fun saveInstance(fields: JsonObject, manage: Boolean) {
        val entered = fields["instance"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (entered.isEmpty()) {
            setFormStatus("Enter the URL of the Piped instance to use.")
            return
        }
        val api = normalizeInstanceUrl(entered) ?: run {
            setFormStatus("The instance URL must start with http:// or https://.")
            return
        }
        val playbackEntered = fields["playback"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val playback = if (playbackEntered.isEmpty()) {
            ""
        } else {
            normalizeInstanceUrl(playbackEntered) ?: run {
                setFormStatus("The playback instance URL must start with http:// or https://.")
                return
            }
        }
        for (url in listOfNotNull(api, playback.takeIf { it.isNotEmpty() })) {
            if (!answersConfig(url)) {
                setFormStatus("Could not reach $url. Check the URL, or try another instance.")
                return
            }
        }
        instanceSource.setApi(api)
        instanceSource.setPlayback(playback)
        val active = instanceSource.api().orEmpty()
        // A session only works on the instance it was created on.
        val saved = session.load()
        if (saved != null && normalizeInstance(saved.instance) != active) session.clear()
        // The form shows who is signed in, so it has to learn that the save kept or dropped the session.
        val sessionKept = session.load() != null
        // The manage form has no sign-in fields, so it must not tell the user to sign in.
        val text = if (manage) "Instance saved." else "Instance saved. Sign in, or continue without an account."
        val script = "if(window.onInstanceSaved){window.onInstanceSaved(" +
            "${json.encodeToString(active)}," +
            "${json.encodeToString(text)}," +
            "$sessionKept);}"
        runCatching { webView.evaluateJavaScript(script) }
    }

    /** True when [instance] answers GET /config with a 2xx JSON object, as every Piped API instance does. */
    private suspend fun answersConfig(instance: String): Boolean {
        val response = try {
            httpClient.request(
                method = HttpMethod.Get,
                url = "$instance/config",
                requestHeaders = mapOf("Accept" to "application/json"),
                body = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coreLog.w { "instance probe of $instance threw: ${e.message}" }
            return false
        }
        if (response.statusCode !in 200..299) {
            coreLog.w { "instance probe of $instance returned ${response.statusCode}" }
            return false
        }
        return runCatching { json.parseToJsonElement(response.body.orEmpty()) }.getOrNull() is JsonObject
    }

    /** A per-form id, so a replayed message from a previous form is ignored. */
    private fun randomFormNonce(): String = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString(36)

    private fun fieldOr(fields: JsonObject, name: String, fallback: String): String =
        fields[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
