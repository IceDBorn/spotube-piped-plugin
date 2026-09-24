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
        // Null = FAILED fetch (throttle), not zero-hit: null music_songs falls to the plain-YouTube backup; a null
        // BACKUP surfaces the transient (throw -> retry). Convert FIRST — unconvertible rows are no source at all.
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
        // The backup is the LAST authority: when it SUCCEEDED, weak music_songs rows are the best available;
        // an ATTEMPTED backup that FAILED throws (retry) only when NO usable candidate survived (round-96/106).

        if (items.isEmpty() && videos == null) {
            throw IllegalStateException("Piped search failed for \"$query\" (throttled or error body)")
        }
        return items
    }

    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val videoId = source.id
        // Null = FAILED fetch (throttle), never authoritative 'no audio': degrade to EMPTY so the host proceeds to
        // the next candidate — an exception aborts the whole MAX_SOURCES chain at the first throttled /streams (round-106).
        val info = client.streams(videoId) ?: return emptyList()

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