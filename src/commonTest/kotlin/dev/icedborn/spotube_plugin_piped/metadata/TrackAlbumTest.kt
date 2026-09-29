package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.ALBUM_LOOKUP_PREFIX
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.fakes.FAKE_INSTANCE
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.fakes.noSessionScope
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The album of a track opened from its menu, which waits at most 4 seconds for the lookup. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackAlbumTest {

    private val piped = FakePiped()
    private val store = EntityStore(FakeStorage())
    private val lookup = AlbumLookup(PipedClient(piped) { FAKE_INSTANCE }, store)

    private fun TestScope.api(): RealMetadataAlbumAPI {
        val library = LocalLibrary(store)
        val mirror = PipedSavedLibrary(piped, store, library, lookup, InstanceSource(store, null), noSessionScope) { null }
        return RealMetadataAlbumAPI(PipedClient(piped) { FAKE_INSTANCE }, store, library, mirror, lookup, backgroundScope)
    }

    private val track = assertNotNull(
        PipedSearchItem(type = "stream", url = "/watch?v=${vid(1)}", title = "Title", uploaderName = "Alpha Artist").toTrack(),
    ).copy(album = null)

    private fun album() {
        piped.publicPlaylist("OLAK5uy_A1", "Album - One", (0 until 5).map(::vid))
        piped.search("filter=music_albums", listOf(piped.albumRow("OLAK5uy_A1", "Album - One", "Alpha Artist")))
    }

    @Test
    fun `a quick lookup returns the album`() = runTest {
        album()
        assertEquals("OLAK5uy_A1", api().getTrackAlbum(track).id)
    }

    @Test
    fun `a slow lookup returns a stub and caches the album later`() = runTest {
        album()
        piped.latencyMs = 3_000
        val api = api()
        assertEquals("$ALBUM_LOOKUP_PREFIX${vid(1)}", api.getTrackAlbum(track).id)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("OLAK5uy_A1", store.cachedTrackAlbum(vid(1)))
    }
}
