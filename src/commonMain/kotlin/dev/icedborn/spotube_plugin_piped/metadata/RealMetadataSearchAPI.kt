package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.toAlbumBasic
import dev.icedborn.spotube_plugin_piped.client.toArtist
import dev.icedborn.spotube_plugin_piped.client.toPlaylist
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.PageDedupe
import dev.icedborn.spotube_plugin_piped.core.continuationToken
import dev.icedborn.spotube_plugin_piped.core.emptyPagination
import dev.icedborn.spotube_plugin_piped.core.nextContinuation
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSupportedSearchType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Piped returns 20 rows on a full music_songs page. */
private const val FULL_SONGS_PAGE = 20

internal class RealMetadataSearchAPI(private val client: PipedClient, private val store: EntityStore) : MetadataSearchAPI {

    private val pages = PageDedupe()

    override val supportedSearchTypes: List<MetadataSupportedSearchType> = listOf(
        MetadataSupportedSearchType.ALL,
        MetadataSupportedSearchType.TRACK,
        MetadataSupportedSearchType.ALBUM,
        MetadataSupportedSearchType.ARTIST,
        MetadataSupportedSearchType.PLAYLIST,
    )

    override suspend fun search(query: String): List<MetadataSearchResult> {
        if (query.isBlank()) return emptyList()
        return orNull("search all ${query.length} chars") {
            coroutineScope {
                // Songs, then plain YouTube videos, then artists, albums and playlists.
                // Videos are searched only when the songs page came back short.
                val songs = async { searchTracks(query, null).items }
                val videos = async {
                    if (songs.await().size >= FULL_SONGS_PAGE) emptyList() else searchVideos(query).items
                }
                val artists = async { searchArtists(query, null).items }
                val albums = async { searchAlbums(query, null).items }
                val playlists = async { searchPlaylists(query, null).items }
                val all = listOf(songs, videos, artists, albums, playlists).awaitAll()
                val songIds = all[0].mapTo(HashSet()) { (it as MetadataSearchResult.Track).data.id }
                buildList {
                    addAll(all[0]) // songs
                    // Plain YouTube videos, minus tracks already listed as songs (same id would crash the host list).
                    addAll(all[1].filterNot { (it as MetadataSearchResult.Track).data.id in songIds })
                    addAll(all[2])
                    addAll(all[3])
                    addAll(all[4])
                }
            }
        } ?: emptyList()
    }

    /** Regular YouTube video search, surfaced as songs so the app can play them. */
    suspend fun searchVideos(query: String, pagination: PaginationStrategy? = null): PaginationResult<MetadataSearchResult.Track> {
        if (query.isBlank()) return emptyPagination()
        return orNull("search videos ${query.length} chars") {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.VIDEOS, token)
            val found = page?.items.orEmpty()
                .filter { it.type == "stream" || it.type == "video" }
                .mapNotNull { item -> item.toTrack()?.also { store.rememberTrack(it) } }
            val items = pages.distinct("search-videos:$query", token, found) { it.id }
            PaginationResult(
                items = items.map { MetadataSearchResult.Track(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        } ?: emptyPagination()
    }

    override suspend fun searchTracks(query: String, pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.Track> {
        if (query.isBlank()) return emptyPagination()
        return orNull("search tracks ${query.length} chars") {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.MUSIC_SONGS, token)
            val found = page?.items.orEmpty()
                .filter { it.type == "stream" || it.type == "video" }
                .mapNotNull { item -> item.toTrack()?.also { store.rememberTrack(it) } }
            val items = pages.distinct("search-songs:$query", token, found) { it.id }
            PaginationResult(
                items = items.map { MetadataSearchResult.Track(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        } ?: emptyPagination()
    }

    override suspend fun searchArtists(query: String, pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.Artist> {
        if (query.isBlank()) return emptyPagination()
        return orNull("search artists ${query.length} chars") {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.MUSIC_ARTISTS, token)
            val found = page?.items.orEmpty().mapNotNull { it.toArtist() }
            val items = pages.distinct("search-artists:$query", token, found) { it.id }
            PaginationResult(
                items = items.map { MetadataSearchResult.Artist(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        } ?: emptyPagination()
    }

    override suspend fun searchAlbums(query: String, pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.Album> {
        if (query.isBlank()) return emptyPagination()
        return orNull("search albums ${query.length} chars") {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.MUSIC_ALBUMS, token)
            val found = page?.items.orEmpty().mapNotNull { it.toAlbumBasic() }
            val items = pages.distinct("search-albums:$query", token, found) { it.id }
            PaginationResult(
                items = items.map { MetadataSearchResult.Album(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        } ?: emptyPagination()
    }

    override suspend fun searchPlaylists(query: String, pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.Playlist> {
        if (query.isBlank()) return emptyPagination()
        return orNull("search playlists ${query.length} chars") {
            val token = pagination.continuationToken()
            val page = client.search(query, PipedSearchFilter.MUSIC_PLAYLISTS, token)
            val found = page?.items.orEmpty().mapNotNull { it.toPlaylist() }
            val items = pages.distinct("search-playlists:$query", token, found) { it.id }
            PaginationResult(
                items = items.map { MetadataSearchResult.Playlist(data = it) },
                totalCount = items.size,
                nextPagination = nextContinuation(page?.nextpage, token),
            )
        } ?: emptyPagination()
    }

    override suspend fun searchUsers(query: String, pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.User> =
        emptyPagination()
}
