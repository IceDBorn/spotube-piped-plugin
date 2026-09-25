package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val clientLog = Logger("PipedClient")

    /** Decodes a /playlists page: null = FAILED fetch, never authoritative-empty. '{}'/'{"error":…}'
     * (throttles) decodes to an empty page that reads as proven-empty everywhere — playlist(), mirror walk. */
internal fun decodePlaylistPage(body: String): PipedPlaylistPage? {
    if (body.isBlank()) return null
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
    if (root !is JsonObject || root["relatedStreams"] !is JsonArray) {
        clientLog.w { "playlist page without relatedStreams: ${body.take(200)}" }
        return null
    }
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
        if (root !is JsonObject || root["items"] !is JsonArray) {
            clientLog.w { "${path.pathWithoutQuery()} filter=$filter returned no items array: ${body.take(200)}" }
            return null
        }
        return json.decodeFromString(body)
    }

    suspend fun streamsForMetadata(videoId: String): PipedStreamsInfo? {
        val body = get("/streams/${videoId.percentEncoded()}")
        // Same key-presence doctrine: a blank body or a JSON object WITHOUT `relatedStreams` is a FAILED fetch
        // (throttle), never authoritative — the default-coerced decode must not be cached as real metadata.
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) {
            clientLog.w { "streams $videoId without relatedStreams: ${body.take(200)}" }
            return null
        }
        return json.decodeFromString(body)
    }

    /** The same /streams body read for playback. Audio consumes [PipedStreamsInfo.audioStreams] and never
     * reads relatedStreams, so the gate is on the field the caller uses (round-108). */
    suspend fun streamsForAudio(videoId: String): PipedStreamsInfo? {
        val body = get("/streams/${videoId.percentEncoded()}")
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["audioStreams"] !is JsonArray) {
            clientLog.w { "streams $videoId without audioStreams: ${body.take(200)}" }
            return null
        }
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
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) {
            clientLog.w { "playlist $playlistId page without relatedStreams: ${body.take(200)}" }
            return null
        }
        return json.decodeFromString(body)
    }

    suspend fun channel(channelId: String): PipedChannelInfo? {
        val body = get("/channel/${channelId.percentEncoded()}")
        // Same doctrine: a blank body or a JSON object WITHOUT `relatedStreams` is a FAILED fetch (throttle),
        // never authoritative — the coerced blank channel would be cached forever (CHANNEL_KEY is never invalidated).

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["relatedStreams"] !is JsonArray) {
            clientLog.w { "channel $channelId without relatedStreams: ${body.take(200)}" }
            return null
        }
        return json.decodeFromString(body)
    }

    /** Every playlist on a channel's playlists tab as (playlistId, name), walking at most [pageLimit] pages.
     * Null when the first fetch fails, so callers can keep an older copy. */
    suspend fun channelPlaylists(channelId: String, pageLimit: Int = 20): List<Pair<String, String>>? {
        val channel = orNull { json.parseToJsonElement(get("/channel/${channelId.percentEncoded()}")) } as? JsonObject
            ?: return null
        val tab = (channel["tabs"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == "playlists" }
        val data = (tab?.get("data") as? JsonPrimitive)?.contentOrNull ?: return null
        val out = mutableListOf<Pair<String, String>>()
        var nextpage: String? = null
        for (page in 0 until pageLimit) {
            val query = "data=${data.percentEncoded()}" + (nextpage?.let { "&nextpage=${it.percentEncoded()}" } ?: "")
            val root = orNull { json.parseToJsonElement(get("/channels/tabs?$query")) } as? JsonObject
                ?: return if (page == 0) null else out
            (root["content"] as? JsonArray).orEmpty().forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val id = playlistIdOf((obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isNotEmpty() && name.isNotEmpty()) out += id to name
            }
            nextpage = (root["nextpage"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: break
        }
        return out
    }

    private suspend fun get(path: String): String {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = baseUrlProvider().trimEnd('/') + path,
            requestHeaders = mapOf("Accept" to "application/json"),
            body = null,
        )
        if (response.statusCode !in 200..299) {
            // The path is logged without its query string: a search term is the user's text.
            clientLog.w { "Piped ${path.pathWithoutQuery()} -> HTTP ${response.statusCode}: ${response.body?.take(200)}" }
            // No body here: the log line above already carries the truncated one, and this message
            // is itself logged by every orNull/catch upstream, which would print the body twice.
            throw IllegalStateException("Piped ${path.pathWithoutQuery()} failed: HTTP ${response.statusCode}")
        }
        return response.body ?: ""
    }
}

