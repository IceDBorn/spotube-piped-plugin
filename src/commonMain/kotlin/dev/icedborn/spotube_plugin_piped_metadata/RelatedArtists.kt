package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

internal const val RELATED_ARTISTS_LIMIT = 12

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
