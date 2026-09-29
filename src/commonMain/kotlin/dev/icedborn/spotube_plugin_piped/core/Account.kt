package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ACCOUNT_KEY = "piped.account"

/** Anything bound to one account: the instance it lives on and the username. */
internal interface AccountIdentity {
    val instance: String
    val username: String
}

/** [url] trimmed and without a trailing slash, the form instances are compared in. */
internal fun normalizeInstance(url: String): String = url.trim().trimEnd('/')

/** The device-wide owner stamp of an account, "instance|username". */
internal fun accountStamp(account: AccountIdentity): String = "${normalizeInstance(account.instance)}|${account.username}"

internal fun sameAccount(a: AccountIdentity, b: AccountIdentity): Boolean =
    normalizeInstance(a.instance) == normalizeInstance(b.instance) && a.username == b.username

/** A per-instance Piped account; token is the session id returned by /login. */
@Serializable
internal data class PipedAccount(override val instance: String, override val username: String = "", val token: String = "") :
    AccountIdentity

internal class AccountSession(private val store: EntityStore) {

    suspend fun load(): PipedAccount? = store.getDecoded(ACCOUNT_KEY, PipedAccount.serializer())

    suspend fun save(account: PipedAccount) {
        store.put(ACCOUNT_KEY, json.encodeToJsonElement(account))
    }

    suspend fun clear() = store.remove(ACCOUNT_KEY)
}

/** POST /login and POST /register on a Piped instance; both return the session token. */
internal class PipedAuthClient(private val httpClient: HttpClientAPI) {

    suspend fun login(instance: String, username: String, password: String): String = authenticate("login", instance, username, password)

    suspend fun register(instance: String, username: String, password: String): String =
        authenticate("register", instance, username, password)

    private suspend fun authenticate(path: String, instance: String, username: String, password: String): String {
        val response = httpClient.request(
            method = HttpMethod.Post,
            url = "${instance.trimEnd('/')}/$path",
            requestHeaders = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = json.encodeToString(
                buildJsonObject {
                    put("username", username)
                    put("password", password)
                },
            ),
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Piped /$path failed: HTTP ${response.statusCode}")
        }
        val token = runCatching {
            json.parseToJsonElement(response.body.orEmpty()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (token.isNullOrBlank()) throw IllegalStateException("Piped /$path returned no token")
        return token
    }
}
