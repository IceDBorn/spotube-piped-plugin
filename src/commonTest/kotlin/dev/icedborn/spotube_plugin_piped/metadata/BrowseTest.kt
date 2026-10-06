package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.albumStub
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.store.AccountHarness
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseItem
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseSection
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home: the two tabs, the carousel, and sections that fail on their own. */
class BrowseTest {

    private val globalCharts = listOf(
        "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i",
        "PL4fGSI1pDJn6t3TXLGiiJdD-sZbrG3tG0",
        "PL4fGSI1pDJn5kI81J1fYWK5eZRl1zJ5kM",
    )

    private class Harness(scope: CoroutineScope) {
        var clock = 0L
        val account = AccountHarness(scope)
        val piped = account.piped
        val store = account.store
        val history = PlayHistory(store)
        val tracks = RealMetadataTrackAPI(account.client, store, account.library, account.mirror)
        val artists = RealMetadataArtistAPI(account.client, store, account.library, account.mirror)
        val albums = RealMetadataAlbumAPI(account.client, store, account.library, account.mirror, account.lookup)
        val playlists = RealMetadataPlaylistAPI(
            account.client,
            store,
            account.library,
            account.mirror,
            history,
            LibraryPlaylistSetting(store),
        )
        val browse = RealMetadataBrowseAPI(
            library = account.library,
            mirror = account.mirror,
            history = history,
            region = RegionSetting(store, null),
            charts = Charts(account.client, store),
            tracks = tracks,
            artists = artists,
            albums = albums,
            playlists = playlists,
            search = RealMetadataSearchAPI(account.client, store),
            now = { clock },
        )
    }

    private fun track(index: Int) = MetadataTrack(
        id = vid(index),
        title = "Track $index",
        durationMs = 200_000,
        trackNumber = null,
        discNumber = null,
        artists = listOf(MetadataArtist.Basic(id = "channel:Alpha", name = "Alpha", thumbnails = emptyList(), externalUri = null)),
        album = albumStub(vid(index), emptyList(), emptyList()),
        thumbnails = emptyList(),
        explicit = null,
        popularity = null,
        isrcCode = null,
        externalUri = null,
    )

    private val videoChannel = "UCvideochannel0000000000"
    private val betaId = "UCbeta000000000000000000"

    /** A play credited to [videoChannel], a second channel of the artist "Beta". */
    private suspend fun Harness.playBetaVideo() {
        history.record(track(1).copy(artists = listOf(MetadataArtist.Basic(videoChannel, "Beta", emptyList(), null))))
        piped.channels[betaId] = "Beta - Topic"
        piped.channels[videoChannel] = "Beta"
    }

    private fun Harness.serveArtistSearch(name: String, id: String) = piped.search(
        "music_artists",
        listOf(
            buildJsonObject {
                put("type", "channel")
                put("url", "/channel/$id")
                put("name", name)
            },
        ),
    )

    private val gammaId = "UCgamma00000000000000000"

    private fun gammaRow(videoId: String) = buildJsonObject {
        put("type", "stream")
        put("url", "/watch?v=$videoId")
        put("title", "Song $videoId")
        put("uploaderName", "Gamma")
        put("uploaderUrl", "/channel/$gammaId")
        put("duration", 200)
    }

    /** Every radio lists three Gamma songs, and Gamma has one album, one own playlist and one fan playlist. */
    private fun Harness.serveGamma() {
        val mix = buildJsonObject {
            put("name", "Mix")
            put("nextpage", null as String?)
            put("relatedStreams", JsonArray((11..13).map { gammaRow(vid(it)) }))
        }
        piped.override("RDAMVM", body = mix.toString())
        piped.channels[gammaId] = "Gamma"
        serveArtistSearch("Gamma", gammaId)
        piped.search("filter=music_albums", listOf(piped.albumRow("OLAKgamma1", "Gamma Album", "Gamma")))
        piped.search("filter=playlists", listOf(piped.albumRow("PLgammaown", "Gamma Hits", "Gamma")))
        piped.search("filter=music_playlists", listOf(piped.albumRow("PLgammafans", "Gamma Radio", "Someone")))
    }

