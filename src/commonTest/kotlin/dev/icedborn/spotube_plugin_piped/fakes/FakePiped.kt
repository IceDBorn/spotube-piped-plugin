package dev.icedborn.spotube_plugin_piped.fakes

import dev.icedborn.spotube_plugin_piped.client.json
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val FAKE_INSTANCE = "https://piped.example"

/** The video id shape the fixtures use: "vid" + a 7-digit index + "0". */
fun vid(index: Int): String = "vid" + index.toString().padStart(7, '0') + "0"

/** A stateful Piped instance: account playlists with paging, public playlists, streams, search and channels.
 * Pages hold [pageSize] rows and continue through "p:<offset>" tokens. */
class FakePiped(var pageSize: Int = 2) : HttpClientAPI {

    class Playlist(val id: String, var name: String, val videos: MutableList<String>) {
        /** The stored position of the first row. Piped can leave it above 0 when an add interleaves with a removal. */
        var firstPosition = 0
    }

    val requests = mutableListOf<Pair<HttpMethod, String>>()
    val bodies = mutableListOf<Pair<String, String?>>()

    /** Playlists on the signed-in account, in listing order. */
    val account = LinkedHashMap<String, Playlist>()

    /** Public playlists (albums, charts, radio mixes) by id. */
    val public = HashMap<String, Playlist>()

    /** Video id to uploader name; a video missing here still resolves with "Artist". */
    val uploaders = HashMap<String, String>()

    /** Video id to title; a video missing here is titled "Title <id>". */
    val titles = HashMap<String, String>()

    /** Video ids whose /streams answers 500. */
    val deadVideos = HashSet<String>()

    /** Video ids a playlist add skips, the way Piped skips a video it cannot fetch from YouTube. */
    val unfetchable = HashSet<String>()
    val channels = HashMap<String, String>()
    val users = HashMap<String, String>()
    var registrationOpen = true

    /** Virtual delay before every answer. */
    var latencyMs = 0L
    private val searches = mutableListOf<Pair<(String) -> Boolean, List<JsonObject>>>()
    private val overrides = mutableListOf<Override>()
    private var created = 0

    private class Override(
        val match: (HttpMethod, String) -> Boolean,
        var times: Int,
        val response: HttpResponse?,
        val error: Throwable? = null,
    )

    /** Makes matching requests throw, the way a host HTTP client fails on a timeout. */
    fun throwOn(fragment: String, error: Throwable = IllegalStateException("timeout"), method: HttpMethod? = null) {
        overrides += Override({ m, url -> (method == null || m == method) && url.contains(fragment) }, Int.MAX_VALUE, null, error)
    }

    /** Answers the next [times] matching requests with [status] and [body] instead of the fake's own state. */
    fun override(fragment: String, status: Int = 200, body: String = "{}", times: Int = Int.MAX_VALUE, method: HttpMethod? = null) {
        overrides += Override(
            { m, url -> (method == null || m == method) && url.contains(fragment) },
            times,
            HttpResponse(status, emptyMap(), body),
        )
    }

    /** Search rows for any query whose URL contains [fragment]. */
    fun search(fragment: String, items: List<JsonObject>) {
        searches += { url: String -> url.contains(fragment) } to items
    }

    fun count(fragment: String, method: HttpMethod? = null): Int =
        requests.count { (m, url) -> (method == null || m == method) && url.contains(fragment) }

    val total: Int get() = requests.size

    fun reset() {
        requests.clear()
        bodies.clear()
    }

    fun accountPlaylist(name: String, videos: List<String>, id: String = "pl-${++created}"): Playlist =
        Playlist(id, name, videos.toMutableList()).also { account[id] = it }

    fun publicPlaylist(id: String, name: String, videos: List<String>): Playlist =
        Playlist(id, name, videos.toMutableList()).also { public[id] = it }

    override suspend fun request(method: HttpMethod, url: String, requestHeaders: Map<String, String>?, body: String?): HttpResponse {
        requests += method to url
        bodies += url to body
        if (latencyMs > 0) delay(latencyMs)
        overrides.firstOrNull { it.times > 0 && it.match(method, url) }?.let {
            it.times--
            it.error?.let { e -> throw e }
            return it.response!!
        }
        val path = "/" + url.substringAfter("://").substringAfter('/', "")
        return when {
            method == HttpMethod.Post -> post(path, body.orEmpty())

            path == "/user/playlists" -> listing(requestHeaders)

            path.startsWith("/playlists/") -> page(path.removePrefix("/playlists/"), 0)

            path.startsWith("/nextpage/playlists/") -> {
                val id = path.removePrefix("/nextpage/playlists/").substringBefore('?')
                val token = decode(path.substringAfter("nextpage="))
                page(id, token.removePrefix("p:").toIntOrNull() ?: return notFound())
            }

            path.startsWith("/streams/") -> streams(path.removePrefix("/streams/"))

            path.startsWith("/search") || path.startsWith("/nextpage/search") -> searchPage(url)

            path.startsWith("/channel/") -> channel(path.removePrefix("/channel/"))

            path == "/config" -> ok("{}")

            else -> notFound()
        }
    }

