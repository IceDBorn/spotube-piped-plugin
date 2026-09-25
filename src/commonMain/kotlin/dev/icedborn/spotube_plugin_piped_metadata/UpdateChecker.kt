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

private const val RELEASES_API = "https://api.github.com/repos/IceDBorn/spotube-piped-plugin/releases"
private const val STABLE_URL = "$RELEASES_API/latest"
private const val NIGHTLY_URL = "$RELEASES_API/tags/nightly"
private const val UPDATE_CHECK_TTL_MS = 6 * 60 * 60_000L

private val updateLog = Logger("PipedUpdate")

/** Reads the GitHub releases of the selected channel and reports one newer than the installed version.
 *
 * The result is cached per channel for [UPDATE_CHECK_TTL_MS], null included, so an offline or
 * rate-limited GitHub does not cost a request per call. */
class UpdateChecker(
    private val httpClient: HttpClientAPI,
    private val channel: UpdateChannelSetting? = null,
) {

    private var lastCheck: Triple<Long, UpdateChannel, PluginUpdateInfo?>? = null

    /** The update to offer, or null when up to date, when the check failed, or when it is cached. */
    suspend fun check(currentVersion: SemVer): PluginUpdateInfo? {
        // orNull, so a storage error while reading the setting cannot reach the host.
        val resolved = orNull("update channel") { channel?.resolve(currentVersion) } ?: UpdateChannel.STABLE
        lastCheck?.let { (at, cachedChannel, info) ->
            if (cachedChannel == resolved && epochMillis() - at < UPDATE_CHECK_TTL_MS) return info
        }
        val info = orNull("update check") { fetch(currentVersion, resolved) }
        lastCheck = Triple(epochMillis(), resolved, info)
        return info
    }

    /** Drops the cached result, so a channel change takes effect without waiting out the TTL. */
    fun clearCache() {
        lastCheck = null
    }

    private suspend fun fetch(currentVersion: SemVer, resolved: UpdateChannel): PluginUpdateInfo? {
        if (resolved == UpdateChannel.STABLE) {
            return fetchRelease(STABLE_URL, "tag_name", currentVersion)?.second
        }
        // Nightly: the newer of the two releases wins, so a stable release replaces the nightlies built on it.
        // orNull per request, so a thrown timeout on one side does not hide the other.
        val releases = listOf(
            orNull("nightly release") { fetchRelease(NIGHTLY_URL, "name", currentVersion) },
            orNull("stable release") { fetchRelease(STABLE_URL, "tag_name", currentVersion) },
        ).filterNotNull()
        return releases.maxByOrNull { it.first }?.second
    }

    /** The release at [url] with its plugin asset, or null when it is missing, malformed or not newer. */
    private suspend fun fetchRelease(
        url: String,
        versionField: String,
        currentVersion: SemVer,
    ): Pair<SemVer, PluginUpdateInfo>? {
        val response = httpClient.request(
            method = HttpMethod.Get,
            url = url,
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
        val rawVersion = root[versionField]?.jsonPrimitive?.contentOrNull
        // parseOrNull, not parse: the nightly tag carries the version in the title, which need not be one.
        val latest = rawVersion?.let { SemVer.parseOrNull(it.removePrefix("v")) }
        if (latest == null) {
            updateLog.w { "release check returned an unparseable version: $rawVersion" }
            return null
        }
        if (latest <= currentVersion) return null
        val asset = root["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".smplug") == true }
            ?: return null
        val download = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        return latest to PluginUpdateInfo(
            latestVersion = latest.toString(),
            directDownloadUrl = download,
            releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}
