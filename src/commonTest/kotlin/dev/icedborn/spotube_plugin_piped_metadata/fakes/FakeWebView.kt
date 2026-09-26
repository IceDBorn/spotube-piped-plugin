package dev.icedborn.spotube_plugin_piped_metadata.fakes

import dev.krtirtho.plugin_interfaces.host_apis.Cookie
import dev.krtirtho.plugin_interfaces.host_apis.WebViewAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** Records what the plugin shows and lets a test push bridge messages back. */
class FakeWebView : WebViewAPI {

    val pages = mutableListOf<String>()
    val scripts = mutableListOf<String>()
    var exitCount = 0

    /** Makes the form fail to open, the way a host that cannot create a web view does. */
    var failOnNavigate: Throwable? = null

    private val messages = MutableSharedFlow<String>(replay = 1)

    override fun navigateTo(url: String) {
        pages += url
    }

    override fun navigateToHTML(html: String) {
        failOnNavigate?.let { throw it }
        pages += html
    }

    override suspend fun getCookies(url: String): List<Cookie> = emptyList()

    override suspend fun evaluateJavaScript(script: String): String? {
        scripts += script
        return null
    }

    override fun urlChangeFlow(): Flow<String> = emptyFlow()

    override fun webviewCreatedFlow(): Flow<Unit> = emptyFlow()

    override fun postMessagesFlow(): Flow<String> = messages

    override fun exitWebView() {
        exitCount++
    }

    /** The nonce the shown form carries, so a test can post a message the loop accepts. */
    fun nonceOf(html: String): String = Regex("var nonce = '([^']*)'").find(html)?.groupValues?.get(1).orEmpty()

    suspend fun post(message: String) {
        messages.emit(message)
    }

    suspend fun post(nonce: String, action: String) = post("""{"action":"$action","nonce":"$nonce"}""")
}