    private fun listing(headers: Map<String, String>?): HttpResponse {
        if (headers?.get("Authorization").isNullOrBlank()) return HttpResponse(401, emptyMap(), "")
        val body = buildJsonArray {
            account.values.forEach { p ->
                add(
                    buildJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        put("videos", p.videos.size)
                    },
                )
            }
        }
        return ok(body.toString())
    }

    private fun page(id: String, offset: Int): HttpResponse {
        val playlist = account[id] ?: public[id] ?: return notFound()
        val rows = playlist.videos.drop(offset).take(pageSize)
        val end = offset + rows.size
        val body = buildJsonObject {
            put("name", playlist.name)
            put("videos", playlist.videos.size)
            put("uploader", "Alpha Artist")
            put("uploaderUrl", "/channel/UCchannelalpha01a0000000")
            if (end < playlist.videos.size) put("nextpage", "p:$end") else put("nextpage", null as String?)
            put("relatedStreams", JsonArray(rows.map { row(it) }))
        }
        return ok(body.toString())
    }

    fun row(videoId: String, title: String = titles[videoId] ?: "Title $videoId"): JsonObject = buildJsonObject {
        put("type", "stream")
        put("url", "/watch?v=$videoId")
        put("title", title)
        put("uploaderName", uploaders[videoId] ?: "Artist")
        put("uploaderUrl", "/channel/UCchannelalpha01a0000000")
        put("duration", 200)
    }

    fun albumRow(playlistId: String, name: String, uploader: String): JsonObject = buildJsonObject {
        put("type", "playlist")
        put("url", "/playlist?list=$playlistId")
        put("name", name)
        put("uploaderName", uploader)
    }

    private fun streams(videoId: String): HttpResponse {
        if (videoId in deadVideos) return HttpResponse(500, emptyMap(), "")
        val body = buildJsonObject {
            put("title", titles[videoId] ?: "Title $videoId")
            put("duration", 200)
            put("uploader", uploaders[videoId] ?: "Artist")
            put("uploaderUrl", "/channel/UCchannelalpha01a0000000")
            put("relatedStreams", JsonArray(emptyList()))
            put("audioStreams", JsonArray(emptyList()))
        }
        return ok(body.toString())
    }

    private fun searchPage(url: String): HttpResponse {
        val items = searches.firstOrNull { it.first(url) }?.second.orEmpty()
        return ok(
            buildJsonObject {
                put("items", JsonArray(items))
                put("nextpage", null as String?)
            }.toString(),
        )
    }

    private fun channel(id: String): HttpResponse {
        val name = channels[id] ?: return notFound()
        return ok(
            buildJsonObject {
                put("id", id)
                put("name", name)
                put("avatarUrl", "")
                put("relatedStreams", JsonArray(emptyList()))
            }.toString(),
        )
    }

    private fun post(path: String, body: String): HttpResponse {
        val fields = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun field(name: String) = (fields[name] as? JsonPrimitive)?.contentOrNull
        val done = """{"message":"ok"}"""
        return when (path) {
            "/login" -> {
                val user = field("username")
                if (user != null &&
                    users[user] == field("password")
                ) {
                    ok("""{"token":"token-$user"}""")
                } else {
                    HttpResponse(401, emptyMap(), "")
                }
            }

            "/register" -> {
                val user = field("username").orEmpty()
                if (!registrationOpen || user in users) return HttpResponse(400, emptyMap(), """{"error":"no"}""")
                users[user] = field("password").orEmpty()
                ok("""{"token":"token-$user"}""")
            }

            "/user/playlists/create" -> {
                val p = accountPlaylist(field("name").orEmpty(), emptyList())
                ok("""{"playlistId":"${p.id}"}""")
            }

            "/user/playlists/add" -> {
                val p = account[field("playlistId")] ?: return ok("""{"error":"Playlist not found"}""")
                val ids = (fields["videoIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                val added = ids.filterNot { it in unfetchable }
                if (ids.isNotEmpty() && added.isEmpty()) {
                    return ok("""{"error":"Unable to add any videos, since they were unable to be fetched"}""")
                }
                p.videos += added
                ok(done)
            }

            "/user/playlists/remove" -> {
                val p = account[field("playlistId")] ?: return notFound()
                val index = (fields["index"]?.jsonPrimitive?.int ?: return notFound()) - p.firstPosition
                if (index !in p.videos.indices) return ok("""{"error":"Video Index not found"}""")
                p.videos.removeAt(index)
                ok(done)
            }

            "/user/playlists/delete" -> {
                account.remove(field("playlistId")) ?: return notFound()
                ok(done)
            }

            "/user/playlists/rename" -> {
                val p = account[field("playlistId")] ?: return notFound()
                p.name = field("newName").orEmpty()
                ok(done)
            }

            else -> notFound()
        }
    }

    private fun decode(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                append(value.substring(i + 1, i + 3).toInt(16).toChar())
                i += 3
            } else {
                append(c)
                i++
            }
        }
    }

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body)

    private fun notFound() = HttpResponse(404, emptyMap(), "")
}
