package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseGenre
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseItem
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseSection
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy

/**
 * Piped exposes no genre shelves; the featured row is filled with the instance's
 * trending videos so the browse page has real content.
 */
class RealMetadataBrowseAPI(
    private val client: PipedClient,
    private val store: EntityStore,
) : MetadataBrowseAPI {

    override suspend fun featured(): List<MetadataBrowseItem> {
        return runCatching {
            client.trending("US")
                .filter { it.type == "stream" || it.type == "video" }
                .mapNotNull { item ->
                    item.toTrack()?.also { track -> store.rememberTrack(track) }
                }
                .take(20)
                .map { MetadataBrowseItem.Track(it) }
        }.getOrDefault(emptyList())
    }

    override suspend fun genres(): List<MetadataBrowseGenre> = emptyList()

    override suspend fun list(
        genreId: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataBrowseSection> = PaginationResult(emptyList(), 0, null)

    override suspend fun sublist(
        genreId: String,
        sectionId: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataBrowseItem> = PaginationResult(emptyList(), 0, null)
}
