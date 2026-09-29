package dev.icedborn.spotube_plugin_piped.fakes

import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI

/** In-memory stand-in for the host persistence API. */
open class FakeStorage : PersistedStorageAPI {
    val values = mutableMapOf<String, String>()

    // Makes key listing fail, the way a host storage error would.
    var throwOnKeys = false

    // Makes writes of keys with this prefix fail.
    var failPutsFor: String? = null

    override suspend fun putString(key: String, value: String) {
        failPutsFor?.let { if (key.startsWith(it)) throw IllegalStateException("write refused") }
        values[key] = value
    }

    override suspend fun getString(key: String): String? = values[key]

    // Makes removes of keys with this prefix fail.
    var failRemovesFor: String? = null

    override suspend fun remove(key: String) {
        failRemovesFor?.let { if (key.startsWith(it)) throw IllegalStateException("remove refused") }
        values.remove(key)
    }

    override suspend fun getKeys(): List<String> {
        if (throwOnKeys) throw IllegalStateException("storage unavailable")
        return values.keys.toList()
    }
}
