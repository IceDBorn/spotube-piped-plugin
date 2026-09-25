package dev.icedborn.spotube_plugin_piped_metadata.fakes

import dev.krtirtho.plugin_interfaces.host_apis.PersistedStorageAPI

/** In-memory stand-in for the host persistence API. */
open class FakeStorage : PersistedStorageAPI {
    val values = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun getKeys(): List<String> = values.keys.toList()
}
