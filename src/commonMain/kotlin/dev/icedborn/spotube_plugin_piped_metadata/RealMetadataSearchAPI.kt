package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSupportedSearchType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class RealMetadataSearchAPI(
    private val client: PipedClient,
    private val store: EntityStore,
) : MetadataSearchAPI {

    override val supportedSearchTypes: List<MetadataSupportedSearchType> = listOf(
        MetadataSupportedSearchType.ALL,
        MetadataSupportedSearchType.TRACK,
        MetadataSupportedSearchType.ALBUM,
        MetadataSupportedSearchType.ARTIST,
        MetadataSupportedSearchType.PLAYLIST,
    )

    override suspend fun search(query: String): List<MetadataSearchResult> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            coroutineScope {
                // The ALL tab combines YouTube Music and plain YouTube: songs first,
                // then videos from normal YouTube, then artists/albums/playlists.
                val songs = async { searchTracks(query, null).items }
                val videos = async { searchVideos(query).items }
                val artists = async { searchArtists(query, null).items }
                val albums = async { searchAlbums(query, null).items }
                val playlists = async { searchPlaylists(query, null).items }
                val all = listOf(songs, videos, artists, albums, playlists).awaitAll()
                buildList {
                    addAll(all[0]) // songs
                    addAll(all[1]) // plain YouTube videos
                    addAll(all[2])
                    addAll(all[3])
                    addAll(all[4])
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Regular YouTube video search, surfaced as songs so the app can play them. */
    suspend fun searchVideos(query: String, pagination: PaginationStrategy? = null): PaginationResult<MetadataSearchResult.Track> {
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(query, PipedSearchFilter.VIDEOS, pagination.continuationToken())
            val items = page?.items.orEmpty()
                .filter { it.type == "stream" || it.type == "video" }
                .mapNotNull { item -> item.toTrack()?.also { store.rememberTrack(it) } }
            PaginationResult(
                items = items.map { MetadataSearchResult.Track(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }

    override suspend fun searchTracks(
        query: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataSearchResult.Track> {
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(query, PipedSearchFilter.MUSIC_SONGS, pagination.continuationToken())
            val items = page?.items.orEmpty()
                .filter { it.type == "stream" || it.type == "video" }
                .mapNotNull { item -> item.toTrack()?.also { store.rememberTrack(it) } }
            PaginationResult(
                items = items.map { MetadataSearchResult.Track(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }

    override suspend fun searchArtists(
        query: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataSearchResult.Artist> {
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(query, PipedSearchFilter.MUSIC_ARTISTS, pagination.continuationToken())
            val items = page?.items.orEmpty().mapNotNull { it.toArtist() }
            PaginationResult(
                items = items.map { MetadataSearchResult.Artist(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }

    override suspend fun searchAlbums(
        query: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataSearchResult.Album> {
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(query, PipedSearchFilter.MUSIC_ALBUMS, pagination.continuationToken())
            val items = page?.items.orEmpty().mapNotNull { it.toAlbumBasic() }
            PaginationResult(
                items = items.map { MetadataSearchResult.Album(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }

    override suspend fun searchPlaylists(
        query: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataSearchResult.Playlist> {
        if (query.isBlank()) return emptyPagination()
        return runCatching {
            val page = client.search(query, PipedSearchFilter.MUSIC_PLAYLISTS, pagination.continuationToken())
            val items = page?.items.orEmpty().mapNotNull { it.toPlaylist() }
            PaginationResult(
                items = items.map { MetadataSearchResult.Playlist(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage),
            )
        }.getOrDefault(emptyPagination())
    }

    override suspend fun searchUsers(
        query: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataSearchResult.User> = emptyPagination()
}
