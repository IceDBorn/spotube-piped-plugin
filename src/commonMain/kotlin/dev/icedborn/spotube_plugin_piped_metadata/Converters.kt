package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumType
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUser

internal const val ALBUM_LOOKUP_PREFIX = "ytm-album:"

/** Resolvable album stub for tracks whose Piped payload carries no album data. */
internal fun albumStub(
    videoId: String,
    thumbnails: List<Thumbnail>,
    artists: List<MetadataArtist.Basic>,
): MetadataAlbum.Detailed = MetadataAlbum.Detailed(
    releaseDate = null,
    genres = emptyList(),
    trackCount = 0,
    id = "$ALBUM_LOOKUP_PREFIX$videoId",
    title = "",
    description = null,
    thumbnails = thumbnails,
    albumType = MetadataAlbumType.Album,
    artists = artists,
    externalUri = null,
)

internal val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
internal val YOUTUBE_CHANNEL_ID = Regex("[A-Za-z0-9_-]{24}")

internal fun videoIdOf(url: String): String =
    url.substringAfter("/watch?v=").substringBefore('&')
        .takeIf { YOUTUBE_VIDEO_ID.matches(it) }.orEmpty()

internal fun channelIdOf(url: String): String {
    val raw = url.substringAfter("/channel/").substringBefore('/')
    return raw.takeIf { it != url && YOUTUBE_CHANNEL_ID.matches(it) }.orEmpty()
}

internal fun playlistIdOf(url: String): String {
    val raw = url.substringAfter("list=").substringBefore('&')
    return raw.takeIf { it.isNotBlank() && it != url }.orEmpty()
}

internal fun String.toThumbnails(): List<Thumbnail> {
    if (isBlank()) return emptyList()
    val (w, h) = thumbnailDimsOf(this)
    return listOf(Thumbnail(this, w, h))
}

internal fun String?.toThumbnailsOrEmpty(): List<Thumbnail> = this?.toThumbnails() ?: emptyList()

/** Piped proxy URLs carry =wNNN-hNNN (or =sNNN square) size segments. */
internal fun thumbnailDimsOf(url: String): Pair<Int, Int> {
    Regex("""=w(\d+)(?:-h(\d+))?""").find(url)?.let { m ->
        return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
    }
    Regex("""=s(\d+)""").find(url)?.let { m ->
        val size = m.groupValues[1].toIntOrNull() ?: 0
        return size to size
    }
    return 0 to 0
}

/** YouTube Music artist channels are auto-generated "... - Topic" accounts; the suffix is noise. */
internal fun cleanArtistName(name: String): String =
    name.removeSuffix(" - Topic")

/** Canonical form of a synthetic artist id: embedded uploader name cleaned. */
internal fun canonicalArtistId(id: String): String =
    if (id.startsWith("channel:")) "channel:" + cleanArtistName(id.removePrefix("channel:")) else id

/** Artist derived from a Piped uploader field; null when there is no usable name or channel id. */
internal fun uploaderArtist(
    name: String,
    channelUrl: String,
    avatar: String? = null,
): MetadataArtist.Basic? {
    val id = channelIdOf(channelUrl)
    val clean = cleanArtistName(name)
    if (clean.isBlank() && id.isEmpty()) return null
    return MetadataArtist.Basic(
        id = id.ifEmpty { "channel:$clean" },
        name = clean,
        thumbnails = avatar.toThumbnailsOrEmpty(),
        externalUri = if (id.isNotEmpty()) "https://www.youtube.com/channel/$id" else null,
    )
}

/** Playlist owner derived from a Piped uploader field; null when there is no usable identity. */
internal fun uploaderUser(
    name: String,
    channelUrl: String,
    avatar: String? = null,
): MetadataUser? {
    val id = channelIdOf(channelUrl)
    val clean = cleanArtistName(name)
    if (clean.isBlank() && id.isEmpty()) return null
    return MetadataUser(
        id = id.ifEmpty { "channel:$clean" },
        // The host renders displayName (falling back to username), so the clean,
        // suffix-stripped name belongs in displayName — same convention as toUser.
        username = name,
        displayName = clean,
        thumbnails = avatar.toThumbnailsOrEmpty(),
        externalUri = if (id.isNotEmpty()) "https://www.youtube.com/channel/$id" else null,
    )
}

