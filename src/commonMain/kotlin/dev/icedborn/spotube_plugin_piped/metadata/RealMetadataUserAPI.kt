package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.YOUTUBE_CHANNEL_ID
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toUser
import dev.icedborn.spotube_plugin_piped.core.PipedAccount
import dev.icedborn.spotube_plugin_piped.core.accountStamp
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.user.MetadataUserAPI

internal class RealMetadataUserAPI(
    private val client: PipedClient,
    private val store: EntityStore,
    private val session: suspend () -> PipedAccount?,
) : MetadataUserAPI {

    override suspend fun getUser(id: String): MetadataUser? {
        // The host asks for "" to learn who owns its playlists, and edits only playlists whose owner matches.
        val account = session()
        if (account != null && (id.isEmpty() || id == accountStamp(account))) return accountUser(account)
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

/** The signed-in account as the owner of its local playlists. */
internal fun accountUser(account: PipedAccount) = MetadataUser(
    id = accountStamp(account),
    username = account.username,
    displayName = account.username,
    thumbnails = emptyList(),
    externalUri = null,
)
