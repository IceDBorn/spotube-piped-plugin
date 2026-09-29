package dev.icedborn.spotube_plugin_piped.audio

import dev.icedborn.spotube_plugin_piped.client.PipedAudioStream
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.PipedStreamsInfo
import dev.icedborn.spotube_plugin_piped.client.PipedVideoStream
import dev.icedborn.spotube_plugin_piped.client.toBasic
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioFormat
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioQuality
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioSource
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioStream
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val audioApiLog = Logger("PipedAudioAPI")
private const val MAX_SOURCES = 5
private const val PLAYABLE_CONFIDENCE = 0.8f
private val YOUTUBE_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

/** After a probe proved a video's rows served, the host's re-resolves of it skip the probe for this long. */
private const val PROBE_SKIP_MS = 30 * 60_000L
private const val SERVED_MEMO_LIMIT = 500

internal class RealPipedAudioAPI(
    private val client: AudioPipedClient,
    // Plays are logged here once sources resolve, so the log never delays the search.
    private val scope: CoroutineScope? = null,
    private val now: () -> Long = ::epochMillis,
    // Called for every track the host asks audio for; the metadata side logs it as a play.
    private val onTrackRequested: suspend (MetadataTrack) -> Unit = {},
) : AudioAPI {

    // Video id to the time a probe proved its rows served. A refusal is per video, so the memo is too.
    private val servedAt = LinkedHashMap<String, Long>()

    override val supportedQualities: List<AudioFormat> = listOf(
        AudioFormat(
            codec = "opus",
            container = "webm",
            qualities = listOf(
                AudioQuality.Lossy(bitrate = 44_000),
                AudioQuality.Lossy(bitrate = 96_000),
                AudioQuality.Lossy(bitrate = 128_000),
                AudioQuality.Lossy(bitrate = 256_000),
            ),
        ),
        AudioFormat(
            codec = "aac",
            container = "mp4",
            qualities = listOf(
                AudioQuality.Lossy(bitrate = 44_000),
                AudioQuality.Lossy(bitrate = 96_000),
                AudioQuality.Lossy(bitrate = 128_000),
                AudioQuality.Lossy(bitrate = 256_000),
            ),
        ),
    )

    override suspend fun getStreamsByTrack(track: MetadataTrack): List<AudioSource> {
        val sources = resolveSources(track)
        if (scope != null) scope.launch { logPlay(track) } else logPlay(track)
        return sources
    }

    private suspend fun logPlay(track: MetadataTrack) {
        try {
            onTrackRequested(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logging a play must never block playback.
            audioApiLog.w { "play log failed for ${track.id}: ${e.message}" }
        }
    }

    private suspend fun resolveSources(track: MetadataTrack): List<AudioSource> {
        track.externalUri?.let { uri ->
            val videoId = extractVideoId(uri)
            if (videoId != null) {
                return listOf(basicSource(videoId, track.title, trackArtist(track), track, 1.0f))
            }
        }
        if (YOUTUBE_ID_REGEX.matches(track.id)) {
            return listOf(basicSource(track.id, track.title, trackArtist(track), track, 1.0f))
        }

        val query = "${track.title} ${trackArtist(track)}".trim()
        // Null is a failed fetch, not zero hits. Rows are converted first, since a row that does not convert is no source.
        val songs = client.searchSongs(query)
        val convertedSongs = songs.orEmpty().mapNotNull { it.toBasic(track) }
        // YouTube Music misses many uploads; when the best song result is weak or
        // absent, also search plain YouTube so the track still resolves.
        val bestMatch = convertedSongs.maxOfOrNull { it.confidence } ?: 0f
        val backupAttempted = bestMatch < PLAYABLE_CONFIDENCE
        val videos = if (backupAttempted) client.searchVideos(query) else null
        val items = (convertedSongs + videos.orEmpty().mapNotNull { it.toBasic(track) })
            .distinctBy { it.id }
            .sortedByDescending { it.confidence }
            .take(MAX_SOURCES)
        // Weak song rows are served when the backup search worked; a failed backup throws only when nothing is left.
        if (items.isEmpty() && videos == null) {
            // The query is the user's search text, so only its length is logged.
            audioApiLog.w { "no usable source for a ${query.length}-char query (songs=${songs?.size}, videos=${videos?.size})" }
            // Length, not the text: the host may log this message, and the query is the user's.
            throw IllegalStateException("Piped search failed for a ${query.length}-char query (throttled or error body)")
        }
        return items
    }

    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val videoId = source.id
        // A failed fetch returns an empty list so the host tries the next candidate; a throw would end the whole chain.
        val info = client.streams(videoId) ?: run {
            audioApiLog.w { "no stream info for $videoId, the host moves to the next candidate" }
            return emptyList()
        }

        val streams = playableStreams(videoId, info)

        if (streams.isEmpty()) {
            audioApiLog.w { "no playable audio stream for $videoId" }
            return emptyList()
        }

        return listOf(
            AudioSource.Streamed(
                id = source.id,
                title = source.title.takeIf { it.isNotBlank() } ?: info.title,
                artist = source.artist,
                album = source.album,
                thumbnails = source.thumbnails,
                externalUri = source.externalUri,
                confidence = source.confidence,
                streams = streams,
            ),
        )
    }

    /** A proxy can refuse every audio row of a video with an empty 403, which the host saves as a 0-byte
     * file, so a row is offered only after a serving HEAD. */
    private suspend fun playableStreams(videoId: String, info: PipedStreamsInfo): List<AudioStream.Lossy> {
        val rows = info.audioStreams.filter { it.url.isNotBlank() }.mapNotNull { it.toLossyStream() }
        if (rows.isEmpty()) return muxedStream(videoId, info)

        // The refusal is per video, so one probe of the richest row settles the common case.
        val richest = rows.maxBy { it.bitrate }
        servedAt[videoId]?.let { if (now() - it < PROBE_SKIP_MS) return rows }
        when (probe(richest.url)) {
            true -> {
                servedAt.remove(videoId)
                servedAt[videoId] = now()
                if (servedAt.size > SERVED_MEMO_LIMIT) servedAt.remove(servedAt.keys.first())
                return rows
            }

            // An inconclusive probe offers the rows but proves nothing, so it is not remembered.
            null -> return rows

            false -> servedAt.remove(videoId)
        }

        audioApiLog.i { "$videoId: the instance refused the richest audio row, probing the rest" }
        val served = coroutineScope {
            rows.filter { it !== richest }
                .map { row -> async { row.takeIf { isServed(it.url) } } }
                .awaitAll()
                .filterNotNull()
        }
        return served.ifEmpty { muxedStream(videoId, info) }
    }

    /** itag 18 only: videoStreams can also hold LBRY mirror rows, one of them an HLS manifest. */
    private suspend fun muxedStream(videoId: String, info: PipedStreamsInfo): List<AudioStream.Lossy> {
        val muxed = info.videoStreams
            .firstOrNull { it.itag == MUXED_ITAG && it.url.isNotBlank() }
            ?.toLossyStream(fallbackBitrate = MUXED_BITRATE)
        if (muxed != null && isServed(muxed.url)) {
            // The row holds audio and video, so the file is a small mp4 with a picture track.
            audioApiLog.i { "$videoId: using the muxed progressive row (itag 18), a video file with audio" }
            return listOf(muxed)
        }
        return emptyList()
    }

    /** True when served, false when refused, null when the probe was inconclusive (429, 5xx, transport error). */
    private suspend fun probe(url: String): Boolean? = client.servesMediaUrl(url).also {
        if (it == false) audioApiLog.d { "the instance refused a stream URL" }
    }

    private suspend fun isServed(url: String): Boolean = probe(url) != false
}

