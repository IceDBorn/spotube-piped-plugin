package dev.icedborn.spotube_plugin_piped

import dev.icedborn.spotube_plugin_piped_metadata.pathWithoutQuery
import dev.icedborn.spotube_plugin_piped_metadata.percentEncoded
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
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

private val audioLog = Logger("PipedAudio")

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
            audioLog.w { "search $filter failed: ${t.message}" }
            return null
        }
        // Key-presence doctrine (metadata-twin parity): throttle bodies (200 + '{}'/'{"error":…}') decode to empty
        // pages; only a JSON object with the `items` key is authoritative — anything else is null (failed fetch).
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["items"] !is JsonArray) {
            audioLog.w { "search $filter returned no items array: ${body.take(200)}" }
            return null
        }
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
            audioLog.w { "streams $videoId failed: ${t.message}" }
            return null
        }
        // Gate on the CONSUMED field (audioStreams), not the metadata twin's relatedStreams, which the audio
        // model never reads: a stripped related list with an intact payload must still decode and play (round-108).

        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (root !is JsonObject || root["audioStreams"] !is JsonArray) {
            audioLog.w { "streams $videoId returned no audioStreams array: ${body.take(200)}" }
            return null
        }
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
            // The path is logged without its query string: a search term is the user's text.
            audioLog.w { "Piped ${path.pathWithoutQuery()} -> HTTP ${response.statusCode}: ${response.body?.take(200)}" }
            // No body here: the log line above already carries the truncated one, and this message
            // is itself logged by every orNull/catch upstream, which would print the body twice.
            throw IllegalStateException("Piped ${path.pathWithoutQuery()} failed: HTTP ${response.statusCode}")
        }
        return response.body ?: ""
    }
}

