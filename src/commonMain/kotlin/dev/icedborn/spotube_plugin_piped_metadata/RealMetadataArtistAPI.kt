package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistOverview
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

private const val TOP_TRACKS_LIMIT = 10

internal class RealMetadataArtistAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
) : MetadataArtistAPI {

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
        val top = top10Tracks(id, channel)
        val albums = artistAlbumsPage(id, channel, null)
        return MetadataArtistOverview(
            artist = channel.toArtist(),
            top10Tracks = top,
            albums = albums,
            relatedArtists = emptyPagination(),
            featuredPlaylists = featuredPlaylistsPage(id, channel, null),
        )
    }

    override suspend fun relatedArtists(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataArtist.Basic> = emptyPagination()

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
        val items = slice.mapNotNull { id ->
            syntheticArtist(id) ?: runCatching { fetchChannel(id).toArtist() }.getOrNull()
        }
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
        // Same cached-value trust as channelNameFor (PipedSavedLibrary): a cached channel that decoded blank is a
        // MISS and re-fetched — CHANNEL_KEY is never invalidated, so trusting it would render the artist empty forever.
        store.cachedChannel(id)?.takeIf { it.name.isNotBlank() }?.let { return it }
        return client.channel(id)?.also { store.cacheChannel(id, it) }
            // Never cache a throttled fetch as a blank channel (CHANNEL_KEY is never invalidated). Return a
            // TRANSIENT blank instead of throwing — the unwrapped screens would hard-fail on a routine 429/5xx.
            ?: PipedChannelInfo()
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
            val page = client.search(query, PipedSearchFilter.MUSIC_ALBUMS, pagination.continuationToken())
            // EVERY page is filtered by the artist: YT Music search is fuzzy, so continuation pages
            // (and title-matching rows) mix in albums by other artists.
            val items = page?.items.orEmpty()
                .filter { it.type == "playlist" }
                .filter { uploadedByArtist(it, id, query) }
                .distinctBy { playlistIdOf(it.url) }
                .mapNotNull { it.toAlbumDetailed() }
            PaginationResult(
                items = items,
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
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
            val page = client.search(query, PipedSearchFilter.PLAYLISTS, pagination.continuationToken())
            val (own, others) = page?.items.orEmpty()
                .filter { it.type == "playlist" }
                .distinctBy { playlistIdOf(it.url) }
                .partition { uploadedByArtist(it, id, query) }
            val items = (own + others.filter { namesArtist(it, query) }).mapNotNull { it.toPlaylist() }
            PaginationResult(
                items = items,
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
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