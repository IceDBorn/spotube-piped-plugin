package dev.icedborn.spotube_plugin_piped_metadata.fakes

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.HttpResponse
import kotlinx.coroutines.delay

/** Offline stand-in for the host HTTP API: routes are matched in registration order, misses give a 404. */
class FakeHttp : HttpClientAPI {
    val requests = mutableListOf<String>()
    private val routes = mutableListOf<Pair<(String) -> Boolean, HttpResponse>>()
    private val throwing = mutableListOf<Pair<(String) -> Boolean, Throwable>>()

    /** Simulated latency. Without it a request completes before the next coroutine starts, so tests
     * that mean to overlap two in-flight calls would really be testing the cache. Costs no wall time in runTest. */
    var latencyMs = 0L

    fun on(match: (String) -> Boolean, status: Int = 200, body: String) {
        routes += match to HttpResponse(status, emptyMap(), body)
    }

    /** Matches anywhere in the URL, so a path still hits when the request carries a query string. */
    fun onPath(path: String, status: Int = 200, body: String) = on({ it.contains(path) }, status, body)

    /** A route that throws instead of answering, the way a host HTTP client fails on a timeout. */
    fun onThrow(match: (String) -> Boolean, error: Throwable) {
        throwing += match to error
    }

    fun onPathThrow(path: String, error: Throwable) = onThrow({ it.contains(path) }, error)

    fun countMatching(fragment: String): Int = requests.count { it.contains(fragment) }

    override suspend fun request(
        method: HttpMethod,
        url: String,
        requestHeaders: Map<String, String>?,
        body: String?,
    ): HttpResponse {
        requests += url
        if (latencyMs > 0) delay(latencyMs)
        throwing.firstOrNull { it.first(url) }?.let { throw it.second }
        return routes.firstOrNull { it.first(url) }?.second ?: HttpResponse(404, emptyMap(), "")
    }
}
