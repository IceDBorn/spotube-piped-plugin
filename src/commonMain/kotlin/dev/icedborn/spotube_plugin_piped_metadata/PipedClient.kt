package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

    /** Decodes a /playlists page: null = FAILED fetch, never authoritative-empty. '{}'/'{"error":…}'
     * (throttles) decodes to an empty page that reads as proven-empty everywhere — playlist(), mirror walk. */
internal fun decodePlaylistPage(body: String): PipedPlaylistPage? {
    if (body.isBlank()) return null
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
    if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
    return json.decodeFromString(body)
}

/** Thin JSON client over a Piped API instance; every search filter is supported. */
class PipedClient(
    private val httpClient: HttpClientAPI,
    private val baseUrlProvider: suspend () -> String,
) {

    /** Searches with any Piped filter; pass the previous page's nextpage for more results. */
    suspend fun search(query: String, filter: String, nextpage: String? = null): PipedSearchPage? {
        val path = if (nextpage.isNullOrBlank()) "/search" else "/nextpage/search"
        val params = mutableListOf("q=${query.percentEncoded()}", "filter=$filter")
        if (!nextpage.isNullOrBlank()) params.add("nextpage=${nextpage.percentEncoded()}")
        val body = get("$path?${params.joinToString("&")}")
        // FAILED/ambiguous shapes return null, never an empty page: '{}'/'{"error":…}' decodes to an all-defaults
        // zero-hit (cached 'none' forever); only a JSON object with the `items` key is authoritative.

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["items"] !is JsonArray) return null
        return json.decodeFromString(body)
    }

    suspend fun streams(videoId: String): PipedStreamsInfo? {
        val body = get("/streams/${videoId.percentEncoded()}")
        // Same key-presence doctrine: a blank body or a JSON object WITHOUT `relatedStreams` is a FAILED fetch
        // (throttle), never authoritative — the default-coerced decode must not be cached as real metadata.
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
        return json.decodeFromString(body)
    }

    suspend fun playlist(playlistId: String): PipedPlaylistPage? {
        val body = get("/playlists/${playlistId.percentEncoded()}")
        // A blank body or a JSON body WITHOUT `relatedStreams` is a FAILED fetch, never an authoritative empty
        // playlist. Writers must not cache null: empty page-1 frozen behind complete=true shows empty forever.

        return decodePlaylistPage(body)
    }

    suspend fun playlistNextPage(playlistId: String, nextpage: String): PipedPlaylistPage? {
        val body = get("/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}")
        // Same key-presence doctrine: a blank body or a JSON object WITHOUT `relatedStreams` is a FAILED
        // continuation fetch (throttle) — a fabricated all-defaults page + null token reads as proven EOF.

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
        return json.decodeFromString(body)
    }

    suspend fun channel(channelId: String): PipedChannelInfo? {
        val body = get("/channel/${channelId.percentEncoded()}")
        // Same doctrine: a blank body or a JSON object WITHOUT `relatedStreams` is a FAILED fetch (throttle),
        // never authoritative — the coerced blank channel would be cached forever (CHANNEL_KEY is never invalidated).

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) return null
        return json.decodeFromString(body)
    }

    suspend fun trending(region: String): List<PipedSearchItem> {
        val body = get("/trending?region=$region")
        return try {
            json.decodeFromString<List<PipedSearchItem>>(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun get(path: String): String {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = baseUrlProvider().trimEnd('/') + path,
            requestHeaders = mapOf("Accept" to "application/json"),
            body = null,
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Piped $path failed: HTTP ${response.statusCode} - ${response.body}")
        }
        return response.body ?: ""
    }
}

private const val HEX = "0123456789ABCDEF"

internal fun String.percentEncoded(): String = buildString {
    for (c in this@percentEncoded) {
        if (c.isLetterOrDigit() || c in "-_.~") {
            append(c)
        } else {
            val bytes = c.toString().encodeToByteArray()
            for (b in bytes) {
                append('%').append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
    }
}