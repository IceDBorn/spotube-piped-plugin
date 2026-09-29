package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.settings.UpdateChannel
import dev.icedborn.spotube_plugin_piped.settings.UpdateChannelSetting
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.plugin_apis.core.PluginUpdateInfo
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.swiftzer.semver.SemVer
import kotlin.coroutines.cancellation.CancellationException

private const val RELEASES_API = "https://api.github.com/repos/IceDBorn/spotube-piped-plugin/releases"
private const val STABLE_URL = "$RELEASES_API/latest"
private const val NIGHTLY_URL = "$RELEASES_API/tags/nightly"
private const val UPDATE_CHECK_TTL_MS = 6 * 60 * 60_000L
private const val PLUGIN_ASSET = "spotube-plugin-piped.smplug"
private const val FAILED_CHECK_TTL_MS = 15 * 60_000L

private val updateLog = Logger("PipedUpdate")

/** Reports a GitHub release of the selected channel newer than the installed version. Answers are cached per
 * channel, and failed checks for a shorter time, so an offline or rate-limited GitHub costs no request per call. */
internal class UpdateChecker(
    private val httpClient: HttpClientAPI,
    private val channel: UpdateChannelSetting? = null,
    private val now: () -> Long = ::epochMillis,
) {

    private class CachedCheck(val expiresAt: Long, val channel: UpdateChannel, val info: PluginUpdateInfo?)

    private var lastCheck: CachedCheck? = null

    /** The update to offer, or null when up to date, when the check failed, or when it is cached. */
    suspend fun check(currentVersion: SemVer): PluginUpdateInfo? {
        // orNull, so a storage error while reading the setting cannot reach the host.
        val resolved = orNull("update channel") { channel?.resolve(currentVersion) } ?: UpdateChannel.STABLE
        lastCheck?.let { if (it.channel == resolved && now() < it.expiresAt) return it.info }
        val result = runCatching { fetch(currentVersion, resolved) }
        result.exceptionOrNull()?.let { e ->
            if (e is CancellationException) throw e
            updateLog.w { "update check failed: ${e.message}" }
        }
        val fetched = result.getOrNull()
        // A nightly check that lost one of its two requests is retried as soon as a failed one.
        val ttl = if (fetched != null && !fetched.partial) UPDATE_CHECK_TTL_MS else FAILED_CHECK_TTL_MS
        val info = fetched?.info
        lastCheck = CachedCheck(now() + ttl, resolved, info)
        return info
    }

    /** Drops the cached result, so a channel change takes effect without waiting out the TTL. */
    fun clearCache() {
        lastCheck = null
    }

    private class Fetched(val info: PluginUpdateInfo?, val partial: Boolean)

    private suspend fun fetch(currentVersion: SemVer, resolved: UpdateChannel): Fetched {
        if (resolved == UpdateChannel.STABLE) {
            return Fetched(fetchRelease(STABLE_URL, "tag_name", currentVersion)?.second, partial = false)
        }
        // Nightly: the newer of the two releases wins, so a stable release replaces the nightlies built on it.
        // A failure on one side does not hide the other; the check fails only when both do.
        val results = listOf(
            runCatching { fetchRelease(NIGHTLY_URL, "name", currentVersion) },
            runCatching { fetchRelease(STABLE_URL, "tag_name", currentVersion) },
        )
        results.forEach { r -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val info = results.mapNotNull { it.getOrNull() }.maxByOrNull { it.first }?.second
        return Fetched(info, partial = results.any { it.isFailure })
    }

    /** The release at [url] with its plugin asset, or null when it has no usable version, no asset or is not newer.
     * Throws when GitHub does not answer with a release. */
    private suspend fun fetchRelease(url: String, versionField: String, currentVersion: SemVer): Pair<SemVer, PluginUpdateInfo>? {
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
        check(response.statusCode in 200..299) { "release check returned HTTP ${response.statusCode}" }
        val body = response.body
        check(!body.isNullOrBlank()) { "release check returned a blank body" }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("release check returned an unparseable body")
        val rawVersion = root[versionField]?.jsonPrimitive?.contentOrNull
        // parseOrNull, not parse: the nightly tag carries the version in the title, which need not be one.
        val latest = rawVersion?.let { SemVer.parseOrNull(it.removePrefix("v")) }
        if (latest == null) {
            updateLog.w { "release check returned an unparseable version: $rawVersion" }
            return null
        }
        if (latest <= currentVersion) return null
        val bundles = root["assets"]?.jsonArray?.map { it.jsonObject }
            ?.filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".smplug") == true }.orEmpty()
        // A cancelled nightly run can leave its versioned upload next to the published bundle.
        val asset = bundles.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == PLUGIN_ASSET } ?: bundles.firstOrNull() ?: return null
        val download = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        return latest to PluginUpdateInfo(
            latestVersion = latest.toString(),
            directDownloadUrl = download,
            releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}
