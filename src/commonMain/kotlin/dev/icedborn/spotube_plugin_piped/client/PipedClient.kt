package dev.icedborn.spotube_plugin_piped.client

import dev.icedborn.spotube_plugin_piped.core.pathWithoutQuery
import dev.icedborn.spotube_plugin_piped.core.percentEncoded
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer

private val clientLog = Logger("PipedClient")

/** Parses [body] once and decodes it when it is a JSON object holding the array [key]. Any other shape, such as
 * a blank body or a throttle's `{}`, is a failed fetch (null), never an empty result. */
internal fun <T> decodeKeyed(body: String?, key: String, serializer: KSerializer<T>, logLabel: String? = null): T? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
    if (root !is JsonObject || root[key] !is JsonArray) {
        if (logLabel != null) clientLog.w { "$logLabel without $key: ${body.take(200)}" }
        return null
    }
    return runCatching { json.decodeFromJsonElement(serializer, root) }.getOrNull()
}

internal inline fun <reified T> decodeKeyed(body: String?, key: String, logLabel: String? = null): T? =
    decodeKeyed(body, key, serializer<T>(), logLabel)

/** A /playlists page, or null for a failed fetch. */
internal fun decodePlaylistPage(body: String?): PipedPlaylistPage? = decodeKeyed(body, "relatedStreams", "playlist page")

/** Thin JSON client over a Piped API instance; every search filter is supported. */
internal class PipedClient(private val httpClient: HttpClientAPI, private val baseUrlProvider: suspend () -> String) {

    /** Searches with any Piped filter; pass the previous page's nextpage for more results. */
    suspend fun search(query: String, filter: String, nextpage: String? = null): PipedSearchPage? {
        val path = if (nextpage.isNullOrBlank()) "/search" else "/nextpage/search"
        val params = mutableListOf("q=${query.percentEncoded()}", "filter=$filter")
        if (!nextpage.isNullOrBlank()) params.add("nextpage=${nextpage.percentEncoded()}")
        return decodeKeyed(get("$path?${params.joinToString("&")}"), "items", "${path.pathWithoutQuery()} filter=$filter")
    }

    suspend fun streamsForMetadata(videoId: String): PipedStreamsInfo? =
        decodeKeyed(get("/streams/${videoId.percentEncoded()}"), "relatedStreams", "streams $videoId")

    /** 403/404 refuse the URL; a 2xx serves it only with an audio or video Content-Type, the rest is null.
     * HEAD because the proxy ignores `Range` and a GET would download the whole file. */
    suspend fun servesMediaUrl(url: String): Boolean? {
        val response = httpClient.request(
            method = HttpMethod.Head,
            url = url,
            requestHeaders = null,
            body = null,
        )
        val contentType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val isMedia = contentType != null && (contentType.startsWith("audio/", true) || contentType.startsWith("video/", true))
        return when (response.statusCode) {
            in 200..299 -> if (isMedia) true else null
            403, 404 -> false
            else -> null
        }
    }

    /** The same /streams body read for playback. Audio consumes [PipedStreamsInfo.audioStreams] and never
     * reads relatedStreams, so the gate is on the field the caller uses. */
    suspend fun streamsForAudio(videoId: String): PipedStreamsInfo? =
        decodeKeyed(get("/streams/${videoId.percentEncoded()}"), "audioStreams", "streams $videoId")

    suspend fun playlist(playlistId: String): PipedPlaylistPage? = decodePlaylistPage(get("/playlists/${playlistId.percentEncoded()}"))

    suspend fun playlistNextPage(playlistId: String, nextpage: String): PipedPlaylistPage? = decodeKeyed(
        get("/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"),
        "relatedStreams",
        "playlist $playlistId page",
    )

    suspend fun channel(channelId: String): PipedChannelInfo? =
        decodeKeyed(get("/channel/${channelId.percentEncoded()}"), "relatedStreams", "channel $channelId")

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
