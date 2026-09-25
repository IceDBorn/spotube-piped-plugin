package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.core.PluginUpdateInfo
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.swiftzer.semver.SemVer

private const val RELEASES_URL = "https://api.github.com/repos/IceDBorn/spotube-piped-plugin/releases/latest"
private const val UPDATE_CHECK_TTL_MS = 6 * 60 * 60_000L

private val updateLog = Logger("PipedUpdate")

/** Reads the latest GitHub release and reports it when it is newer than the installed version.
 *
 * The result is cached for [UPDATE_CHECK_TTL_MS], null included, so an offline or rate-limited
 * GitHub does not cost a request per call. */
class UpdateChecker(private val httpClient: HttpClientAPI) {

    private var lastCheck: Pair<Long, PluginUpdateInfo?>? = null

    /** The update to offer, or null when up to date, when the check failed, or when it is cached. */
    suspend fun check(currentVersion: SemVer): PluginUpdateInfo? {
        lastCheck?.let { (at, info) -> if (epochMillis() - at < UPDATE_CHECK_TTL_MS) return info }
        val info = orNull("update check") { fetch(currentVersion) }
        lastCheck = epochMillis() to info
        return info
    }

    private suspend fun fetch(currentVersion: SemVer): PluginUpdateInfo? {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = RELEASES_URL,
            // GitHub rejects a request with no User-Agent.
            requestHeaders = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "spotube-plugin-piped",
            ),
            body = null,
        )
        if (response.statusCode !in 200..299) {
            updateLog.w { "release check returned HTTP ${response.statusCode}" }
            return null
        }
        val body = response.body
        if (body.isNullOrBlank()) {
            updateLog.w { "release check returned a blank body" }
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
        // parseOrNull, not parse: a hand-made tag such as "nightly" is not a version.
        val latest = tag?.let { SemVer.parseOrNull(it.removePrefix("v")) }
        if (latest == null) {
            updateLog.w { "release check returned an unparseable tag: $tag" }
            return null
        }
        if (latest <= currentVersion) return null
        val asset = root["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".smplug") == true }
            ?: return null
        return PluginUpdateInfo(
            latestVersion = latest.toString(),
            directDownloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
            releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}
