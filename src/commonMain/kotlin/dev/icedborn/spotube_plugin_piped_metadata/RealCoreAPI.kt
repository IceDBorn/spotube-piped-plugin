package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.swiftzer.semver.SemVer

private const val FORM_WAIT_MS = 900_000L

/**
 * Piped needs no account for metadata, but a per-instance account enables
 * write-through saves to the "Spotube - Albums/Artists/Favorites" playlists and
 * an offline cache of the account state. The login button shown by the host
 * opens the plugin's settings form.
 */
class RealCoreAPI(
    private val httpClient: HttpClientAPI,
    private val storage: PersistedStorageAPI,
    private val webView: WebViewAPI,
    private val session: AccountSession,
    private val instanceSource: InstanceSource,
    private val onLogin: suspend () -> Unit = {},
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

    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo? = null

    override fun supportMarkdownText(currentVersion: SemVer): String =
        "YouTube Music metadata (tracks, albums, artists, playlists) via a Piped instance." +
            " There is no default instance: open Settings, choose Piped and save the URL" +
            " of an instance from the TeamPiped list (github.com/TeamPiped/Piped/wiki/Instances)." +
            " Optionally give it a separate playback instance for audio." +
            " Log in on that instance to sync saved items to the account and keep an" +
            " offline cache on this device; without an account everything stays local." +
            " Source: https://github.com/IceDBorn/spotube-piped-plugins"

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun login() {
        try {
            loggedInStateFlow.value = false
            val existing = session.load()
            showSettingsForm(
                instanceSource.api().orEmpty(),
                instanceSource.playback().orEmpty(),
                existing?.username.orEmpty(),
            )
            val account = session.load() ?: throw IllegalStateException("Piped login failed")
            loggedInStateFlow.value = true
            onLogin()
        } finally {
            webView.exitWebView()
        }
    }

    override suspend fun logout() {
        session.clear()
        loggedInStateFlow.value = false
    }

    private suspend fun showSettingsForm(instance: String, playback: String, username: String) {
        val messages = Channel<String>(Channel.CONFLATED)
        val subscriber = scope.launch {
            webView.postMessagesFlow().onEach { messages.trySend(it) }.launchIn(this)
            webView.navigateToHTML(settingsFormHtml(instance, playback, username))
        }
        try {
            while (true) {
                val message = withTimeoutOrNull(FORM_WAIT_MS) { messages.receive() }
                    ?: throw IllegalStateException("the settings form was closed without saving")
                val fields = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: continue
                val action = fields["action"]?.jsonPrimitive?.contentOrNull
                val enteredInstance = fieldOr(fields, "instance", instance)
                val enteredPlayback = fields["playback"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (enteredInstance.isEmpty()) {
                    setFormStatus("Enter the URL of the Piped instance to use.")
                    continue
                }
                if (action == "instance") {
                    instanceSource.setApi(enteredInstance)
                    instanceSource.setPlayback(enteredPlayback)
                    setFormStatus("Instance saved. Piped uses it for all requests.")
                    continue
                }
                if (action != "login") continue
                val enteredUsername = fieldOr(fields, "username", username)
                val password = fields["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val createIfMissing = fields["createAccount"]?.jsonPrimitive?.contentOrNull == "true"
                if (enteredUsername.isEmpty() || password.isEmpty()) {
                    setFormStatus("Username and password are required.")
                    continue
                }
                instanceSource.setApi(enteredInstance)
                instanceSource.setPlayback(enteredPlayback)
                val auth = PipedAuthClient(httpClient)
                val token = try {
                    auth.login(enteredInstance, enteredUsername, password)
                } catch (e: Exception) {
                    if (createIfMissing) {
                        try {
                            auth.register(enteredInstance, enteredUsername, password)
                        } catch (e2: Exception) {
                            setFormStatus("Sign-in failed, and registration was rejected by the instance.")
                            continue
                        }
                    } else {
                        setFormStatus("The username or password is incorrect.")
                        continue
                    }
                }
                session.save(PipedAccount(instance = enteredInstance, username = enteredUsername, token = token))
                return
            }
            error("unreachable")
        } finally {
            subscriber.cancel()
        }
    }

    /** The host cannot reply to the form directly, so feedback is pushed with evaluateJavaScript. */
    private suspend fun setFormStatus(text: String) {
        val script = "var s=document.getElementById('status');" +
            "if(s){s.className='error';s.textContent=${json.encodeToString(text)};}" +
            "var b=document.getElementById('save');if(b){b.disabled=false;}"
        runCatching { webView.evaluateJavaScript(script) }
    }

    private fun fieldOr(fields: kotlinx.serialization.json.JsonObject, name: String, fallback: String): String =
        fields[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