private fun trackArtist(track: MetadataTrack): String = track.artists.joinToString(", ") { it.name }

private fun extractVideoId(uri: String): String? {
    val watchIdx = uri.indexOf("v=")
    if (watchIdx >= 0) {
        val id = uri.substring(watchIdx + 2).substringBefore('&')
        return id.takeIf { YOUTUBE_ID_REGEX.matches(it) }
    }
    val youtuBeIdx = uri.indexOf("youtu.be/")
    if (youtuBeIdx >= 0) {
        val id = uri.substring(youtuBeIdx + "youtu.be/".length).substringBefore('?')
        return id.takeIf { YOUTUBE_ID_REGEX.matches(it) }
    }
    return null
}

private fun basicSource(videoId: String, title: String, artist: String?, track: MetadataTrack, confidence: Float): AudioSource.Basic =
    AudioSource.Basic(
        id = videoId,
        title = title,
        artist = artist,
        album = track.album?.title,
        thumbnails = track.thumbnails.orEmpty(),
        externalUri = "https://www.youtube.com/watch?v=$videoId",
        confidence = confidence,
    )

private fun PipedSearchItem.toBasic(track: MetadataTrack): AudioSource.Basic? {
    // Only a real watch URL yields a source id /streams can resolve: fallback /live/ or malformed rows would mint the
    // whole URL as the source id (every selection then 404s). Search rows must carry a watch URL.
    val videoId = url.substringAfter("/watch?v=").substringBefore('&')
    if (!YOUTUBE_ID_REGEX.matches(videoId)) return null
    val thumbnails = if (thumbnail.isNotBlank()) {
        listOf(Thumbnail(url = thumbnail, width = 300, height = 300))
    } else {
        emptyList()
    }
    return AudioSource.Basic(
        id = videoId,
        title = title,
        artist = uploaderName,
        album = track.album?.title,
        thumbnails = thumbnails,
        externalUri = "https://www.youtube.com/watch?v=$videoId",
        confidence = confidenceAgainst(track),
    )
}

