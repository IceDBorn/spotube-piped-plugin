@file:Suppress("unused")

package dev.icedborn.spotube_plugin_piped

import app.cash.zipline.Zipline
import dev.icedborn.spotube_plugin_piped.audio.AudioPipedClient
import dev.icedborn.spotube_plugin_piped.audio.RealPipedAudioAPI
import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.core.AccountSession
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.RealCoreAPI
import dev.icedborn.spotube_plugin_piped.metadata.AlbumLookup
import dev.icedborn.spotube_plugin_piped.metadata.Charts
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataAlbumAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataArtistAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataBrowseAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataPlaylistAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataSearchAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataTrackAPI
import dev.icedborn.spotube_plugin_piped.metadata.RealMetadataUserAPI
import dev.icedborn.spotube_plugin_piped.scrobble.RealScrobbleAPI
import dev.icedborn.spotube_plugin_piped.scrobble.ScrobbleHistory
import dev.icedborn.spotube_plugin_piped.settings.LibraryPlaylistSetting
import dev.icedborn.spotube_plugin_piped.settings.RegionSetting
import dev.icedborn.spotube_plugin_piped.settings.UpdateChannelSetting
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.LocalLibrary
import dev.icedborn.spotube_plugin_piped.store.PipedSavedLibrary
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.StorageMigration
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
import dev.krtirtho.plugin_interfaces.plugin_apis.scrobble.ScrobbleAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.scrobble.ScrobbleAPI_SERVICE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalJsExport

private val zipline by lazy { Zipline.get() }

/** Runs background work, including the account refresh at start, since login() is not called on relaunch. */
private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    runPluginInitialized { start() }
}

private suspend fun start() {
    val httpClient = zipline.take<HttpClientAPI>(HttpClientAPI_SERVICE_NAME)
    val storage = zipline.take<PersistedStorageAPI>(PersistedStorageAPI_SERVICE_NAME)
    val store = EntityStore(storage)
    val library = LocalLibrary(store)
    val session = AccountSession(store)
    val account = session.load()
    val instanceSource = InstanceSource(store, account?.instance)
    val migration = StorageMigration(AccountHttp(httpClient), store, library, session, instanceSource)
    migration.run()
    val client = PipedClient(httpClient) { instanceSource.requireApi() }
    val albumLookup = AlbumLookup(client, store)
    val history = PlayHistory(store)
    val mirror = PipedSavedLibrary(httpClient, store, library, albumLookup, instanceSource, initScope, history) { session.load() }
    // Bound by every host version; older hosts without it fall back to the Global charts.
    val systemInfo = runCatching { zipline.take<SystemInformationAPI>(SystemInformationAPI_SERVICE_NAME) }.getOrNull()
    val region = RegionSetting(store, systemInfo)
    val libraryPlaylist = LibraryPlaylistSetting(store)
    val core = RealCoreAPI(
        httpClient = httpClient,
        storage = storage,
        webView = zipline.take<WebViewAPI>(WebViewAPI_SERVICE_NAME),
        session = session,
        instanceSource = instanceSource,
        region = region,
        channel = UpdateChannelSetting(store),
        libraryPlaylist = libraryPlaylist,
        onLogin = {
            migration.adoptBareBindings()
            mirror.refreshCache()
        },
        coroutineScope = initScope,
    )
    zipline.bind<CoreAPI>(CoreAPI_SERVICE_NAME, core)
    val trackApi = RealMetadataTrackAPI(client, store, library, mirror, history)
    val scrobbles = ScrobbleHistory(history, store, fetchTrack = { id -> trackApi.getTrack(id) }, onPlay = { mirror.played(it) })
    val playback = PipedClient(httpClient) { instanceSource.playback() ?: instanceSource.requireApi() }
    zipline.bind<AudioAPI>(
        AudioAPI_SERVICE_NAME,
        RealPipedAudioAPI(AudioPipedClient(playback), scope = initScope) { scrobbles.onAudioRequested(it) },
    )
    zipline.bind<ScrobbleAPI>(ScrobbleAPI_SERVICE_NAME, RealScrobbleAPI(scrobbles))
    bindMetadata(client, store, library, mirror, albumLookup, history, region, libraryPlaylist, trackApi)
    if (account != null) initScope.launch { mirror.refreshCache() }
}

private fun bindMetadata(
    client: PipedClient,
    store: EntityStore,
    library: LocalLibrary,
    mirror: PipedSavedLibrary,
    albumLookup: AlbumLookup,
    history: PlayHistory,
    region: RegionSetting,
    libraryPlaylist: LibraryPlaylistSetting,
    trackApi: RealMetadataTrackAPI,
) {
    val albumApi = RealMetadataAlbumAPI(client, store, library, mirror, albumLookup, initScope)
    val artistApi = RealMetadataArtistAPI(client, store, library, mirror) { seeds -> trackApi.radio(seeds, 50) }
    val playlistApi = RealMetadataPlaylistAPI(client, store, library, mirror, history, libraryPlaylist, initScope)
    val searchApi = RealMetadataSearchAPI(client, store)
    val browseApi = RealMetadataBrowseAPI(
        library = library,
        mirror = mirror,
        history = history,
        region = region,
        charts = Charts(client, store),
        tracks = trackApi,
        artists = artistApi,
        albums = albumApi,
        playlists = playlistApi,
        search = searchApi,
    )
    zipline.bind<MetadataSearchAPI>(MetadataSearchAPI_SERVICE_NAME, searchApi)
    zipline.bind<MetadataTrackAPI>(MetadataTrackAPI_SERVICE_NAME, trackApi)
    zipline.bind<MetadataAlbumAPI>(MetadataAlbumAPI_SERVICE_NAME, albumApi)
    zipline.bind<MetadataArtistAPI>(MetadataArtistAPI_SERVICE_NAME, artistApi)
    zipline.bind<MetadataPlaylistAPI>(MetadataPlaylistAPI_SERVICE_NAME, playlistApi)
    zipline.bind<MetadataBrowseAPI>(MetadataBrowseAPI_SERVICE_NAME, browseApi)
    zipline.bind<MetadataUserAPI>(MetadataUserAPI_SERVICE_NAME, RealMetadataUserAPI(client, store))
}
