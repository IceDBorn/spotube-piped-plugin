package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI

class RealMetadataUserAPI(
    private val client: PipedClient,
    private val store: EntityStore,
) : MetadataUserAPI {

    override suspend fun getUser(id: String): MetadataUser? {
        if (!YOUTUBE_CHANNEL_ID.matches(id)) return null
        return runCatching {
            val channel = store.cachedChannel(id) ?: run {
                val fetched = client.channel(id)
                store.cacheChannel(id, fetched)
                fetched
            }
            channel.toUser()
        }.getOrNull()
    }
}
