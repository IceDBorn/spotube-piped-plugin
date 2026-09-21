package dev.icedborn.spotube_plugin_piped_metadata

private const val ALBUM_CANDIDATE_LIMIT = 7
private const val ALBUM_CONTAINMENT_CHECKS = 6

/**
 * Resolves a music video to the album playlist whose first page actually
 * contains it. Album names rarely match track titles, so candidates come from a
 * music_albums search for the artist (authoritative channel), then for
 * "title artist"; each candidate's first page is checked for the video id.
 */
internal class AlbumLookup(
    private val client: PipedClient,
    private val store: EntityStore,
) {
    suspend fun resolveAlbumForVideo(videoId: String): String? = runCatching {
        val info = store.cachedStreams(videoId) ?: run {
            val fetched = client.streams(videoId)
            store.cacheStreams(videoId, fetched)
            fetched
        }
        resolveForNames(info.title, info.uploader.orEmpty().let(::cleanArtistName), videoId)
    }.getOrNull()

    suspend fun resolveForNames(title: String, artist: String, videoId: String): String {
        val candidates = mutableListOf<PipedSearchItem>()
        if (artist.isNotBlank()) {
            candidates += client.search(artist, PipedSearchFilter.MUSIC_ALBUMS).items
                .asSequence()
                .filter { it.type == "playlist" && playlistIdOf(it.url).isNotEmpty() }
                .filter { it.uploaderMatches(artist) }
                .take(ALBUM_CANDIDATE_LIMIT)
        }
        val titleQuery = listOfNotNull(title.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() })
            .joinToString(" ")
        if (titleQuery.isNotBlank()) {
            candidates += client.search(titleQuery, PipedSearchFilter.MUSIC_ALBUMS).items
                .asSequence()
                .filter { it.type == "playlist" && playlistIdOf(it.url).isNotEmpty() }
                .take(ALBUM_CANDIDATE_LIMIT)
        }
        if (candidates.isEmpty()) throw IllegalStateException("Album search returned no results")
        val distinct = candidates.distinctBy { playlistIdOf(it.url) }

        for (candidate in distinct.take(ALBUM_CONTAINMENT_CHECKS)) {
            val id = playlistIdOf(candidate.url)
            val page = store.cachedAlbumPlaylist(id) ?: run {
                val fetched = client.playlist(id)
                store.cacheAlbumPlaylist(id, fetched)
                fetched
            }
            if (page.relatedStreams.any { videoIdOf(it.url) == videoId }) return id
        }
        return playlistIdOf(distinct.maxByOrNull { it.albumNameScore(title, artist) }!!.url)
    }
}

private fun PipedSearchItem.uploaderMatches(artist: String): Boolean {
    val cleaned = cleanArtistName(uploaderName)
    return cleaned.isNotBlank() &&
        (cleaned.equals(artist, ignoreCase = true) ||
            cleaned.contains(artist, ignoreCase = true) ||
            artist.contains(cleaned, ignoreCase = true))
}

private fun PipedSearchItem.albumNameScore(trackTitle: String, trackArtist: String?): Float {
    var score = 0f
    if (trackTitle.isNotBlank() && (name.contains(trackTitle, ignoreCase = true) || title.contains(trackTitle, ignoreCase = true))) score += 0.5f
    val cleanedUploader = cleanArtistName(uploaderName)
    if (!trackArtist.isNullOrBlank() && (cleanedUploader.equals(trackArtist, ignoreCase = true) || name.contains(trackArtist, ignoreCase = true))) {
        score += 0.4f
    }
    return score
}
