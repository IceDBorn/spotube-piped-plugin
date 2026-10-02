package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.PipedSearchFilter
import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.icedborn.spotube_plugin_piped.client.toArtist
import dev.icedborn.spotube_plugin_piped.core.mapConcurrently
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

internal const val RELATED_ARTISTS_LIMIT = 12

/** Radio artists ranked before the match; extra ones replace those with no YouTube Music artist. */
internal const val RADIO_CANDIDATES = 2 * RELATED_ARTISTS_LIMIT

private const val MATCH_CACHE_SIZE = 500

/** Artists of [radio] ranked by how often they appear, minus [exclude] and ids with no real channel. */
internal fun rankRadioArtists(
    radio: List<MetadataTrack>,
    exclude: Set<String>,
    limit: Int = RELATED_ARTISTS_LIMIT,
): List<MetadataArtist.Basic> {
    val firstSeen = LinkedHashMap<String, MetadataArtist.Basic>()
    val counts = HashMap<String, Int>()
    radio.forEach { track ->
        val artist = track.artists.firstOrNull() ?: return@forEach
        // "channel:Name" ids open no artist page, so they are not worth showing.
        if (!artist.id.startsWith("UC")) return@forEach
        val key = canonicalArtistId(artist.id)
        if (key in exclude) return@forEach
        if (key !in firstSeen) firstSeen[key] = artist
        counts[key] = (counts[key] ?: 0) + 1
    }
    // sortedByDescending is stable, so ties keep the radio order YouTube ranked them in.
    return firstSeen.entries.sortedByDescending { counts[it.key] ?: 0 }
        .take(limit)
        .map { it.value }
}

/** Radio rows credit the uploading channel, which can be a label, a fan or a second channel of the artist.
 * The artist search returns the YouTube Music artist, so each name is matched against it. */
internal class MusicArtistMatch(private val client: PipedClient) {

    private val byName = LinkedHashMap<String, Found>()

    /** The artists of [candidates] in order, minus names with no exact match, repeats and [exclude]. */
    suspend fun resolve(
        candidates: List<MetadataArtist.Basic>,
        exclude: Set<String>,
        limit: Int = RELATED_ARTISTS_LIMIT,
    ): List<MetadataArtist.Basic> {
        val out = LinkedHashMap<String, MetadataArtist.Basic>()
        var next = 0
        while (out.size < limit && next < candidates.size) {
            val batch = candidates.subList(next, minOf(next + limit - out.size, candidates.size))
            next += batch.size
            for (artist in batch.mapConcurrently { lookup(it.name)?.artist }.filterNotNull()) {
                if (out.size < limit && artist.id !in exclude) out.getOrPut(artist.id) { artist }
            }
        }
        return out.values.toList()
    }

    /** Played [artists] as their YouTube Music artist, without repeats. A failed search keeps the artist as it is,
     * and a name with no exact match is dropped. */
    suspend fun resolvePlayed(artists: List<MetadataArtist.Basic>): List<MetadataArtist.Basic> =
        artists.mapConcurrently { artist -> lookup(artist.name).let { if (it == null) artist else it.artist } }
            .filterNotNull()
            .distinctBy { canonicalArtistId(it.id) }

    /** Null when the search failed; a [Found] with a null artist when no artist has that name. */
    private suspend fun lookup(name: String): Found? {
        val key = cleanArtistName(name).trim().lowercase()
        if (key.isEmpty()) return null
        byName[key]?.let { return it }
        // A failed search is not cached, so the name is tried again next time.
        val page = orNull("artist match") { client.search(key, PipedSearchFilter.MUSIC_ARTISTS) } ?: return null
        val found = Found(page.items.firstNotNullOfOrNull { item -> item.toArtist()?.takeIf { it.name.lowercase() == key } })
        byName[key] = found
        if (byName.size > MATCH_CACHE_SIZE) byName.remove(byName.keys.first())
        return found
    }

    private class Found(val artist: MetadataArtist.Basic?)
}
