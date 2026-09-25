package dev.icedborn.spotube_plugin_piped_metadata

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.swiftzer.semver.SemVer

private const val CHANNEL_KEY = "piped.update.channel"

/** Which GitHub release the update check looks at. */
enum class UpdateChannel { AUTO, STABLE, NIGHTLY }

/** The stored update channel; the GitHub release [UpdateChecker] should offer from. */
class UpdateChannelSetting(private val store: EntityStore) {

    suspend fun stored(): UpdateChannel =
        UpdateChannel.entries.firstOrNull {
            it.name == (store.get(CHANNEL_KEY) as? JsonPrimitive)?.contentOrNull
        } ?: UpdateChannel.AUTO

    suspend fun set(channel: UpdateChannel) {
        if (channel == UpdateChannel.AUTO) store.remove(CHANNEL_KEY)
        else store.put(CHANNEL_KEY, JsonPrimitive(channel.name))
    }

    /** [stored], with Auto resolved by the installed build: a nightly install stays on nightlies. */
    suspend fun resolve(currentVersion: SemVer): UpdateChannel = when (val channel = stored()) {
        UpdateChannel.AUTO ->
            if (currentVersion.preRelease != null) UpdateChannel.NIGHTLY else UpdateChannel.STABLE
        else -> channel
    }
}
