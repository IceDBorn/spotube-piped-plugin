package dev.icedborn.spotube_plugin_piped.fakes

import dev.icedborn.spotube_plugin_piped.client.string
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.HttpResponse
import kotlinx.coroutines.delay

/** Offline stand-in for the host HTTP API: routes are matched in registration order, misses give a 404. */
class FakeHttp : HttpClientAPI {
    val requests = mutableListOf<String>()
    val methods = mutableListOf<Pair<HttpMethod, String>>()
    val headers = mutableListOf<Map<String, String>?>()
    private val routes = mutableListOf<Pair<(String) -> Boolean, HttpResponse>>()
    private val throwing = mutableListOf<Pair<(String) -> Boolean, Throwable>>()

    /** Simulated latency. Without it a request completes before the next coroutine starts, so tests
     * that mean to overlap two in-flight calls would really be testing the cache. Costs no wall time in runTest. */
    var latencyMs = 0L

    /** [contentType] is null for no Content-Type header, which the stream probe reads as inconclusive. */
    fun on(match: (String) -> Boolean, status: Int = 200, body: String, contentType: String? = null) {
        val responseHeaders = contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap()
        routes += match to HttpResponse(status, responseHeaders, body)
    }

    /** Matches anywhere in the URL, so a path still hits when the request carries a query string. */
    fun onPath(path: String, status: Int = 200, body: String, contentType: String? = null) =
        on({ it.contains(path) }, status, body, contentType)

    /** A route that throws instead of answering, the way a host HTTP client fails on a timeout. */
    fun onThrow(match: (String) -> Boolean, error: Throwable) {
        throwing += match to error
    }

    fun onPathThrow(path: String, error: Throwable) = onThrow({ it.contains(path) }, error)

    fun countMatching(fragment: String): Int = requests.count { it.contains(fragment) }

    override suspend fun request(method: HttpMethod, url: String, requestHeaders: Map<String, String>?, body: String?): HttpResponse {
        requests += url
        methods += method to url
        headers += requestHeaders
        if (latencyMs > 0) delay(latencyMs)
        throwing.firstOrNull { it.first(url) }?.let { throw it.second }
        return routes.firstOrNull { it.first(url) }?.second ?: HttpResponse(404, emptyMap(), "")
    }
}