private fun PipedSearchItem.confidenceAgainst(track: MetadataTrack): Float {
    var score = 0.70f
    if (title.contains(track.title, ignoreCase = true)) score += 0.15f
    val artist = trackArtist(track)
    if (artist.isNotBlank() && (uploaderName.equals(artist, ignoreCase = true) || title.contains(artist, ignoreCase = true))) {
        score += 0.10f
    }
    if (duration > 0 && track.durationMs > 0) {
        val delta = abs(duration - track.durationMs / 1000f)
        if (delta <= 60f) score += 0.05f
    }
    return score.coerceAtMost(1.0f)
}

// Roughly itag 18's audio rate; the host only uses bitrate to rank streams.
private const val MUXED_BITRATE = 96_000
private const val MUXED_ITAG = 18

private fun PipedAudioStream.toLossyStream(): AudioStream.Lossy? {
    val isWebm = mimeType.contains("webm") || format.equals("WEBMA", ignoreCase = true) || itag in setOf(249, 250, 251)
    val codec = if (isWebm) "opus" else "aac"
    val container = if (isWebm) "webm" else "mp4"
    val bitrate = bitrate.takeIf { it > 0 } ?: parseKbps(quality) ?: ITAG_BITRATES[itag]
    return bitrate?.let {
        AudioStream.Lossy(
            url = url,
            codec = codec,
            container = container,
            bitrate = it,
        )
    }
}

/** itag 18 reports bitrate 0, so [fallbackBitrate] stands in for it. */
private fun PipedVideoStream.toLossyStream(fallbackBitrate: Int) = AudioStream.Lossy(
    url = url,
    codec = "aac",
    container = "mp4",
    bitrate = bitrate.takeIf { it > 0 } ?: fallbackBitrate,
)

private val ITAG_BITRATES = mapOf(
    139 to 48_000,
    140 to 128_000,
    249 to 50_000,
    250 to 70_000,
    251 to 160_000,
)

private fun parseKbps(quality: String): Int? {
    val idx = quality.indexOf("kbps")
    if (idx <= 0) return null
    return quality.substring(0, idx).trim().toFloatOrNull()?.let { (it * 1000).toInt() }
}
