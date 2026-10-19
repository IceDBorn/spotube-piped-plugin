package dev.icedborn.spotube_plugin_piped.client

import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val NOT_CONFIGURED = "not configured"
private val STORAGE_NAME = Regex("^[a-z0-9]{1,32}$")

internal fun isStorageName(name: String): Boolean = STORAGE_NAME.matches(name)

internal sealed interface StorageStat {
    data class Exists(val etag: String) : StorageStat

    data object Missing : StorageStat

    data object Unsupported : StorageStat
}

internal enum class StoragePut { OK, CONFLICT, UNSUPPORTED, FAILED }

/** The Piped storage routes. Files live under the signed-in user, so every call needs the session. */
internal class StorageHttp(private val httpClient: HttpClientAPI) {

    suspend fun s3Enabled(instance: String): Boolean? {
        val (status, body) = send(HttpMethod.Get, instance, "/config", emptyMap(), null) ?: return null
        if (status !in 200..299) return null
        return parseObject(body)?.get("s3Enabled")?.jsonPrimitive?.booleanOrNull
    }

    suspend fun stat(account: PipedAccount, file: String): StorageStat? {
        require(isStorageName(file))
        val (_, body) = send(HttpMethod.Get, account.instance, "/storage/stat?file=$file", auth(account), null) ?: return null
        val answer = parseObject(body) ?: return null
        answer.string("error")?.let { return if (it.contains(NOT_CONFIGURED)) StorageStat.Unsupported else null }
        return when (answer.string("status")) {
            "exists" -> answer.string("etag")?.let { StorageStat.Exists(it) }
            "not_exists" -> StorageStat.Missing
            else -> null
        }
    }

    suspend fun get(account: PipedAccount, file: String): String? {
        require(isStorageName(file))
        val (status, body) = send(HttpMethod.Get, account.instance, "/storage/get?file=$file", auth(account), null) ?: return null
        return body?.takeIf { status in 200..299 && it.isNotBlank() }
    }

    suspend fun put(account: PipedAccount, file: String, body: String, etag: String?): StoragePut {
        require(isStorageName(file))
        val headers = auth(account) + buildMap {
            put("x-file-name", file)
            put("Content-Type", "application/octet-stream")
            if (etag != null) put("x-last-etag", etag)
        }
        val (_, answer) = send(HttpMethod.Post, account.instance, "/storage/put", headers, body) ?: return StoragePut.FAILED
        val parsed = parseObject(answer) ?: return StoragePut.FAILED
        val error = parsed.string("error")
        return when {
            error == null && parsed.string("status") == "ok" -> StoragePut.OK
            error?.contains("etag") == true -> StoragePut.CONFLICT
            error?.contains(NOT_CONFIGURED) == true -> StoragePut.UNSUPPORTED
            else -> StoragePut.FAILED
        }
    }

    private fun auth(account: PipedAccount) = mapOf("Authorization" to account.token)

    private suspend fun send(
        method: HttpMethod,
        instance: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String?>? {
        val url = instance.trimEnd('/') + path
        val requestHeaders = headers + ("Accept" to "application/json")
        val response = orNull { httpClient.request(method = method, url = url, requestHeaders = requestHeaders, body = body) }
            ?: return null
        return response.statusCode to response.body
    }

    private fun parseObject(body: String?): JsonObject? =
        body?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}
