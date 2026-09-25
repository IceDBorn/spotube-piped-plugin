package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI

class RealMetadataUserAPI(
    private val client: PipedClient,
    private val store: EntityStore,
) : MetadataUserAPI {

    override suspend fun getUser(id: String): MetadataUser? {
        // Synthetic ids (channel:NAME) come from uploader URLs without a parseable
        // channel id; the name is embedded, so no instance fetch is possible.
        if (id.startsWith("channel:")) {
            val name = id.removePrefix("channel:").let(::cleanArtistName)
            return MetadataUser(
                id = id,
                username = name,
                displayName = name,
                thumbnails = emptyList(),
                externalUri = null,
            )
        }
        if (!YOUTUBE_CHANNEL_ID.matches(id)) return null
        return orNull("channel $id") {
            // Same cached-value trust as channelNameFor / fetchChannel: a cached channel with a blank name is
            // a MISS (CHANNEL_KEY is never invalidated — trusting it would render the user empty forever).
            val channel = store.cachedChannel(id)?.takeIf { it.name.isNotBlank() } ?: client.channel(id)
                ?.also { store.cacheChannel(id, it) }
            channel?.toUser()
        }
    }
}