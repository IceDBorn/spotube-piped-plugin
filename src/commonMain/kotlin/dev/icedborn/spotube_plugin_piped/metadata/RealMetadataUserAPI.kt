package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.YOUTUBE_CHANNEL_ID
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toUser
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI

internal class RealMetadataUserAPI(private val client: PipedClient, private val store: EntityStore) : MetadataUserAPI {

    override suspend fun getUser(id: String): MetadataUser? {
        // Synthetic ids ("channel:<name>") come from uploader URLs without a parseable
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
            // A cached channel with a blank name is a miss; the channel cache is never invalidated.
            val channel = store.cachedChannel(id)?.takeIf { it.name.isNotBlank() } ?: client.channel(id)
                ?.also { store.cacheChannel(id, it) }
            channel?.toUser()
        }
    }
}
