package dev.icedborn.spotube_plugin_piped

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.json.Json

/** The search filter values Piped's /search endpoint accepts. */
object PipedSearchFilter {
    const val ALL = "all"
    const val VIDEOS = "videos" // regular YouTube videos, not YouTube Music
    const val CHANNELS = "channels"
    const val PLAYLISTS = "playlists"
    const val MUSIC_SONGS = "music_songs"
    const val MUSIC_VIDEOS = "music_videos"
    const val MUSIC_ALBUMS = "music_albums"
    const val MUSIC_PLAYLISTS = "music_playlists"
    const val MUSIC_ARTISTS = "music_artists"
}

/** Thin JSON client over a Piped API instance. */
class PipedClient(
    private val httpClient: HttpClientAPI,
    private val baseUrlProvider: suspend () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, filter: String): List<PipedSearchItem> {
        val body = get("/search?filter=${filter.percentEncoded()}&q=${query.percentEncoded()}")
        return json.decodeFromString<PipedSearchPage>(body).items.filter { it.type == "stream" }
    }

    suspend fun searchSongs(query: String): List<PipedSearchItem> =
        search(query, PipedSearchFilter.MUSIC_SONGS)

    /** Plain YouTube search, for songs YouTube Music does not index. */
    suspend fun searchVideos(query: String): List<PipedSearchItem> =
        search(query, PipedSearchFilter.VIDEOS)

    suspend fun streams(videoId: String): PipedStreamInfo {
        val body = get("/streams/${videoId.percentEncoded()}")
        return json.decodeFromString<PipedStreamInfo>(body)
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

private fun String.percentEncoded(): String = buildString {
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
