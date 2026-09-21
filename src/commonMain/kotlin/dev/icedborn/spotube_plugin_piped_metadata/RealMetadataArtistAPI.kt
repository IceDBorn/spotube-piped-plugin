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

    override suspend fun getArtist(id: String): MetadataArtist.Detailed = fetchChannel(id).toArtist()

    override suspend fun getArtistTop10Tracks(id: String): List<MetadataTrack> =
        top10Tracks(id, fetchChannel(id))

    override suspend fun artistOverview(id: String): MetadataArtistOverview {
        val channel = fetchChannel(id)
        val top = top10Tracks(id, channel)
        val albums = artistAlbumsPage(id, channel, null)
        return MetadataArtistOverview(
            artist = channel.toArtist(),
            top10Tracks = top,
            albums = albums,
            relatedArtists = emptyPagination(),
            featuredPlaylists = emptyPagination(),
        )
    }

    override suspend fun relatedArtists(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataArtist.Basic> = emptyPagination()

    override suspend fun featuredPlaylists(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataPlaylist> = emptyPagination()

    override suspend fun getArtistAlbums(
        id: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataAlbum.Detailed> {
        val channel = fetchChannel(id)
        return artistAlbumsPage(id, channel, pagination)
    }

    override suspend fun savedArtists(pagination: PaginationStrategy?): PaginationResult<MetadataArtist.Detailed> {
        mirror.maybeRefresh()
        val paging = pagination.getOffsetOrDefault()
        val ids = mirror.allSavedArtistIds()
        val slice = ids.drop(paging.offset).take(paging.limit)
        val items = slice.mapNotNull { id -> runCatching { fetchChannel(id).toArtist() }.getOrNull() }
        return PaginationResult(
            items = items,
            totalCount = ids.size,
            nextPagination = if (paging.offset + paging.limit < ids.size) {
                PaginationStrategy.Offset(paging.offset + paging.limit, paging.limit)
            } else {
                null
            },
        )
    }

    override suspend fun isSavedArtists(ids: List<String>): List<Boolean> = mirror.isSavedArtists(ids)

    override suspend fun saveArtists(ids: List<String>) {
            val already = library.isSavedArtists(ids)
            val fresh = ids.filterIndexed { i, _ -> !already[i] }
            library.saveArtists(ids)
            mirror.save(SavedKind.ARTIST, fresh)
        }

    override suspend fun removeSavedArtists(ids: List<String>) {

        mirror.remove(SavedKind.ARTIST, ids)

        library.removeArtists(ids)

    }
    private suspend fun fetchChannel(id: String): PipedChannelInfo {
        store.cachedChannel(id)?.let { return it }
        val fetched = client.channel(id)
        store.cacheChannel(id, fetched)
        return fetched
    }

    /**
     * Topic channels carry no uploads, so channel videos are mixed with a YT Music
     * song search for the artist name; the channel's own uploader wins ties.
     */
    private suspend fun top10Tracks(id: String, channel: PipedChannelInfo): List<MetadataTrack> {
        val fromChannel = channel.relatedStreams
            .filter { it.type == "stream" || it.type == "video" }
            .mapNotNull { it.toTrack()?.also { track -> store.rememberTrack(track) } }
        if (fromChannel.size >= 5) return fromChannel.take(TOP_TRACKS_LIMIT)

        val page = client.search(channel.name, PipedSearchFilter.MUSIC_SONGS)
        val songs = page.items
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
        if (channel.name.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(channel.name, PipedSearchFilter.MUSIC_ALBUMS, pagination.continuationToken())
            // First page: only albums by this artist; later pages: keep whatever the query returns.
            val items = page.items
                .filter { it.type == "playlist" }
                .filter { album -> pagination != null || albumMatchesArtist(album, id, channel.name) }
                .mapNotNull { it.toAlbumDetailed(channel.toArtistBasic()) }
            PaginationResult(
                items = items,
                totalCount = items.size,
                nextPagination = nextContinuation(page.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }
}

private fun albumMatchesArtist(album: PipedSearchItem, channelId: String, channelName: String): Boolean {
    val sameChannel = channelIdOf(album.uploaderUrl) == channelId
    val sameName = channelName.isNotBlank() &&
        (album.uploaderName.equals(channelName, ignoreCase = true) ||
            album.name.contains(channelName, ignoreCase = true) ||
            album.title.contains(channelName, ignoreCase = true))
    return sameChannel || sameName
}