internal fun PipedSearchItem.toTrack(album: MetadataAlbum.Detailed? = null, trackNumber: Int? = null): MetadataTrack? {
    val id = videoIdOf(url)
    if (id.isEmpty()) return null
    val artist = uploaderArtist(uploaderName, uploaderUrl, uploaderAvatar)
    return MetadataTrack(
        id = id,
        title = title.ifBlank { name },
        durationMs = if (duration > 0) duration * 1000L else 0L,
        trackNumber = trackNumber,
        discNumber = null,
        artists = listOfNotNull(artist),
        album = album ?: albumStub(id, thumbnail.toThumbnails(), listOfNotNull(artist)),
        thumbnails = thumbnail.toThumbnails(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = "https://www.youtube.com/watch?v=$id",
    )
}

internal fun PipedStreamsInfo.toTrack(videoId: String): MetadataTrack {
    val artist = uploaderArtist(uploader, uploaderUrl, uploaderAvatar)
    return MetadataTrack(
        id = videoId,
        title = title,
        durationMs = if (duration > 0) duration * 1000L else 0L,
        trackNumber = null,
        discNumber = null,
        artists = listOfNotNull(artist),
        album = albumStub(videoId, thumbnailUrl.orEmpty().toThumbnails(), listOfNotNull(artist)),
        thumbnails = thumbnailUrl.orEmpty().toThumbnails(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = "https://www.youtube.com/watch?v=$videoId",
    )
}

private val ALBUM_NAME_PREFIX = Regex("""^(Album|Single|Compilation)\s*[–-]\s*""")

internal fun splitAlbumTitle(raw: String): Pair<String, MetadataAlbumType> {
    val cleaned = raw.trim()
    val type = when {
        cleaned.startsWith("Single ", ignoreCase = true) -> MetadataAlbumType.Single
        cleaned.startsWith("Compilation ", ignoreCase = true) -> MetadataAlbumType.Collection
        else -> MetadataAlbumType.Album
    }
    val title = ALBUM_NAME_PREFIX.replace(cleaned, "")
    return title.ifBlank { cleaned } to type
}

internal fun PipedPlaylistPage.toAlbum(playlistId: String): MetadataAlbum.Detailed {
    val (title, type) = splitAlbumTitle(name)
    val artist = uploaderArtist(uploader.orEmpty(), uploaderUrl.orEmpty(), uploaderAvatar)
        ?: relatedStreams.firstNotNullOfOrNull { uploaderArtist(it.uploaderName, it.uploaderUrl, it.uploaderAvatar) }
    return MetadataAlbum.Detailed(
        releaseDate = null,
        genres = emptyList(),
        trackCount = videos.takeIf { it >= 0 } ?: relatedStreams.size,
        id = playlistId,
        title = title,
        description = description.orEmpty().takeIf { it.isNotBlank() },
        thumbnails = thumbnailUrl.orEmpty().toThumbnails(),
        albumType = type,
        artists = listOfNotNull(artist),
        externalUri = "https://www.youtube.com/playlist?list=$playlistId",
    )
}

internal fun PipedSearchItem.toAlbumBasic(artistFallback: MetadataArtist.Basic? = null): MetadataAlbum.Basic? {
    val id = playlistIdOf(url)
    if (id.isEmpty()) return null
    val (title, type) = splitAlbumTitle(name.ifBlank { title })
    val artist = uploaderArtist(uploaderName, uploaderUrl) ?: artistFallback
    return MetadataAlbum.Basic(
        id = id,
        title = title,
        description = null,
        thumbnails = thumbnail.toThumbnails(),
        albumType = type,
        artists = listOfNotNull(artist),
        externalUri = "https://www.youtube.com/playlist?list=$id",
    )
}

internal fun PipedSearchItem.toAlbumDetailed(): MetadataAlbum.Detailed? {
    val id = playlistIdOf(url)
    if (id.isEmpty()) return null
    val (title, type) = splitAlbumTitle(name.ifBlank { title })
    val artist = uploaderArtist(uploaderName, uploaderUrl)
    return MetadataAlbum.Detailed(
        releaseDate = null,
        genres = emptyList(),
        trackCount = 0,
        id = id,
        title = title,
        description = null,
        thumbnails = thumbnail.toThumbnails(),
        albumType = type,
        artists = listOfNotNull(artist),
        externalUri = "https://www.youtube.com/playlist?list=$id",
    )
}

internal fun PipedPlaylistPage.toPlaylist(playlistId: String): MetadataPlaylist {
    val owner = uploader?.let { uploaderUser(it, uploaderUrl.orEmpty(), uploaderAvatar) }
        ?: relatedStreams.firstNotNullOfOrNull { uploaderUser(it.uploaderName, it.uploaderUrl, it.uploaderAvatar) }
    return MetadataPlaylist(
        id = playlistId,
        title = name,
        description = description.orEmpty().takeIf { it.isNotBlank() },
        thumbnails = thumbnailUrl.orEmpty().toThumbnails(),
        trackCount = videos.takeIf { it >= 0 } ?: relatedStreams.size,
        externalUri = "https://www.youtube.com/playlist?list=$playlistId",
        owner = owner,
    )
}

internal fun PipedSearchItem.toPlaylist(): MetadataPlaylist? {
    val id = playlistIdOf(url)
    if (id.isEmpty()) return null
    return MetadataPlaylist(
        id = id,
        title = name.ifBlank { title },
        description = null,
        thumbnails = thumbnail.toThumbnails(),
        trackCount = 0,
        externalUri = "https://www.youtube.com/playlist?list=$id",
        owner = uploaderUser(uploaderName, uploaderUrl, uploaderAvatar),
    )
}

internal fun PipedSearchItem.toArtist(): MetadataArtist.Basic? {
    val id = channelIdOf(url)
    if (id.isEmpty()) return null
    return MetadataArtist.Basic(
        id = id,
        name = name.ifBlank { title }.let(::cleanArtistName),
        thumbnails = thumbnail.toThumbnails(),
        externalUri = "https://www.youtube.com/channel/$id",
    )
}

internal fun PipedChannelInfo.toArtist(): MetadataArtist.Detailed = MetadataArtist.Detailed(
    genres = emptyList(),
    biography = description.orEmpty().takeIf { it.isNotBlank() },
    followersCount = subscriberCount.takeIf { it > 0 }?.toInt(),
    id = id,
    name = cleanArtistName(name),
    thumbnails = avatarUrl.toThumbnails(),
    externalUri = "https://www.youtube.com/channel/$id",
)


internal fun PipedChannelInfo.toUser(): MetadataUser = MetadataUser(
    id = id,
    username = name,
    displayName = cleanArtistName(name),
    thumbnails = avatarUrl.toThumbnails(),
    externalUri = "https://www.youtube.com/channel/$id",
)
