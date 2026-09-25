package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpMethod
import dev.krtirtho.plugin_interfaces.host_apis.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Live checks against a real instance; skipped unless PIPED_INSTANCE is set. Not part of the default run. */
class LiveSmokeTest {

    /** Absent unless the caller exports it, so the default run stays offline. */
    private fun instance(): String =
        System.getenv("PIPED_INSTANCE")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: throw org.junit.AssumptionViolatedException("PIPED_INSTANCE is not set")

    /** The real client talks over the host API, which the JVM tests do not provide. */
    private class LiveHttp(private val instance: String) : HttpClientAPI {
        override suspend fun request(
            method: HttpMethod,
            url: String,
            requestHeaders: Map<String, String>?,
            body: String?,
        ): HttpResponse {
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            // A hung instance must fail the test, not hang it.
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            requestHeaders?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() }
            return HttpResponse(code, emptyMap(), text)
        }
    }

    @Test
    fun `search returns at least one song`() = runTest {
        val url = instance()
        val page = PipedClient(LiveHttp(url)) { url }.search("daft punk", PipedSearchFilter.MUSIC_SONGS)
        assertTrue(!page!!.items.isEmpty(), "no songs from $url")
    }

    @Test
    fun `an album playlist has tracks`() = runTest {
        val url = instance()
        val client = PipedClient(LiveHttp(url)) { url }
        val albumId = client.search("random access memories", PipedSearchFilter.MUSIC_ALBUMS)!!
            .items.firstNotNullOfOrNull { playlistIdOf(it.url).takeIf { id -> id.isNotEmpty() } }
        if (albumId == null) throw org.junit.AssumptionViolatedException("no album row to walk")
        val page = client.playlist(albumId)
        assertTrue(page != null && page.relatedStreams.isNotEmpty(), "no tracks in $albumId")
    }

    @Test
    fun `a channel has a name`() = runTest {
        val url = instance()
        val client = PipedClient(LiveHttp(url)) { url }
        val channelId = client.search("daft punk", PipedSearchFilter.MUSIC_ARTISTS)!!
            .items.firstNotNullOfOrNull { channelIdOf(it.url).takeIf { id -> id.isNotEmpty() } }
        if (channelId == null) throw org.junit.AssumptionViolatedException("no artist row to walk")
        val channel = client.channel(channelId)
        assertTrue(!channel!!.name.isBlank(), "blank channel name on $url")
    }
}
