@file:Suppress("unused")

package dev.icedborn.spotube_plugin_piped

import app.cash.zipline.Zipline
import dev.icedborn.spotube_plugin_piped_metadata.AccountSession
import dev.icedborn.spotube_plugin_piped_metadata.AlbumLookup
import dev.icedborn.spotube_plugin_piped_metadata.Charts
import dev.icedborn.spotube_plugin_piped_metadata.EntityStore
import dev.icedborn.spotube_plugin_piped_metadata.InstanceSource
import dev.icedborn.spotube_plugin_piped_metadata.LocalLibrary
import dev.icedborn.spotube_plugin_piped_metadata.PipedClient
import dev.icedborn.spotube_plugin_piped_metadata.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped_metadata.PlayHistory
import dev.icedborn.spotube_plugin_piped_metadata.RealCoreAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataAlbumAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataArtistAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataBrowseAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataPlaylistAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataSearchAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataTrackAPI
import dev.icedborn.spotube_plugin_piped_metadata.RealMetadataUserAPI
import dev.icedborn.spotube_plugin_piped_metadata.RegionSetting
import dev.krtirtho.plugin_interfaces.core.runPluginInitialized
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI
import dev.krtirtho.plugin_interfaces.host_apis.HttpClientAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI
import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.host_apis.SystemInformationAPI
import dev.krtirtho.plugin_interfaces.host_apis.SystemInformationAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.host_apis.WebViewAPI
import dev.krtirtho.plugin_interfaces.host_apis.WebViewAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.audio.AudioAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.core.CoreAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.core.CoreAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbumAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtistAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylistAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylistAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.search.MetadataSearchAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrackAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrackAPI_SERVICE_NAME
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI_SERVICE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalJsExport

private val zipline by lazy { Zipline.get() }

/**
 * Cache refresh on app start with a saved session. Login() is not called again
 * on ordinary relaunches, so the account cache is refreshed here too.
 */
private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    runPluginInitialized {
        val httpClient = zipline.take<HttpClientAPI>(HttpClientAPI_SERVICE_NAME)
        val storage = zipline.take<PersistedStorageAPI>(PersistedStorageAPI_SERVICE_NAME)
        val webView = zipline.take<WebViewAPI>(WebViewAPI_SERVICE_NAME)

        val store = EntityStore(storage)
        val library = LocalLibrary(store)
        val session = AccountSession(store)
        val account = session.load()
        val instanceSource = InstanceSource(store, account?.instance)
        val client = PipedClient(httpClient) {
            instanceSource.requireApi()
        }
        val albumLookup = AlbumLookup(client, store)
        val mirror = PipedSavedLibrary(httpClient, store, library, albumLookup, instanceSource) { session.load() }
        // Bound by every host version; older hosts without it fall back to the Global charts.
        val systemInfo = runCatching { zipline.take<SystemInformationAPI>(SystemInformationAPI_SERVICE_NAME) }.getOrNull()
        val region = RegionSetting(store, systemInfo)
        val history = PlayHistory(store)
        val core = RealCoreAPI(
            httpClient = httpClient,
            storage = storage,
            webView = webView,
            session = session,
            instanceSource = instanceSource,
            region = region,
            onLogin = { mirror.refreshCache() },
        )
        val trackApi = RealMetadataTrackAPI(client, store, library, mirror)
        val albumApi = RealMetadataAlbumAPI(client, store, library, mirror, albumLookup)
        val artistApi = RealMetadataArtistAPI(client, store, library, mirror)
        val playlistApi = RealMetadataPlaylistAPI(client, store, library, mirror)

        zipline.bind<CoreAPI>(CoreAPI_SERVICE_NAME, core)
        zipline.bind<AudioAPI>(
            AudioAPI_SERVICE_NAME,
            RealPipedAudioAPI(
                AudioPipedClient(
                    PipedClient(httpClient) {
                        instanceSource.playback() ?: instanceSource.requireApi()
                    }
                )
            ) {
                history.record(it)
            },
        )
        zipline.bind<MetadataSearchAPI>(MetadataSearchAPI_SERVICE_NAME, RealMetadataSearchAPI(client, store))
        zipline.bind<MetadataTrackAPI>(MetadataTrackAPI_SERVICE_NAME, trackApi)
        zipline.bind<MetadataAlbumAPI>(MetadataAlbumAPI_SERVICE_NAME, albumApi)
        zipline.bind<MetadataArtistAPI>(MetadataArtistAPI_SERVICE_NAME, artistApi)
        zipline.bind<MetadataPlaylistAPI>(MetadataPlaylistAPI_SERVICE_NAME, playlistApi)
        zipline.bind<MetadataBrowseAPI>(
            MetadataBrowseAPI_SERVICE_NAME,
            RealMetadataBrowseAPI(
                library = library,
                mirror = mirror,
                history = history,
                region = region,
                charts = Charts(client, store),
                tracks = trackApi,
                artists = artistApi,
                albums = albumApi,
                playlists = playlistApi,
            ),
        )
        zipline.bind<MetadataUserAPI>(MetadataUserAPI_SERVICE_NAME, RealMetadataUserAPI(client, store))
        if (account != null) {
            initScope.launch { mirror.refreshCache() }
        }
    }
}
