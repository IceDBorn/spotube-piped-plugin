package dev.icedborn.spotube_plugin_piped.client

import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.percentEncoded
import dev.icedborn.spotube_plugin_piped.store.MirrorRows
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Pages one walk of an account playlist reads by default. */
internal const val ACCOUNT_PAGE_LIMIT = 6

/** One walk of an account playlist. [pages] counts decoded pages; 0 means page 1 failed, which is no evidence
 * of an empty playlist. [lastPageConverted] counts rows of the final page that became tracks. */
internal data class WalkResult(
    val ids: List<String>,
    val rows: List<MetadataTrack>,
    val nextpage: String?,
    val lastPageRows: Int,
    val pages: Int,
    val lastPageConverted: Int,
    // Every row's video id in page order, "" for a row without one.
    val rawIds: List<String>,
)

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun listedCount(entry: JsonObject): Int? = entry.string("videos")?.toIntOrNull()

/** Piped answers a throttled or failed mutation with 200 and '{}' or '{"error":…}', so only another object confirms. */
internal fun postConfirmed(body: String?): Boolean {
    if (body == null) return false
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
    return obj.isNotEmpty() && obj["error"] == null
}

/** Requests against the signed-in account, and walks of its playlists. */
internal class AccountHttp(private val httpClient: HttpClientAPI) {

    /** An unauthenticated GET; a transport failure or non-2xx status is null. */
    suspend fun get(instance: String, path: String): String? = request(HttpMethod.Get, instance, path, null, null)

    suspend fun userGet(account: PipedAccount, path: String): String? = request(HttpMethod.Get, account.instance, path, account.token, null)

    /** An authenticated POST. A transport failure throws, since the write may have landed. */
    suspend fun userPost(account: PipedAccount, path: String, body: String): String? {
        val response = httpClient.request(
            method = HttpMethod.Post,
            url = account.instance.trimEnd('/') + path,
            requestHeaders = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Authorization" to account.token),
            body = body,
        )
        return if (response.statusCode in 200..299) response.body else null
    }

    private suspend fun request(method: HttpMethod, instance: String, path: String, token: String?, body: String?): String? {
        val headers = buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", token)
        }
        val response =
            orNull { httpClient.request(method = method, url = instance.trimEnd('/') + path, requestHeaders = headers, body = body) }
                ?: return null
        return if (response.statusCode in 200..299) response.body else null
    }

    /** The account's playlist listing, or null when it could not be read. */
    suspend fun listing(account: PipedAccount): List<JsonObject>? {
        val body = userGet(account, "/user/playlists") ?: return null
        return runCatching { json.parseToJsonElement(body).jsonArray.map { it.jsonObject } }.getOrNull()
    }

    /** Creates a playlist and returns its id, or null when the answer holds none. */
    suspend fun createPlaylist(account: PipedAccount, name: String): String? {
        val created = userPost(account, "/user/playlists/create", buildJsonObject { put("name", name) }.toString())
        return runCatching { json.parseToJsonElement(created.orEmpty()).jsonObject.string("playlistId") }.getOrNull()
    }

    suspend fun addVideos(account: PipedAccount, playlistId: String, videoIds: List<String>): Boolean {
        val body = buildJsonObject {
            put("playlistId", playlistId)
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        }
        return postConfirmed(userPost(account, "/user/playlists/add", body.toString()))
    }

    suspend fun removeRow(account: PipedAccount, playlistId: String, index: Int): Boolean {
        val body = buildJsonObject {
            put("playlistId", playlistId)
            put("index", index)
        }
        return postConfirmed(userPost(account, "/user/playlists/remove", body.toString()))
    }

    /** Walks an account playlist until the end or [pageLimit] pages. [onPage] gets each page and its leading row
     * offset and answers whether to go on. Returns the trailing token, null at the end. */
    suspend fun walkPages(
        account: PipedAccount,
        playlistId: String,
        pageLimit: Int? = ACCOUNT_PAGE_LIMIT,
        startToken: String? = null,
        onPage: suspend (page: PipedPlaylistPage, seen: Int) -> Boolean,
    ): String? {
        if (startToken != null && startToken.isBlank()) return null
        var nextpage: String? = startToken
        var seen = 0
        var pages = 0
        do {
            val path = if (nextpage.isNullOrBlank()) {
                "/playlists/${playlistId.percentEncoded()}"
            } else {
                "/nextpage/playlists/${playlistId.percentEncoded()}?nextpage=${nextpage.percentEncoded()}"
            }
            val body = userGet(account, path) ?: break
            // A '{}' or error body decodes to null, so a throttle never reads as an empty page.
            val page = runCatching { decodePlaylistPage(body) }.getOrNull() ?: break
            val keepWalking = onPage(page, seen)
            seen += page.relatedStreams.size
            nextpage = page.nextpage
            pages++
            if (!keepWalking) break
            // A blank token ends the walk without proving the end; callers treat only null as the end.
            if (nextpage != null && nextpage.isBlank()) break
        } while (nextpage != null && (pageLimit == null || pages < pageLimit))
        return nextpage
    }

    suspend fun walk(
        account: PipedAccount,
        playlistId: String,
        pageLimit: Int? = ACCOUNT_PAGE_LIMIT,
        startToken: String? = null,
    ): WalkResult {
        val ids = mutableListOf<String>()
        val rows = mutableListOf<MetadataTrack>()
        val rawIds = mutableListOf<String>()
        var lastPageRows = 0
        var lastPageConverted = 0
        var pages = 0
        val nextpage = walkPages(account, playlistId, pageLimit, startToken) { page, _ ->
            pages++
            lastPageRows = page.relatedStreams.size
            lastPageConverted = 0
            page.relatedStreams.forEach { item ->
                val video = videoIdOf(item.url)
                rawIds += video
                if (video.isNotEmpty()) ids += video
                item.toTrack()?.let { track ->
                    rows += track
                    lastPageConverted++
                }
            }
            true
        }
        return WalkResult(ids, rows, nextpage, lastPageRows, pages, lastPageConverted, rawIds)
    }

    /** Walks a mirror to its end. Rows then an empty final page, or a final page with no readable row, is no
     * proven end, so such a walk is not [MirrorRows.complete]. */
    suspend fun walkMirror(account: PipedAccount, playlistId: String): MirrorRows {
        val videos = mutableListOf<String>()
        var pages = 0
        var lastRows = 0
        var lastConverted = 0
        val token = walkPages(account, playlistId, pageLimit = null) { page, _ ->
            pages++
            lastRows = page.relatedStreams.size
            lastConverted = 0
            page.relatedStreams.forEach { item ->
                val video = videoIdOf(item.url)
                if (video.isNotEmpty()) lastConverted++
                videos += video
            }
            true
        }
        val ended = token == null && pages > 0
        val complete = ended && !(pages > 1 && lastRows == 0) && !(lastRows > 0 && lastConverted == 0)
        return MirrorRows(videos, complete, ended)
    }
}