    private suspend fun Harness.allForYou(): List<MetadataBrowseSection> {
        val out = mutableListOf<MetadataBrowseSection>()
        var page = browse.list("for-you", null)
        out += page.items
        while (page.nextPagination != null) {
            page = browse.list("for-you", page.nextPagination)
            out += page.items
        }
        return out
    }

    private suspend fun Harness.yourArtists(): List<String> = browse.list("for-you", null).items
        .single { it.title == "Your artists" }.items.map { (it as MetadataBrowseItem.Artist).data.id }

    /** The fallback charts, the second of which fails to load. */
    private fun Harness.serveCharts() {
        piped.publicPlaylist(globalCharts[0], "Top 100 Songs Global", listOf(vid(1), vid(2)))
        piped.publicPlaylist(globalCharts[2], "Top 100 Music Videos Global", listOf(vid(3)))
    }

    @Test
    fun `with nothing personal the Charts tab comes first`() = runTest {
        val h = Harness(backgroundScope)
        assertEquals(listOf("charts", "for-you"), h.browse.genres().map { it.id })
    }

    @Test
    fun `with play history the For you tab comes first`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        assertEquals(listOf("for-you", "charts"), h.browse.genres().map { it.id })
    }

    @Test
    fun `the Charts tab skips a chart that fails to load`() = runTest {
        val h = Harness(backgroundScope)
        h.serveCharts()
        val page = h.browse.list("charts", null)
        assertEquals(listOf("Top 100 Songs Global", "Top 100 Music Videos Global"), page.items.map { it.title })
        assertNull(page.nextPagination)
    }

    @Test
    fun `an unknown tab is empty`() = runTest {
        val h = Harness(backgroundScope)
        assertTrue(h.browse.list("nope", null).items.isEmpty())
    }

    @Test
    fun `with nothing personal the carousel shows charts`() = runTest {
        val h = Harness(backgroundScope)
        h.serveCharts()
        val items = h.browse.featured()
        assertEquals(listOf(globalCharts[0], globalCharts[2]), items.map { (it as MetadataBrowseItem.Playlist).data.id })
    }

    @Test
    fun `the carousel lists the resolved albums of recent plays`() = runTest {
        val h = Harness(backgroundScope)
        val resolved = albumStub(vid(2), emptyList(), emptyList()).copy(id = "OLAK5uy_resolved", title = "Resolved")
        h.history.record(track(2).copy(album = resolved))
        val album = h.browse.featured().filterIsInstance<MetadataBrowseItem.Album>().single().data
        assertEquals("OLAK5uy_resolved", album.id)
    }

    @Test
    fun `the carousel skips untitled album stubs`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        assertTrue(h.browse.featured().filterIsInstance<MetadataBrowseItem.Album>().isEmpty())
    }

    @Test
    fun `the For you tab opens with recent plays`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        val page = h.browse.list("for-you", null)
        assertEquals("Recently played", page.items.first().title)
    }

    @Test
    fun `a failed radio is fetched again by the next section`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        // Neither the mix nor the catalog top-up answers.
        h.piped.override("/search", status = 500, body = "")
        h.browse.list("for-you", null)
        h.browse.list("for-you", PaginationStrategy.Offset(3, 3))
        // The radio section and the fans section each ask, since an empty radio is not kept.
        assertEquals(2, h.piped.count("RDAMVM"))
    }

    @Test
    fun `a radio is reused only by sections built soon after it`() = runTest {
        val soon = Harness(backgroundScope)
        soon.history.record(track(1))
        soon.browse.list("for-you", null)
        val fetched = soon.piped.count("RDAMVM")
        soon.browse.list("for-you", PaginationStrategy.Offset(3, 3))
        assertEquals(fetched, soon.piped.count("RDAMVM"))
        val later = Harness(backgroundScope)
        later.history.record(track(1))
        later.browse.list("for-you", null)
        later.clock += 3 * 60_000L
        later.browse.list("for-you", PaginationStrategy.Offset(3, 3))
        assertTrue(later.piped.count("RDAMVM") > fetched)
    }

    @Test
    fun `Your artists lists the artist behind a video channel`() = runTest {
        val h = Harness(backgroundScope)
        h.playBetaVideo()
        h.serveArtistSearch("Beta", betaId)
        assertEquals(listOf(betaId), h.yourArtists())
    }

    @Test
    fun `More from reads the matched artist and not the video channel`() = runTest {
        val h = Harness(backgroundScope)
        h.playBetaVideo()
        h.serveArtistSearch("Beta", betaId)
        h.browse.list("for-you", null)
        h.browse.list("for-you", PaginationStrategy.Offset(3, 3))
        assertTrue(h.piped.count("/channel/$betaId") > 0)
        assertEquals(0, h.piped.count("/channel/$videoChannel"))
    }

    @Test
    fun `a played uploader with no artist of that name is left out`() = runTest {
        val h = Harness(backgroundScope)
        h.playBetaVideo()
        h.serveArtistSearch("Someone Else", betaId)
        assertTrue(h.browse.list("for-you", null).items.none { it.title == "Your artists" })
    }

    @Test
    fun `a failed artist search keeps the played artist`() = runTest {
        val h = Harness(backgroundScope)
        h.playBetaVideo()
        h.piped.override("music_artists", status = 500, body = "")
        assertEquals(listOf(videoChannel), h.yourArtists())
    }

    @Test
    fun `an artist name is searched once across Home pages`() = runTest {
        val h = Harness(backgroundScope)
        h.playBetaVideo()
        h.serveArtistSearch("Beta", betaId)
        h.browse.list("for-you", null)
        h.browse.list("for-you", PaginationStrategy.Offset(3, 3))
        assertEquals(1, h.piped.count("music_artists"))
    }

    @Test
    fun `For you lists songs, albums and playlists of the new artists`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        h.serveGamma()
        val sections = h.allForYou()
        fun items(title: String) = sections.single { it.title == title }.items
        assertEquals((11..13).map { vid(it) }, items("New songs for you").map { (it as MetadataBrowseItem.Track).data.id })
        assertEquals(listOf("OLAKgamma1"), items("Albums you might like").map { (it as MetadataBrowseItem.Album).data.id })
        assertEquals(listOf("PLgammaown"), items("Playlists you might like").map { (it as MetadataBrowseItem.Playlist).data.id })
        val fans = sections.single { it.description == "Playlists for fans of" }
        assertEquals("Gamma", fans.title)
        assertEquals(listOf("PLgammafans"), fans.items.map { (it as MetadataBrowseItem.Playlist).data.id })
    }

    @Test
    fun `the discovery rows cost one channel fetch and one search of each kind`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        h.serveGamma()
        h.allForYou()
        assertEquals(1, h.piped.count("RDAMVM"))
        assertEquals(1, h.piped.count("/channel/$gammaId"))
        assertEquals(1, h.piped.count("filter=music_albums"))
        assertEquals(1, h.piped.count("filter=playlists"))
        assertEquals(1, h.piped.count("filter=music_playlists"))
    }

    @Test
    fun `a failed playlist search drops only its own row`() = runTest {
        val h = Harness(backgroundScope)
        h.history.record(track(1))
        h.serveGamma()
        h.piped.override("filter=music_playlists", status = 500, body = "")
        val sections = h.allForYou()
        assertTrue(sections.none { it.description == "Playlists for fans of" })
        assertTrue(sections.any { it.title == "Albums you might like" })
    }
}
