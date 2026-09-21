package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod

/** Thin JSON client over a Piped API instance; every search filter is supported. */
class PipedClient(
    private val httpClient: HttpClientAPI,
    private val baseUrlProvider: suspend () -> String,
) {

    /** Searches with any Piped filter; pass the previous page's nextpage for more results. */
    suspend fun search(query: String, filter: String, nextpage: String? = null): PipedSearchPage {
        val path = if (nextpage.isNullOrBlank()) "/search" else "/nextpage/search"
        val params = mutableListOf("q=${query.percentEncoded()}", "filter=$filter")
        if (!nextpage.isNullOrBlank()) params.add("nextpage=${nextpage.percentEncoded()}")
        return try {
            json.decodeFromString<PipedSearchPage>(get("$path?${params.joinToString("&")}"))
        } catch (e: Exception) {
            PipedSearchPage()
        }
    }

    suspend fun streams(videoId: String): PipedStreamsInfo {
        val body = get("/streams/${videoId.percentEncoded()}")
        return if (body.isBlank()) PipedStreamsInfo() else json.decodeFromString(body)
    }

    suspend fun playlist(playlistId: String): PipedPlaylistPage {
        val body = get("/playlists/${playlistId.percentEncoded()}")
        return if (body.isBlank()) PipedPlaylistPage() else json.decodeFromString(body)
    }

    suspend fun playlistNextPage(playlistId: String, nextpage: String): PipedPlaylistPage {
        val body = get("/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}")
        return if (body.isBlank()) PipedPlaylistPage() else json.decodeFromString(body)
    }

    suspend fun channel(channelId: String): PipedChannelInfo {
        val body = get("/channel/${channelId.percentEncoded()}")
        return if (body.isBlank()) PipedChannelInfo(id = channelId) else json.decodeFromString(body)
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
