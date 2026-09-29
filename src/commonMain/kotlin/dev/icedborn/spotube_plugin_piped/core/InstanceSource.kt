package dev.icedborn.spotube_plugin_piped.core

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val INSTANCE_KEY = "piped.instance"
internal const val PLAYBACK_INSTANCE_KEY = "piped.playback.instance"

private const val NO_INSTANCE_MSG = "No Piped instance is set. Open Settings, choose the Piped plugin and save an instance URL."

/** The user picks the Piped instance; there is no built-in default. */
internal class InstanceSource(private val store: EntityStore, private val sessionFallback: String?) {
    private var apiLoaded = false
    private var apiSaved: String? = null
    private var playbackLoaded = false
    private var playbackSaved: String? = null

    private suspend fun load(key: String): String? {
        val raw = store.get(key)?.jsonPrimitive?.contentOrNull ?: return null
        return normalizeInstance(raw).takeIf { it.isNotEmpty() }
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
        apiSaved = normalizeInstance(url)
        store.put(INSTANCE_KEY, JsonPrimitive(apiSaved))
    }

    /** Blank clears the playback instance, falling back to the main one. */
    suspend fun setPlayback(url: String) {
        playbackSaved = normalizeInstance(url).takeIf { it.isNotEmpty() }
        if (playbackSaved == null) {
            store.remove(PLAYBACK_INSTANCE_KEY)
        } else {
            store.put(PLAYBACK_INSTANCE_KEY, JsonPrimitive(playbackSaved))
        }
    }
}

/** [url] trimmed, without a trailing slash, with https:// added when no scheme is given; null for another scheme. */
internal fun normalizeInstanceUrl(url: String): String? {
    val trimmed = normalizeInstance(url)
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return withScheme.takeIf { it.substringAfter("://").isNotEmpty() }
}
