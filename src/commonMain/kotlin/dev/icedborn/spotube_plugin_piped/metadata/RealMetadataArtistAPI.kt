package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedChannelInfo
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.channelIdOf
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toAlbumDetailed
import dev.icedborn.spotube_plugin_piped.client.toArtist
import dev.icedborn.spotube_plugin_piped.client.toPlaylist
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.PageDedupe
import dev.icedborn.spotube_plugin_piped.core.continuationToken
import dev.icedborn.spotube_plugin_piped.core.emptyPagination
import dev.icedborn.spotube_plugin_piped.core.getOffsetOrDefault
import dev.icedborn.spotube_plugin_piped.core.nextContinuation
import dev.icedborn.spotube_plugin_piped.core.savedPage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.SavedKind
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistOverview
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val TOP_TRACKS_LIMIT = 10
private const val RELATED_TIMEOUT_MS = 8_000L
private const val RELATED_CACHE_SIZE = 50

internal class RealMetadataArtistAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    // A function, not the track API, so tests can pass a fake radio.
    private val radio: suspend (seedIds: List<String>) -> List<MetadataTrack> = { emptyList() },
) : MetadataArtistAPI {

    private val relatedCache = LinkedHashMap<String, List<MetadataArtist.Basic>>()
    private val channelFetches = HashMap<String, CompletableDeferred<PipedChannelInfo?>>()
    private val pages = PageDedupe()
    private val artistMatch = MusicArtistMatch(client)

    /** The YouTube Music artists behind radio [candidates], shared with Home so both reuse one name cache. */
    internal suspend fun matchMusicArtists(candidates: List<MetadataArtist.Basic>, exclude: Set<String>) =
        artistMatch.resolve(candidates, exclude)

    /** Played artists as their YouTube Music artist, so Home lists the artist and not a video channel. */
    internal suspend fun matchPlayedArtists(played: List<MetadataArtist.Basic>) = artistMatch.resolvePlayed(played)

    /** A failed fetch gives a blank-named artist that keeps [id], so the host never sees an empty id. */
    override suspend fun getArtist(id: String): MetadataArtist.Detailed =
        syntheticArtist(id) ?: fetchChannel(id)?.toArtist() ?: blankArtist(id)

    override suspend fun getArtistTop10Tracks(id: String): List<MetadataTrack> {
        if (syntheticArtist(id) != null) return emptyList()
        return top10Tracks(id, fetchChannel(id) ?: return emptyList())
    }

    override suspend fun artistOverview(id: String): MetadataArtistOverview {
        syntheticArtist(id)?.let { return emptyOverview(it) }
        val channel = fetchChannel(id) ?: return emptyOverview(blankArtist(id))
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

    override suspend fun relatedArtists(id: String, pagination: PaginationStrategy?): PaginationResult<MetadataArtist.Basic> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val items = withTimeoutOrNull(RELATED_TIMEOUT_MS) {
            relatedFor(id) { fetchChannel(id)?.let { top10Tracks(id, it) }.orEmpty() }
        }.orEmpty()
        // One page only: the host keys list items by id, so a second page would repeat rows and crash it.
        val paging = pagination.getOffsetOrDefault()
        val page = items.drop(paging.offset).take(paging.limit)
        return PaginationResult(page, items.size, null)
    }

    override suspend fun featuredPlaylists(id: String, pagination: PaginationStrategy?): PaginationResult<MetadataPlaylist> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val channel = fetchChannel(id) ?: return emptyPagination()
        return featuredPlaylistsPage(id, channel, pagination)
    }

    override suspend fun getArtistAlbums(id: String, pagination: PaginationStrategy?): PaginationResult<MetadataAlbum.Detailed> {
        if (syntheticArtist(id) != null) return emptyPagination()
        val channel = fetchChannel(id) ?: return emptyPagination()
        return artistAlbumsPage(id, channel, pagination)
    }

    override suspend fun savedArtists(pagination: PaginationStrategy?): PaginationResult<MetadataArtist.Detailed> {
        mirror.maybeRefresh()
        return savedPage(mirror.allSavedArtistIds(), pagination) { id -> syntheticArtist(id) ?: fetchChannel(id)?.toArtist() }
    }

    override suspend fun isSavedArtists(ids: List<String>): List<Boolean> = mirror.isSavedArtists(ids)

    override suspend fun saveArtists(ids: List<String>) {
        val canonical = ids.map(::canonicalArtistId).distinct()
        // Every id goes to the mirror, so one saved locally whose mirror write failed is retried.
        library.saveArtists(canonical)
        mirror.save(SavedKind.ARTIST, canonical)
    }

    override suspend fun removeSavedArtists(ids: List<String>) {
        val canonical = ids.map(::canonicalArtistId).distinct()
        mirror.remove(SavedKind.ARTIST, canonical)
        mirror.removeLibraryEntries(SavedKind.ARTIST, canonical)
    }

    /** Synthetic ids ("channel:<name>") come from uploader URLs without a parseable channel id; the name is
     * embedded, so no instance fetch is possible. */
    private fun blankArtist(id: String) = MetadataArtist.Detailed(
        genres = emptyList(),
        biography = null,
        followersCount = null,
        id = id,
        name = "",
        thumbnails = emptyList(),
        externalUri = null,
    )

    private fun emptyOverview(artist: MetadataArtist.Detailed) = MetadataArtistOverview(
        artist = artist,
        top10Tracks = emptyList(),
        albums = emptyPagination(),
        relatedArtists = emptyPagination(),
        featuredPlaylists = emptyPagination(),
    )

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

    /** The channel, a stale copy when the fetch fails, or null when there is neither. Never throws. */
    private suspend fun fetchChannel(id: String): PipedChannelInfo? {
        // A cached channel that decoded blank is a miss.
        val cached = store.cachedChannel(id)?.takeIf { it.name.isNotBlank() }
        if (cached != null && store.channelFresh(id)) return cached
        // Concurrent getArtist/artistOverview calls for one id share a single request.
        channelFetches[id]?.let { return runCatching { it.await() }.getOrNull() ?: cached }
        val pending = CompletableDeferred<PipedChannelInfo?>()
        channelFetches[id] = pending
        var fetched: PipedChannelInfo? = null
        try {
            fetched = try {
                // The requested id wins, so a body without one still yields a keyed artist.
                client.channel(id)?.takeIf { it.name.isNotBlank() }?.copy(id = id)
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
        return fetched ?: cached
    }

    /** Topic channels carry no uploads, so channel videos are mixed with a YT Music song search for the artist
     * name; the channel's own uploader wins ties. */
    private suspend fun top10Tracks(id: String, channel: PipedChannelInfo): List<MetadataTrack> {
        val fromChannel = channel.relatedStreams
            .filter { it.type == "stream" || it.type == "video" }
            .mapNotNull { it.toTrack()?.also { track -> store.rememberTrack(track) } }
        if (fromChannel.size >= 5) return fromChannel.take(TOP_TRACKS_LIMIT)
        // A blank name would search for arbitrary rows.
        if (channel.name.isBlank()) return fromChannel.take(TOP_TRACKS_LIMIT)

        // search() throws on a non-2xx status, which would fail the artist screen.
        val page = runCatching { client.search(channel.name, PipedSearchFilter.MUSIC_SONGS) }.getOrNull()
        val songs = page?.items.orEmpty()
            .filter { it.type == "stream" || it.type == "video" }
            .sortedByDescending { channelIdOf(it.uploaderUrl) == id }
            .mapNotNull { it.toTrack()?.also { track -> store.rememberTrack(track) } }
        return (fromChannel + songs).distinctBy { it.id }.take(TOP_TRACKS_LIMIT)
    }

    /** Related artists from the radio of this artist's first two top tracks, cached because nothing else caches
     * the radio. [top] is a lambda, so a cache hit costs no top-tracks request. */
    private suspend fun relatedFor(id: String, top: suspend () -> List<MetadataTrack>): List<MetadataArtist.Basic> {
        if (syntheticArtist(id) != null) return emptyList()
        val key = canonicalArtistId(id)
        relatedCache[key]?.let { return it }
        val seeds = top().take(2).map { it.id }
        if (seeds.isEmpty()) return emptyList()
        // The radio request can fail (429/5xx); degrade to no section rather than blank the page.
        val ranked = orNull("related artists $id") { rankRadioArtists(radio(seeds), setOf(key), RADIO_CANDIDATES) }.orEmpty()
        // The match also adds the avatars that radio rows lack.
        val related = artistMatch.resolve(ranked, setOf(key))
        if (related.isEmpty()) return emptyList()
        // Only a non-empty result is cached: an empty one is often a transient radio failure, and
        // caching it would hide the section until restart.
        relatedCache[key] = related
        if (relatedCache.size > RELATED_CACHE_SIZE) relatedCache.remove(relatedCache.keys.first())
        return related
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
            // Every page is filtered by the artist, since the fuzzy search mixes in other artists' albums.
            val matched = page?.items.orEmpty()
                .filter { it.type == "playlist" }
                .filter { uploadedByArtist(it, id, query) }
                .mapNotNull { it.toAlbumDetailed() }
            val items = pages.distinct("artist-albums:$id", token, matched) { it.id }
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
            val items = pages.distinct("artist-playlists:$id", token, matched) { it.id }
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

/** The row is this artist's when its uploader channel id or cleaned uploader name matches exactly. Titles are not
 * matched, since a title that mentions the artist admits other artists' rows. */
private fun uploadedByArtist(item: PipedSearchItem, channelId: String, artistName: String): Boolean {
    val uploaderId = channelIdOf(item.uploaderUrl)
    if (uploaderId == channelId) return true
    val uploaderName = cleanArtistName(item.uploaderName)
    return uploaderName.equals(artistName, ignoreCase = true)
}
