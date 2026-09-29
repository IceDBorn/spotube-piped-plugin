package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.PipedSearchPage
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.client.playlistIdOf
import dev.icedborn.spotube_plugin_piped.client.videoIdOf
import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.NO_ALBUM
import dev.icedborn.spotube_plugin_piped.store.cacheAlbumChainPage
import dev.icedborn.spotube_plugin_piped.store.cachedAlbumChainPage
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.extras.logger.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val albumLog = Logger("PipedAlbum")

private const val ALBUM_CANDIDATE_LIMIT = 7
private const val ALBUM_FALLBACK_PAGE_LIMIT = 6

/** Requests one album lookup may spend: the streams fetch plus a search and 7 candidate pages for each of 2 queries. */
internal const val ALBUM_LOOKUP_BUDGET = 17

/** Resolves a music video to the album playlist whose first page contains it. Candidates: music_albums
 * search for the artist, then for "title artist"; each first page is checked for the video id. */
internal class AlbumLookup(private val client: PipedClient, private val store: EntityStore) {
    /** The album, or null when the searches worked and found none, with the number of requests spent. */
    data class AlbumResolve(val albumId: String?, val requests: Int)

    /** Counts requests and throws once [budget] is spent; an exhausted lookup is inconclusive, never "none". */
    internal class RequestCounter(private val budget: Int = Int.MAX_VALUE) {
        var requests = 0
            private set

        fun charge() {
            if (requests >= budget) throw LookupBudgetSpent(requests)
            requests++
        }
    }

    class LookupBudgetSpent(val requests: Int) : IllegalStateException("album lookup budget spent after $requests requests")

    /** Pass a [counter] to read the requests a failed lookup spent. */
    suspend fun resolveAlbumForVideo(
        videoId: String,
        budget: Int = Int.MAX_VALUE,
        counter: RequestCounter = RequestCounter(budget),
    ): AlbumResolve {
        val info = store.cachedStreams(videoId) ?: run {
            counter.charge()
            client.streamsForMetadata(videoId)
        }?.also { store.cacheStreams(videoId, it) }
            // Transport/ambiguous fetch: never fabricate a 'not an album'
            // verdict from an all-defaults decode — the caller must retry.
            ?: throw IllegalStateException("streams fetch failed for $videoId")
        val albumId = resolveForNames(info.title, info.uploader.orEmpty().let(::cleanArtistName), videoId, counter)
        return AlbumResolve(albumId, counter.requests)
    }

