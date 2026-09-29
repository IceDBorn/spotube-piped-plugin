package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.AccountHttp
import dev.icedborn.spotube_plugin_piped.client.PipedChannelInfo
import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.PipedSearchPage
import dev.icedborn.spotube_plugin_piped.client.PipedStreamsInfo
import dev.icedborn.spotube_plugin_piped.client.channelIdOf
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.decodeKeyed
import dev.icedborn.spotube_plugin_piped.client.decodePlaylistPage
import dev.icedborn.spotube_plugin_piped.client.videoIdOf
import dev.icedborn.spotube_plugin_piped.core.InstanceSource
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.percentEncoded

/** Picks the mirror row video that stands for a saved album or artist, and maps row videos back to artists. */
internal class Representatives(private val http: AccountHttp, private val store: EntityStore, private val instanceSource: InstanceSource) {

    /** One playable video for a saved entity, preferring one no other entity uses. */
    suspend fun representativeVideo(kind: SavedKind, id: String, used: Set<String>): String? = when (kind) {
        // A track row is the track id, so it never collides with another entity.
        SavedKind.TRACK -> id

        SavedKind.ALBUM -> firstVideoOfPlaylist(id, used)

        // Music search tracks can be added reliably; channel uploads often cannot, so they are the fallback.
        SavedKind.ARTIST -> {
            val name = channelNameFor(id)
            val viaSearch = name.takeIf { it.isNotBlank() }?.let { firstVideoOfSearch(it, used) }
            viaSearch ?: firstVideoOfArtist(id, used)
        }
    }

    /** The uploader's resolved id from cached stream info only, with no request. */
    suspend fun cachedArtistIdOf(videoId: String): String? = store.cachedStreams(videoId)?.let(::artistIdOf)

    /** The uploader channel of a video, or a synthetic channel id. */
    suspend fun artistChannelOf(account: PipedAccount, videoId: String): String? {
        val info = store.cachedStreams(videoId) ?: run {
            val body = http.get(account.instance, "/streams/${videoId.percentEncoded()}")
            val fetched = decodeKeyed<PipedStreamsInfo>(body, "relatedStreams") ?: return null
            store.cacheStreams(videoId, fetched)
            fetched
        }
        return artistIdOf(info)
    }

    private fun artistIdOf(info: PipedStreamsInfo): String? {
        val channelId = channelIdOf(info.uploaderUrl)
        if (channelId.isNotEmpty()) return channelId
        return info.uploader.takeIf { it.isNotBlank() }?.let(::cleanArtistName)?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
    }

    private suspend fun firstVideoOfPlaylist(playlistId: String, used: Set<String>): String? {
        val page = store.cachedAlbumPlaylist(playlistId) ?: run {
            val body = http.get(instanceSource.requireApi(), "/playlists/${playlistId.percentEncoded()}") ?: return null
            val fetched = runCatching { decodePlaylistPage(body) }.getOrNull() ?: return null
            store.cacheAlbumPlaylist(playlistId, fetched)
            fetched
        }
        // A save must find a row, so every page 1 row is probed.
        return firstFetchableOf(page.relatedStreams.videoIds(), used, probeLimit = page.relatedStreams.size)
    }

    private fun decodeChannel(body: String?): PipedChannelInfo? = decodeKeyed(body, "relatedStreams")

    private suspend fun firstVideoOfArtist(channelId: String, used: Set<String>): String? {
        // A cached channel with a blank name is treated as a miss; the channel cache is never invalidated.
        val info = store.cachedChannel(channelId)?.takeIf { it.name.isNotBlank() } ?: run {
            val fetched = decodeChannel(http.get(instanceSource.requireApi(), "/channel/${channelId.percentEncoded()}")) ?: return null
            store.cacheChannel(channelId, fetched)
            fetched
        }
        return firstFetchableOf(info.relatedStreams.videoIds(), used, probeLimit = info.relatedStreams.size)
    }

    /** The first candidate the instance can resolve, trying at most [probeLimit]. A candidate no entity uses wins;
     * when all are used, the first fetchable one is shared and a later unsave moves the sibling off it. */
    private suspend fun firstFetchableOf(candidates: List<String>, used: Set<String>, probeLimit: Int = 6): String? {
        val head = candidates.take(probeLimit)
        return head.firstOrNull { it !in used && fetchable(it) } ?: head.firstOrNull { it in used && fetchable(it) }
    }

    private suspend fun fetchable(videoId: String): Boolean {
        val info = store.cachedStreams(videoId) ?: run {
            val body = http.get(instanceSource.requireApi(), "/streams/${videoId.percentEncoded()}")
            val fetched = decodeKeyed<PipedStreamsInfo>(body, "relatedStreams") ?: return false
            store.cacheStreams(videoId, fetched)
            fetched
        }
        return info.title.isNotBlank()
    }

    private suspend fun channelNameFor(id: String): String {
        if (id.startsWith("channel:")) return id.removePrefix("channel:")
        store.cachedChannel(id)?.let { if (it.name.isNotBlank()) return it.name }
        val fetched = decodeChannel(http.get(instanceSource.requireApi(), "/channel/${id.percentEncoded()}"))
        if (fetched != null) store.cacheChannel(id, fetched)
        return fetched?.name?.takeIf { it.isNotBlank() } ?: id
    }

    private suspend fun firstVideoOfSearch(query: String, used: Set<String>): String? {
        val body = http.get(instanceSource.requireApi(), "/search?filter=music_songs&q=${query.percentEncoded()}")
        // A blank or key-missing body is a throttle, not zero hits.
        val page = decodeKeyed<PipedSearchPage>(body, "items") ?: return null
        return firstFetchableOf(page.items.videoIds(), used)
    }

    private fun List<PipedSearchItem>.videoIds(): List<String> = mapNotNull { videoIdOf(it.url).takeIf { v -> v.isNotEmpty() } }
}
