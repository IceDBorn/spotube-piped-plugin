package dev.icedborn.spotube_plugin_piped_metadata

private const val ALBUM_CANDIDATE_LIMIT = 7
private const val ALBUM_FALLBACK_PAGE_LIMIT = 6

/** Resolves a music video to the album playlist whose first page contains it. Candidates: music_albums
 * search for the artist, then for "title artist"; each first page is checked for the video id. */
internal class AlbumLookup(
    private val client: PipedClient,
    private val store: EntityStore,
) {
/** Verdict + the Piped requests it issued (budget charges PER REQUEST: one 'none' verdict can walk every candidate's chain).
 * null = searches SUCCEEDED but found no album (safe to cache); transport failures propagate so a transient never caches 'none'. */
    data class AlbumResolve(val albumId: String?, val requests: Int)

    internal class RequestCounter {
        var requests = 0
    }

    suspend fun resolveAlbumForVideo(videoId: String): AlbumResolve {
        val counter = RequestCounter()
        val info = store.cachedStreams(videoId) ?: run {
            counter.requests++
            client.streams(videoId)
        }?.also { store.cacheStreams(videoId, it) }
            // Transport/ambiguous fetch: never fabricate a 'not an album'
            // verdict from an all-defaults decode — the caller must retry.
            ?: throw IllegalStateException("streams fetch failed for $videoId")
        val albumId = resolveForNames(info.title, info.uploader.orEmpty().let(::cleanArtistName), videoId, counter)
        return AlbumResolve(albumId, counter.requests)
    }

    suspend fun resolveForNames(
        title: String, artist: String, videoId: String, counter: RequestCounter = RequestCounter(),
    ): String? {
        val candidates = mutableListOf<PipedSearchItem>()
        if (artist.isNotBlank()) {
            // A NULL search page is a FAILED/ambiguous fetch, never a genuine zero-hit — the resolver
            // never caches a permanent 'none' for a transient fault.
            counter.requests++
            val artistPage = client.search(artist, PipedSearchFilter.MUSIC_ALBUMS)
                ?: throw IllegalStateException("album search failed (artist)")
            candidates += artistPage.items.asSequence()
                .filter { it.type == "playlist" && playlistIdOf(it.url).isNotEmpty() }
                .filter { it.uploaderMatches(artist) }
                .take(ALBUM_CANDIDATE_LIMIT)
        }
        val titleQuery = listOfNotNull(title.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() })
            .joinToString(" ")
        if (titleQuery.isNotBlank()) {
            counter.requests++
            val titlePage = client.search(titleQuery, PipedSearchFilter.MUSIC_ALBUMS)
                ?: throw IllegalStateException("album search failed (title)")
            candidates += titlePage.items.asSequence()
                .filter { it.type == "playlist" && playlistIdOf(it.url).isNotEmpty() }
                .take(ALBUM_CANDIDATE_LIMIT)
        }
        if (candidates.isEmpty()) return null
        val distinct = candidates.distinctBy { playlistIdOf(it.url) }

        // Containment check on EVERY candidate's first page — only an album that ACTUALLY contains the
        // video can resolve; an unchecked candidate (beyond the ranked budget) is never returned as a verdict.
        for (candidate in distinct) {
            val id = playlistIdOf(candidate.url)
            val page = store.cachedAlbumPlaylist(id) ?: run {
                counter.requests++
                // runCatching: get() THROWS on non-2xx (429/5xx) before its null guard; that throw must not
                // abort the ranked chain (round-47) — a null means no page-1 evidence: the candidate is inconclusive.
                val fetched = runCatching { client.playlist(id) }.getOrNull() ?: return@run null
                // A decodable page — even genuinely EMPTY — is authoritative: cache it (the evidence walk reads
                // the cache; page()'s extension re-anchors a throttled decodable empty, so it self-heals not freezes).
                store.cacheAlbumPlaylist(id, fetched)
                fetched
            } ?: continue
            if (page.relatedStreams.any { videoIdOf(it.url) == videoId }) return id
        }
        // Walk candidates' chains IN SCORE ORDER (the real album usually ranks below weak title/uploader matches);
        // 'none' needs every chain PROVEN to end without the video — a guessed album binds a wrong repKey forever.
        val ranked = distinct.sortedByDescending { it.albumNameScore(title, artist) }
        // An UNPROVEN chain (blank/empty continuation, live token at the depth cap) is INCONCLUSIVE, never
        // rejected: lower-ranked candidates still walk (round-47); any unproven candidate blocks 'none'.
        var inconclusive = false
        for (cand in ranked) {
            val candId = playlistIdOf(cand.url)
            val page1 = store.cachedAlbumPlaylist(candId)
            // A null here = page 1 was NEVER fetched — zero fetched pages is no evidence: inconclusive, but
            // lower-ranked candidates still walk (a cached empty page IS evidence: the proven-not-here verdict).
            if (page1 == null) {
                inconclusive = true
                continue
            }
            var token = page1.nextpage
            var lastRows = page1.relatedStreams.size
            var depth = 0
            var walked = false
            while (depth < ALBUM_FALLBACK_PAGE_LIMIT) {
                val t = token ?: break
                // A DECODED BLANK continuation token is the throttled/degenerate shape (blank != EOF anywhere:
                // rows beyond it may exist) — inconclusive, never a proven end.
                if (t.isBlank()) {
                    inconclusive = true
                    break
                }
                // A null continuation or non-2xx throw is a failed fetch, not a proven end — inconclusive;
                // the lower-ranked candidates still get their walks.
                counter.requests++
                val next = runCatching { client.playlistNextPage(candId, t) }.getOrNull() ?: run {
                    inconclusive = true
                    break
                }
                lastRows = next.relatedStreams.size
                walked = true
                if (next.relatedStreams.any { videoIdOf(it.url) == videoId }) return candId
                token = next.nextpage
                depth++
            }
            when {
                // A fetched EMPTY continuation page (blank/{} throttle decoded to empty + null token) is
                // NOT a proven end — inconclusive.
                lastRows == 0 && walked -> inconclusive = true
                // ANY non-null token is an unproven stop: a LIVE token = the depth cap cut the chain short; a
                // DECODED BLANK token = throttled/degenerate (rows beyond it may exist). Inconclusive, never a verdict.
                token != null -> inconclusive = true
                // Proven not-in-this-candidate: keep the evidence walk for the
                // next-ranked candidate.
                else -> Unit
            }
        }
        if (inconclusive) {
            // Any unproven chain blocks the permanent 'none' (it would freeze the real album out); propagate
            // like a transport failure so doRefresh's retry latch re-attempts it.
            throw IllegalStateException("album resolution inconclusive (candidate chains unproven)")
        }
        // Every first page checked AND every chain provably ended without the video: the cacheable 'none'.
        // (a distinct empty search = the pre-existing no-candidates verdict.)
        return null
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