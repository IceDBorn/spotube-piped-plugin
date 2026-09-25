package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistOverview
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private const val TOP_TRACKS_LIMIT = 10
private const val RELATED_TIMEOUT_MS = 8_000L
private const val RELATED_CACHE_SIZE = 50

private val channelFetches = HashMap<String, CompletableDeferred<PipedChannelInfo?>>()

internal class RealMetadataArtistAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    // A function, not the track API, so tests can pass a fake radio.
    private val radio: suspend (seedIds: List<String>) -> List<MetadataTrack> = { emptyList() },
) : MetadataArtistAPI {

    private val relatedCache = LinkedHashMap<String, List<MetadataArtist.Basic>>()

    override suspend fun getArtist(id: String): MetadataArtist.Detailed =
        syntheticArtist(id) ?: fetchChannel(id).toArtist()

    override suspend fun getArtistTop10Tracks(id: String): List<MetadataTrack> {
        if (syntheticArtist(id) != null) return emptyList()
        return top10Tracks(id, fetchChannel(id))
    }

    override suspend fun artistOverview(id: String): MetadataArtistOverview {
        syntheticArtist(id)?.let {
            return MetadataArtistOverview(
                artist = it, top10Tracks = emptyList(), albums = emptyPagination(),
                relatedArtists = emptyPagination(), featuredPlaylists = emptyPagination(),
            )
        }
        val channel = fetchChannel(id)
        return coroutineScope {
            val top = async { top10Tracks(id, channel) }
            val albums = async { artistAlbumsPage(id, channel, null) }
            val playlists = async { featuredPlaylistsPage(id, channel, null) }
            val related = async { withTimeoutOrNull(RELATED_TIMEOUT_MS) { relatedFor(id) { top.await() } } }
            val relatedItems = related.await().orEmpty()
            MetadataArtistOverview(
                artist = channel.toArtist(),
                top10Tracks = top.await(),
                albums = albums.await(),
                relatedArtists = PaginationResult(relatedItems, relatedItems.size, null),
                featuredPlaylists = playlists.await(),
            )
        }
    }

    override suspend fun relatedArtists(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataArtist.Basic> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val items = withTimeoutOrNull(RELATED_TIMEOUT_MS) {
            relatedFor(id) { top10Tracks(id, fetchChannel(id)) }
        }.orEmpty()
        // One page only: the host keys list items by id, so a second page would repeat rows and crash it.
        val paging = pagination.getOffsetOrDefault()
        val page = items.drop(paging.offset).take(paging.limit)
        return PaginationResult(page, items.size, null)
    }

    override suspend fun featuredPlaylists(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataPlaylist> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val channel = fetchChannel(id)
        return featuredPlaylistsPage(id, channel, pagination)
    }

    override suspend fun getArtistAlbums(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataAlbum.Detailed> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val channel = fetchChannel(id)
        return artistAlbumsPage(id, channel, pagination)
    }

    override suspend fun savedArtists(pagination: PaginationStrategy?): PaginationResult<MetadataArtist.Detailed> {
        mirror.maybeRefresh()
        val paging = pagination.getOffsetOrDefault()
        val ids = mirror.allSavedArtistIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapConcurrently { id ->
            syntheticArtist(id) ?: runCatching { fetchChannel(id).toArtist() }.getOrNull()
        }.filterNotNull()
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            // Local saved-set ids are AUTHORITATIVE (the list terminates): page by the fixed limit, so a
            // transiently-failed page never hides tail ids nor re-requests failing ids (account lists vary).
            nextPagination = if (paging.offset + paging.limit < ids.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedArtists(ids: List<String>): List<Boolean> = mirror.isSavedArtists(ids)

    override suspend fun saveArtists(ids: List<String>) {
        val canonical = ids.map(::canonicalArtistId).distinct()
        // Pass the FULL id set, not just library-fresh ids: an id whose save committed locally but never reached
        // the mirror must keep retrying (already-saved ids are cheap no-ops: rebind + dedupe).
        library.saveArtists(canonical)
        mirror.save(SavedKind.ARTIST, canonical)
    }

    override suspend fun removeSavedArtists(ids: List<String>) {
        val canonical = ids.map(::canonicalArtistId).distinct()
        mirror.remove(SavedKind.ARTIST, canonical)
        mirror.removeLibraryEntries(SavedKind.ARTIST, canonical)
    }
    /** Synthetic ids (channel:NAME) come from uploader URLs without a parseable channel id; the name is
     * embedded, so no instance fetch is possible. */
    private fun syntheticArtist(id: String): MetadataArtist.Detailed? {
        if (!id.startsWith("channel:")) return null
        val name = id.removePrefix("channel:").let(::cleanArtistName)
        return MetadataArtist.Detailed(
            genres = emptyList(),
            biography = null,
            followersCount = null,
            id = id,
            name = name,
            thumbnails = emptyList(),
            externalUri = null,
        )
    }

    private suspend fun fetchChannel(id: String): PipedChannelInfo {
        // A cached channel that decoded blank is a MISS (same trust rule as channelNameFor in PipedSavedLibrary).
        val cached = store.cachedChannel(id)?.takeIf { it.name.isNotBlank() }
        if (cached != null && store.channelFresh(id)) return cached
        // Concurrent getArtist/artistOverview calls for one id share a single request.
        channelFetches[id]?.let { return runCatching { it.await() }.getOrNull() ?: cached ?: PipedChannelInfo() }
        val pending = CompletableDeferred<PipedChannelInfo?>()
        channelFetches[id] = pending
        var fetched: PipedChannelInfo? = null
        try {
            fetched = try {
                client.channel(id)?.takeIf { it.name.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } finally {
            channelFetches.remove(id)
            pending.complete(fetched)
        }
        if (fetched != null) store.cacheChannel(id, fetched)
        // A failed fetch serves the stale copy, or a TRANSIENT blank: never cached, and never thrown
        // (the screens would hard-fail on a routine 429/5xx).
        return fetched ?: cached ?: PipedChannelInfo()
    }

    /** Topic channels carry no uploads, so channel videos are mixed with a YT Music song search for the artist
     * name; the channel's own uploader wins ties. */
    private suspend fun top10Tracks(id: String, channel: PipedChannelInfo): List<MetadataTrack> {
        val fromChannel = channel.relatedStreams
            .filter { it.type == "stream" || it.type == "video" }
            .mapNotNull { it.toTrack()?.also { track -> store.rememberTrack(track) } }
        if (fromChannel.size >= 5) return fromChannel.take(TOP_TRACKS_LIMIT)
        // A transient blank channel must never fire a blank-name search: some instances reject q= non-2xx (the
        // unwrapped screens hard-fail), others 200 with arbitrary rows that sort ahead. Guard like artistAlbumsPage.
        if (channel.name.isBlank()) return fromChannel.take(TOP_TRACKS_LIMIT)

        // Degrade-not-throw like artistAlbumsPage: search() THROWS on non-2xx (routine 429/5xx) and the unwrapped
        // call would hard-fail the artist screens — runCatching keeps the old empty-page degradation.
        val page = runCatching { client.search(channel.name, PipedSearchFilter.MUSIC_SONGS) }.getOrNull()
        val songs = page?.items.orEmpty()
            .filter { it.type == "stream" || it.type == "video" }
            .sortedByDescending { channelIdOf(it.uploaderUrl) == id }
            .mapNotNull { it.toTrack()?.also { track -> store.rememberTrack(track) } }
        return (fromChannel + songs).distinctBy { it.id }.take(TOP_TRACKS_LIMIT)
    }

    /** Related artists from the YT Music radio of this artist's first two top tracks. Cached, because
     * the radio is not cached anywhere else and a page visit otherwise costs the requests again.
     * [top] is a lambda, so a cache hit costs no top-tracks request either. */
    private suspend fun relatedFor(
        id: String,
        top: suspend () -> List<MetadataTrack>,
    ): List<MetadataArtist.Basic> {
        if (syntheticArtist(id) != null) return emptyList()
        val key = canonicalArtistId(id)
        relatedCache[key]?.let { return it }
        val seeds = top().take(2).map { it.id }
        if (seeds.isEmpty()) return emptyList()
        // The radio request can fail (429/5xx); degrade to no section rather than blank the page.
        val ranked = orNull("related artists $id") { rankRadioArtists(radio(seeds), setOf(key)) }.orEmpty()
        if (ranked.isEmpty()) return emptyList()
        // Radio rows carry no avatars; the channel fetch adds them, is shared per id and cached for hours.
        val withAvatars = ranked.mapConcurrently { basic ->
            val channel = orNull { fetchChannel(basic.id) }
            if (channel != null && channel.name.isNotBlank()) channel.toArtist().toBasic() else basic
        }
        // Only a non-empty result is cached: an empty one is often a transient radio failure, and
        // caching it would hide the section until restart.
        relatedCache[key] = withAvatars
        if (relatedCache.size > RELATED_CACHE_SIZE) relatedCache.remove(relatedCache.keys.first())
        return withAvatars
    }

    private suspend fun artistAlbumsPage(
        id: String,
        channel: PipedChannelInfo,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataAlbum.Detailed> {
        // Auto-generated music channels end in " - Topic"; that suffix poisons the music_albums search
        // query (YT Music then ranks other channels' playlists first), so search the cleaned name instead.
        val query = cleanArtistName(channel.name)
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.MUSIC_ALBUMS, token)
            // EVERY page is filtered by the artist: YT Music search is fuzzy, so continuation pages
            // (and title-matching rows) mix in albums by other artists.
            val matched = page?.items.orEmpty()
                .filter { it.type == "playlist" }
                .filter { uploadedByArtist(it, id, query) }
                .mapNotNull { it.toAlbumDetailed() }
            val items = distinctAcrossPages("artist-albums:$id", token, matched) { it.id }
            PaginationResult(
                items = items,
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        }.getOrDefault(emptyPagination())
    }

    private suspend fun featuredPlaylistsPage(
        id: String,
        channel: PipedChannelInfo,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataPlaylist> {
        // The artist's own playlists first, then others that name the artist (fan and label playlists).
        // Unlike albums, a name match is enough here: many artists upload no playlists of their own.
        val query = cleanArtistName(channel.name)
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.PLAYLISTS, token)
            val (own, others) = page?.items.orEmpty()
                .filter { it.type == "playlist" }
                .partition { uploadedByArtist(it, id, query) }
            val matched = (own + others.filter { namesArtist(it, query) }).mapNotNull { it.toPlaylist() }
            val items = distinctAcrossPages("artist-playlists:$id", token, matched) { it.id }
            PaginationResult(
                items = items,
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        }.getOrDefault(emptyPagination())
    }
}

private fun namesArtist(item: PipedSearchItem, artistName: String): Boolean =
    (item.name.ifBlank { item.title }).contains(artistName, ignoreCase = true) ||
        item.uploaderName.contains(artistName, ignoreCase = true)

/** The row is this artist's when its uploader channel id matches; otherwise only an EXACT cleaned
 * uploader-name match passes. Titles are NOT matched: a title that mentions the artist admits other artists' rows. */
private fun uploadedByArtist(item: PipedSearchItem, channelId: String, artistName: String): Boolean {
    val uploaderId = channelIdOf(item.uploaderUrl)
    if (uploaderId == channelId) return true
    val uploaderName = cleanArtistName(item.uploaderName)
    return uploaderName.equals(artistName, ignoreCase = true)
}
