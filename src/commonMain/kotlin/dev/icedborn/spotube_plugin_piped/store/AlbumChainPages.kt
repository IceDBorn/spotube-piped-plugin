package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.PipedPlaylistPage
import dev.icedborn.spotube_plugin_piped.client.json
import kotlinx.serialization.json.encodeToJsonElement

internal const val ALBUM_CHAIN_PREFIX = "album.chain:"

/** A continuation page of an album's chain, keyed by its token. A chain deeper than one refresh walks resumes
 * from the pages an earlier refresh cached, so it reaches its end instead of restarting. */
internal suspend fun EntityStore.cachedAlbumChainPage(id: String, token: String): PipedPlaylistPage? =
    getDecoded(ALBUM_CHAIN_PREFIX + id + ":" + token, PipedPlaylistPage.serializer())

internal suspend fun EntityStore.cacheAlbumChainPage(id: String, token: String, page: PipedPlaylistPage) {
    put(ALBUM_CHAIN_PREFIX + id + ":" + token, json.encodeToJsonElement(page))
}
