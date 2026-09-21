package dev.icedborn.spotube_plugin_piped_metadata

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val INSTANCE_KEY = "piped.instance"
internal const val PLAYBACK_INSTANCE_KEY = "piped.playback.instance"

private const val NO_INSTANCE_MSG = "No Piped instance is set. Open Settings, choose the Piped plugin and save an instance URL."

/** The user picks the Piped instance; there is no built-in default. */
class InstanceSource(
    private val store: EntityStore,
    private val sessionFallback: String?,
) {
    private var apiLoaded = false
    private var apiSaved: String? = null
    private var playbackLoaded = false
    private var playbackSaved: String? = null

    private suspend fun load(key: String): String? {
        val raw = store.get(key)?.jsonPrimitive?.contentOrNull ?: return null
        return raw.trim().trimEnd('/').takeIf { it.isNotEmpty() }
    }

    /** The main instance: auth, account data, search and metadata. */
    suspend fun api(): String? {
        if (!apiLoaded) {
            apiLoaded = true
            apiSaved = load(INSTANCE_KEY)
        }
        return apiSaved ?: sessionFallback?.trimEnd('/')
    }

    /** Optional instance used only to resolve audio. Blank means "use the main instance". */
    suspend fun playback(): String? {
        if (!playbackLoaded) {
            playbackLoaded = true
            playbackSaved = load(PLAYBACK_INSTANCE_KEY)
        }
        return playbackSaved
    }

    suspend fun requireApi(): String = api() ?: throw IllegalStateException(NO_INSTANCE_MSG)

    suspend fun setApi(url: String) {
        apiSaved = clean(url)
        store.put(INSTANCE_KEY, JsonPrimitive(apiSaved))
    }

    /** Blank clears the playback instance, falling back to the main one. */
    suspend fun setPlayback(url: String) {
        playbackSaved = clean(url).takeIf { it.isNotEmpty() }
        if (playbackSaved == null) store.remove(PLAYBACK_INSTANCE_KEY)
        else store.put(PLAYBACK_INSTANCE_KEY, JsonPrimitive(playbackSaved))
    }

    private fun clean(url: String) = url.trim().trimEnd('/')
}
