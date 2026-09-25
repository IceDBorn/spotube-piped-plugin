package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement

/** "YouTube Music Global Charts": publishes every chart as a playlist. Piped has no charts endpoint. */
private const val CHARTS_CHANNEL_ID = "UCrKZcyOJVWnJ60zM1XWllNw"
private const val CHART_INDEX_KEY = "charts.index"
private const val CHART_AT_KEY = "chart.at:"
private const val CHART_INDEX_TTL_MS = 7 * 24 * 60 * 60_000L
// Charts update daily; a refetch that fails serves the older copy.
private const val CHART_TTL_MS = 6 * 60 * 60_000L

/** Used while the index is unavailable (first run offline, channel fetch failing). */
private val GLOBAL_FALLBACK = listOf(
    "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i" to "Top 100 Songs Global",
    "PL4fGSI1pDJn6t3TXLGiiJdD-sZbrG3tG0" to "Daily top music videos – Global",
    "PL4fGSI1pDJn5kI81J1fYWK5eZRl1zJ5kM" to "Top 100 Music Videos Global",
)

@Serializable
private data class ChartIndex(val fetchedAt: Long, val charts: List<List<String>>)

data class Chart(val playlistId: String, val title: String)

class Charts(private val client: PipedClient, private val store: EntityStore) {

    private suspend fun index(): List<Pair<String, String>> {
        val cached = store.getDecoded(CHART_INDEX_KEY, ChartIndex.serializer())
        if (cached != null && epochMillis() - cached.fetchedAt < CHART_INDEX_TTL_MS) return cached.pairs()
        val fresh = client.channelPlaylists(CHARTS_CHANNEL_ID)?.takeIf { it.isNotEmpty() }
        if (fresh == null) return cached?.pairs() ?: GLOBAL_FALLBACK
        store.put(CHART_INDEX_KEY, json.encodeToJsonElement(ChartIndex(epochMillis(), fresh.map { listOf(it.first, it.second) })))
        return fresh
    }

    private fun ChartIndex.pairs() = charts.mapNotNull { if (it.size == 2) it[0] to it[1] else null }

    /** Charts for [regionName] ("Global" or a country as the titles spell it), main charts first. */
    suspend fun forRegion(regionName: String): List<Chart> {
        val all = index()
        fun find(title: String) = all.firstOrNull { it.second.equals(title, ignoreCase = true) }
        val main = listOfNotNull(
            find("Top 100 Songs $regionName"),
            find("Daily top music videos – $regionName"),
            find("Top 100 Music Videos $regionName"),
        )
        // Genre charts exist for some countries only, titled "Top 50 <genre> music videos <country>".
        val genres = all.filter {
            it.second.startsWith("Top 50 ", ignoreCase = true) && it.second.endsWith(" $regionName", ignoreCase = true)
        }
        return (main + genres).map { Chart(it.first, it.second) }
    }

    /** The chart's playlist card and its tracks, from a cache refreshed every few hours. */
    suspend fun load(chart: Chart): Pair<MetadataPlaylist, List<MetadataTrack>>? {
        val id = chart.playlistId
        val cached = store.cachedAlbumPlaylist(id)
        val at = (store.get(CHART_AT_KEY + id) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val page = if (cached != null && epochMillis() - at < CHART_TTL_MS) cached else {
            orNull { client.playlist(id) }?.takeIf { it.relatedStreams.isNotEmpty() }?.also {
                store.cacheAlbumPlaylist(id, it)
                store.put(CHART_AT_KEY + id, JsonPrimitive(epochMillis().toString()))
            } ?: cached
        } ?: return null
        val tracks = page.relatedStreams.mapNotNull { it.toTrack() }
        return page.toPlaylist(id) to tracks
    }
}