    suspend fun resolveForNames(title: String, artist: String, videoId: String, counter: RequestCounter = RequestCounter()): String? {
        // The verdict is per video: a same-titled upload of another video may still be on an album.
        if (store.cachedTrackAlbum(videoId) == NO_ALBUM) return null
        val distinct = mutableListOf<PipedSearchItem>()
        // The artist's own albums are searched and checked first; the title search runs only when they miss.
        if (artist.isNotBlank()) {
            // A null search page is a failed fetch, never a zero-hit, so no "none" is cached for it.
            counter.charge()
            val artistPage = client.search(artist, PipedSearchFilter.MUSIC_ALBUMS)
                ?: throw IllegalStateException("album search failed (artist)")
            val found = candidatesOf(artistPage).filter { it.uploaderMatches(artist) }.take(ALBUM_CANDIDATE_LIMIT).toList()
            distinct += found
            firstPageContaining(found, videoId, counter)?.let { return it }
        }
        val titleQuery = listOfNotNull(title.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() })
            .joinToString(" ")
        if (titleQuery.isNotBlank()) {
            counter.charge()
            val titlePage = client.search(titleQuery, PipedSearchFilter.MUSIC_ALBUMS)
                ?: throw IllegalStateException("album search failed (title)")
            val seen = distinct.mapTo(HashSet()) { playlistIdOf(it.url) }
            val found = candidatesOf(titlePage).filter { playlistIdOf(it.url) !in seen }.take(ALBUM_CANDIDATE_LIMIT).toList()
            distinct += found
            firstPageContaining(found, videoId, counter)?.let { return it }
        }
        if (distinct.isEmpty()) {
            store.cacheTrackAlbum(videoId, NO_ALBUM)
            return null
        }
        // Chains are walked best score first. A "none" needs every chain proven to end without the video.
        val ranked = distinct.sortedByDescending { it.albumNameScore(title, artist) }
        // An unproven chain makes the lookup inconclusive, but lower candidates are still walked.
        var inconclusive = false
        for (candidate in ranked) {
            val id = playlistIdOf(candidate.url)
            when (walkChain(id, videoId, counter)) {
                Chain.FOUND -> return id
                Chain.UNPROVEN -> inconclusive = true
                Chain.ABSENT -> Unit
            }
        }
        if (inconclusive) {
            albumLog.w { "album resolution inconclusive for $videoId after ${distinct.size} candidates" }
            // Thrown like a transport failure, so the refresh retries the video later.
            throw IllegalStateException("album resolution inconclusive (candidate chains unproven)")
        }
        // Every first page checked and every chain proven to end without the video: the cacheable "none".
        store.cacheTrackAlbum(videoId, NO_ALBUM)
        return null
    }

    private enum class Chain { FOUND, ABSENT, UNPROVEN }

    /** Walks the continuation pages of album [id] past its cached page 1, looking for [videoId]. Pages already
     * walked are read from the cache and cost nothing, so a chain deeper than the page limit makes progress
     * across refreshes instead of restarting and never ending. */
    private suspend fun walkChain(id: String, videoId: String, counter: RequestCounter): Chain {
        // No cached page 1 means it was never fetched, which is no evidence either way.
        val page1 = store.cachedAlbumPlaylist(id) ?: return Chain.UNPROVEN
        var token = page1.nextpage
        var lastRows = page1.relatedStreams.size
        var walked = false
        // Only fetched pages count against the limit, so cached ones let a deep chain reach further each refresh.
        var fetched = 0
        while (fetched < ALBUM_FALLBACK_PAGE_LIMIT) {
            val t = token ?: return if (lastRows == 0 && walked) Chain.UNPROVEN else Chain.ABSENT
            // A blank token is a throttle, and a failed fetch is no end either.
            if (t.isBlank()) return Chain.UNPROVEN
            // A cached page was walked by an earlier refresh, so it is evidence and costs no request.
            val cached = store.cachedAlbumChainPage(id, t)
            val next = cached ?: run {
                counter.charge()
                fetched++
                val page = orNull("album page $id next") { client.playlistNextPage(id, t) } ?: return Chain.UNPROVEN
                // Only a page that proves something is cached. An empty page or a blank token is a throttle that
                // this walk treats as no end, and caching it would leave the chain unproven on every refresh.
                if (page.relatedStreams.isNotEmpty() && page.nextpage?.isBlank() != true) {
                    store.cacheAlbumChainPage(id, t, page)
                }
                page
            }
            lastRows = next.relatedStreams.size
            walked = true
            if (next.relatedStreams.any { videoIdOf(it.url) == videoId }) return Chain.FOUND
            token = next.nextpage
        }
        // An empty last page may be a decoded throttle, and a live token means the depth cap cut the chain.
        return if (token == null && !(lastRows == 0 && walked)) Chain.ABSENT else Chain.UNPROVEN
    }

    private fun candidatesOf(page: PipedSearchPage): Sequence<PipedSearchItem> =
        page.items.asSequence().filter { it.type == "playlist" && playlistIdOf(it.url).isNotEmpty() }

    /** The first candidate whose first page holds [videoId]. A page that fails to load is skipped: no evidence. */
    private suspend fun firstPageContaining(candidates: List<PipedSearchItem>, videoId: String, counter: RequestCounter): String? {
        for (candidate in candidates) {
            val id = playlistIdOf(candidate.url)
            val page = store.cachedAlbumPlaylist(id) ?: run {
                counter.charge()
                // A thrown non-2xx must not abort the other candidates.
                val fetched = orNull("album page $id") { client.playlist(id) } ?: return@run null
                // A decodable page, even an empty one, is evidence: the chain walk below reads it from the cache.
                store.cacheAlbumPlaylist(id, fetched)
                fetched
            } ?: continue
            if (page.relatedStreams.any { videoIdOf(it.url) == videoId }) return id
        }
        return null
    }
}

private fun PipedSearchItem.uploaderMatches(artist: String): Boolean {
    val cleaned = cleanArtistName(uploaderName)
    return cleaned.isNotBlank() &&
        (
            cleaned.equals(artist, ignoreCase = true) ||
                cleaned.contains(artist, ignoreCase = true) ||
                artist.contains(cleaned, ignoreCase = true)
            )
}

private fun PipedSearchItem.albumNameScore(trackTitle: String, trackArtist: String?): Float {
    var score = 0f
    if (trackTitle.isNotBlank() &&
        (name.contains(trackTitle, ignoreCase = true) || title.contains(trackTitle, ignoreCase = true))
    ) {
        score += 0.5f
    }
    val cleanedUploader = cleanArtistName(uploaderName)
    if (!trackArtist.isNullOrBlank() &&
        (cleanedUploader.equals(trackArtist, ignoreCase = true) || name.contains(trackArtist, ignoreCase = true))
    ) {
        score += 0.4f
    }
    return score
}
