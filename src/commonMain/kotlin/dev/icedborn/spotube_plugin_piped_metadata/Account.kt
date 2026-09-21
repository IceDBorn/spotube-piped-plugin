package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

private const val ACCOUNT_KEY = "piped.account"

/** A per-instance Piped account; token is the session id returned by /login. */
@Serializable
data class PipedAccount(
    val instance: String,
    val username: String = "",
    val token: String = "",
)

class AccountSession(private val store: EntityStore) {

    suspend fun load(): PipedAccount? {
        val raw = store.get(ACCOUNT_KEY) ?: return null
        return runCatching { json.decodeFromJsonElement<PipedAccount>(raw) }.getOrNull()
    }

    suspend fun save(account: PipedAccount) {
        store.put(ACCOUNT_KEY, json.encodeToJsonElement(account))
    }

    suspend fun clear() = store.remove(ACCOUNT_KEY)
}

/** POST /login and POST /register on a Piped instance; both return the session token. */
class PipedAuthClient(private val httpClient: HttpClientAPI) {

    suspend fun login(instance: String, username: String, password: String): String =
        authenticate("login", instance, username, password)

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
