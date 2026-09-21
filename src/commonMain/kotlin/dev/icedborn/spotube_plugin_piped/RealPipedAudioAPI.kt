package dev.icedborn.spotube_plugin_piped

import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioFormat
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioQuality
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioSource
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioStream
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.math.abs

private const val MAX_SOURCES = 5
private const val PLAYABLE_CONFIDENCE = 0.8f
private val YOUTUBE_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

class RealPipedAudioAPI(private val client: PipedClient) : AudioAPI {

    override val supportedQualities: List<AudioFormat> = listOf(
        AudioFormat(
            codec = "opus", container = "webm", qualities = listOf(
                AudioQuality.Lossy(bitrate = 44_000),
                AudioQuality.Lossy(bitrate = 96_000),
                AudioQuality.Lossy(bitrate = 128_000),
                AudioQuality.Lossy(bitrate = 256_000),
            )
        ),
        AudioFormat(
            codec = "aac", container = "mp4", qualities = listOf(
                AudioQuality.Lossy(bitrate = 44_000),
                AudioQuality.Lossy(bitrate = 96_000),
                AudioQuality.Lossy(bitrate = 128_000),
                AudioQuality.Lossy(bitrate = 256_000),
            )
        ),
    )

    override suspend fun getStreamsByTrack(track: MetadataTrack): List<AudioSource> {
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
        val songs = client.searchSongs(query)
        // YouTube Music misses many uploads; when the best song result is weak or
        // absent, also search plain YouTube so the track still resolves.
        val bestMatch = songs.maxOfOrNull { it.confidenceAgainst(track) } ?: 0f
        val videos = if (bestMatch < PLAYABLE_CONFIDENCE) client.searchVideos(query) else emptyList()
        val items = (songs + videos)
            .distinctBy { it.url }
            .sortedByDescending { it.confidenceAgainst(track) }
            .take(MAX_SOURCES)
            .map { it.toBasic(track) }
        return items
    }

    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val videoId = source.id
        val info = client.streams(videoId)

        val streams = info.audioStreams
            .filter { it.url.isNotBlank() }
            .mapNotNull { it.toLossyStream() }

        if (streams.isEmpty()) return emptyList()

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
            )
        )
    }
}

private fun trackArtist(track: MetadataTrack): String =
    track.artists.joinToString(", ") { it.name }

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

private fun basicSource(
    videoId: String,
    title: String,
    artist: String?,
    track: MetadataTrack,
    confidence: Float,
): AudioSource.Basic = AudioSource.Basic(
    id = videoId,
    title = title,
    artist = artist,
    album = track.album?.title,
    thumbnails = track.thumbnails.orEmpty(),
    externalUri = "https://www.youtube.com/watch?v=$videoId",
    confidence = confidence,
)

private fun PipedSearchItem.toBasic(track: MetadataTrack): AudioSource.Basic {
    val videoId = url.substringAfter("/watch?v=").substringBefore('&')
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
