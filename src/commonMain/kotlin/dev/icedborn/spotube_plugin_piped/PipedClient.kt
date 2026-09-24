package dev.icedborn.spotube_plugin_piped

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

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
    private val json = Json {
        ignoreUnknownKeys = true
        // Piped returns null for fields declared non-nullable (title, views, duration...); coerceInputValues maps
        // them to defaults so documented-shape bodies DECODE (chains reach the backup verdict), not throw (round-103).
        coerceInputValues = true
    }

    suspend fun search(query: String, filter: String): List<PipedSearchItem>? {
        // get() throws on every non-2xx (HTTP 429 quota throttles are routine); absorb them as FAILED-fetch
        // nulls, or a 429 on music_songs aborts getStreamsByTrack before the plain-YouTube backup runs (round-107).
        val body = try {
            get("/search?filter=${filter.percentEncoded()}&q=${query.percentEncoded()}")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            return null
        }
        // Key-presence doctrine (metadata-twin parity): throttle bodies (200 + '{}'/'{"error":…}') decode to empty
        // pages; only a JSON object with the `items` key is authoritative — anything else is null (failed fetch).
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["items"] !is JsonArray) return null
        // Accept "stream" and "video" rows (music_songs can carry either). A keyed body that fails to decode
        // is also a FAILED-fetch null, same contract.
        return runCatching { json.decodeFromString<PipedSearchPage>(body) }
            .getOrNull()?.items?.filter { it.type == "stream" || it.type == "video" }
    }

    suspend fun searchSongs(query: String): List<PipedSearchItem>? =
        search(query, PipedSearchFilter.MUSIC_SONGS)

    /** Plain YouTube search, for songs YouTube Music does not index. */
    suspend fun searchVideos(query: String): List<PipedSearchItem>? =
        search(query, PipedSearchFilter.VIDEOS)

    suspend fun streams(videoId: String): PipedStreamInfo? {
        // Same absorption as search(): a throttled /streams is a FAILED-fetch null so the host tries the next
        // candidate instead of aborting the whole MAX_SOURCES chain (round-107).
        val body = try {
            get("/streams/${videoId.percentEncoded()}")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            return null
        }
        // Gate on the CONSUMED field (audioStreams), not the metadata twin's relatedStreams, which the audio
        // model never reads: a stripped related list with an intact payload must still decode and play (round-108).

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["audioStreams"] !is JsonArray) return null
        return runCatching { json.decodeFromString<PipedStreamInfo>(body) }.getOrNull()
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