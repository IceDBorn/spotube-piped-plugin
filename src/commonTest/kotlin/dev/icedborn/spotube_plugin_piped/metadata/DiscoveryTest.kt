package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.albumStub
import dev.icedborn.spotube_plugin_piped.client.toAlbumBasic
import dev.icedborn.spotube_plugin_piped.client.toPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home discovery: the artists the user does not play yet, and their songs, albums and playlists. */
class DiscoveryTest {

    private val gamma = artist("UCgamma00000000000000000", "Gamma")
    private val delta = artist("UCdelta00000000000000000", "Delta")
    private val known = artist("UCknown00000000000000000", "Known")

    private class Setup {
        var clock = 0L
        val radioCalls = mutableListOf<String>()
        var matchCalls = 0
        val searches = mutableListOf<String>()
        var radios: Map<String, List<MetadataTrack>> = emptyMap()
        var albums: Map<String, List<MetadataAlbum.Basic>> = emptyMap()
        var playlists: Map<String, List<MetadataPlaylist>> = emptyMap()
        var searchResults: Map<String, List<MetadataPlaylist>> = emptyMap()
        val discovery = Discovery(
            radio = { id ->
                radioCalls += id
                delay(10)
                radios[id].orEmpty()
            },
            match = { candidates, exclude ->
                matchCalls++
                candidates.filter { it.id !in exclude }
            },
            albumsOf = { id -> albums[id] ?: error("album search failed") },
            playlistsOf = { id -> playlists[id].orEmpty() },
            searchPlaylists = { query ->
                searches += query
                searchResults[query].orEmpty()
            },
            now = { clock },
        )
    }

    @Test
    fun `new artists leave out known artists and rank by radio count`() = runTest {
        val s = Setup()
        s.radios = mapOf("seed1" to listOf(track("a", delta), track("b", gamma), track("c", gamma), track("d", known)))
        val found = s.discovery.newArtists(listOf(track("seed1", known)), listOf(known.id))
        assertEquals(listOf(gamma.id, delta.id), found.map { it.id })
    }

    @Test
    fun `concurrent sections share one artist build, even an empty one`() = runTest {
        val s = Setup()
        val seeds = listOf(track("seed1", known))
        val results = listOf(
            async { s.discovery.newArtists(seeds, emptyList()) },
            async { s.discovery.newArtists(seeds, emptyList()) },
        ).awaitAll()
        s.discovery.newArtists(seeds, emptyList())
        assertTrue(results.all { it.isEmpty() })
        assertEquals(listOf("seed1"), s.radioCalls)
        assertEquals(1, s.matchCalls)
    }

    @Test
    fun `the artist set is built again after the reuse window`() = runTest {
        val s = Setup()
        val seeds = listOf(track("seed1", known))
        s.discovery.newArtists(seeds, emptyList())
        s.clock += 2 * 60_000L
        s.discovery.newArtists(seeds, emptyList())
        assertEquals(2, s.radioCalls.size)
    }

    @Test
    fun `new songs keep the new artists' tracks, matched by id or name, alternating seeds`() = runTest {
        val s = Setup()
        val gammaVideoChannel = artist("UCgammavideo000000000000", "Gamma")
        s.radios = mapOf(
            "seed1" to listOf(track("a", gamma), track("b", known), track("c", gamma)),
            "seed2" to listOf(track("d", gammaVideoChannel), track("e", delta)),
        )
        val seeds = listOf(track("seed1", known), track("seed2", known))
        assertEquals(listOf("a", "d", "c"), s.discovery.newSongs(seeds, listOf(gamma)).map { it.id })
    }

    @Test
    fun `albums take four from each of the top four artists, alternating, and skip a failed artist`() = runTest {
        val s = Setup()
        val artists = (1..5).map { artist("UCartist$it".padEnd(24, '0'), "Artist $it") }
        // artists[0] has no entry, so its fetch throws; artists[4] is past the top four.
        s.albums = mapOf(
            artists[1].id to (1..5).map { album("b$it") },
            artists[2].id to listOf(album("c1")),
            artists[3].id to (1..5).map { album("d$it") },
            artists[4].id to listOf(album("e1")),
        )
        val ids = s.discovery.albumsBy(artists).map { it.id }
        assertEquals(listOf("b1", "c1", "d1", "b2", "d2", "b3", "d3", "b4", "d4"), ids)
    }

    @Test
    fun `playlists come from the same artists, alternating`() = runTest {
        val s = Setup()
        s.playlists = mapOf(gamma.id to listOf(playlist("g1"), playlist("g2")), delta.id to listOf(playlist("d1")))
        assertEquals(listOf("g1", "d1", "g2"), s.discovery.playlistsBy(listOf(gamma, delta)).map { it.id })
    }

    @Test
    fun `the playlist search reads the names of the top two artists`() = runTest {
        val s = Setup()
        val third = artist("UCthird00000000000000000", "Third")
        s.searchResults = mapOf(
            "Gamma" to (1..12).map { playlist("g$it") },
            "Delta" to listOf(playlist("g1"), playlist("d1")),
        )
        val ids = s.discovery.playlistsSearchedFor(listOf(gamma, delta, third)).map { it.id }
        assertEquals(setOf("Gamma", "Delta"), s.searches.toSet())
        assertEquals(2, s.searches.size)
        assertEquals(listOf("g1", "g2", "d1", "g3", "g4", "g5", "g6", "g7", "g8", "g9", "g10"), ids)
    }

    @Test
    fun `round robin takes one from each list in turn`() {
        assertEquals(listOf(1, 3, 6, 2, 4, 5), roundRobin(listOf(listOf(1, 2), listOf(3, 4, 5), listOf(6))))
    }
}

private fun artist(id: String, name: String) = MetadataArtist.Basic(id = id, name = name, thumbnails = emptyList(), externalUri = null)

private fun track(id: String, by: MetadataArtist.Basic) = MetadataTrack(
    id = id,
    title = "Song $id",
    durationMs = 200_000,
    trackNumber = null,
    discNumber = null,
    artists = listOf(by),
    album = albumStub(id, emptyList(), emptyList()),
    thumbnails = emptyList(),
    explicit = null,
    popularity = null,
    isrcCode = null,
    externalUri = null,
)

private fun album(id: String) = PipedSearchItem(type = "playlist", url = "/playlist?list=$id", name = "Album $id").toAlbumBasic()!!

private fun playlist(id: String) = PipedSearchItem(type = "playlist", url = "/playlist?list=$id", name = "Playlist $id").toPlaylist()!!
