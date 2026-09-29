package dev.icedborn.spotube_plugin_piped.audio

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.PipedStreamsInfo
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlin.coroutines.cancellation.CancellationException

private val audioLog = Logger("PipedAudio")

/** The shared [PipedClient] for audio. A failed fetch is null, never a throw, because RealPipedAudioAPI
 * reads null as "try the next source". */
internal class AudioPipedClient(private val client: PipedClient) {

    suspend fun searchSongs(query: String): List<PipedSearchItem>? = search(query, PipedSearchFilter.MUSIC_SONGS)

    /** Plain YouTube search, for songs YouTube Music does not index. */
    suspend fun searchVideos(query: String): List<PipedSearchItem>? = search(query, PipedSearchFilter.VIDEOS)

    suspend fun streams(videoId: String): PipedStreamsInfo? = orNull("streams $videoId") {
        client.streamsForAudio(videoId)
    }

    /** null = no verdict (5xx, 429, redirect or a failed probe), which the caller treats as served. */
    suspend fun servesMediaUrl(url: String): Boolean? = orNull("probe") {
        client.servesMediaUrl(url)
    }

    private suspend fun search(query: String, filter: String): List<PipedSearchItem>? {
        val page = orNull("search $filter") { client.search(query, filter) } ?: return null
        // Accept "stream" and "video" rows (music_songs can carry either); a channel or playlist row
        // has no /streams to resolve, so it is dropped here rather than at the source.
        return page.items.filter { it.type == "stream" || it.type == "video" }
    }

    private suspend fun <T> orNull(label: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        audioLog.w { "$label failed: ${e.message}" }
        null
    }
}
