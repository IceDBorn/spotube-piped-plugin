package dev.icedborn.spotube_plugin_piped.metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** Songs one artist may place in a radio batch before the rest of theirs wait for the leftover slots. */
internal const val RADIO_PER_ARTIST = 2

/** Mixes of other artists' songs that a short radio batch reads, one request each. */
internal const val RADIO_HOPS = 2

internal fun MetadataTrack.artistKey(): String = artists.firstOrNull()?.id ?: "track:$id"

/** A radio batch of up to [limit] songs that spreads across artists. Songs past [perArtist] for their artist go to
 * the end, so they only fill a batch the other artists could not. */
internal class ArtistSpread(private val limit: Int, private val perArtist: Int, exclude: Collection<String>) {

    private val seen = HashSet(exclude)
    private val kept = ArrayList<MetadataTrack>()
    private val overflow = ArrayList<MetadataTrack>()
    private val counts = HashMap<String, Int>()

    val full: Boolean get() = kept.size >= limit

    // Counts the songs past the artist limit too, so a top-up with more of the same artist is not fetched.
    val filled: Boolean get() = kept.size + overflow.size >= limit

    fun offer(tracks: List<MetadataTrack>) {
        for (track in tracks) {
            if (full) return
            if (!seen.add(track.id)) continue
            val count = counts[track.artistKey()] ?: 0
            if (count >= perArtist) {
                overflow += track
                continue
            }
            counts[track.artistKey()] = count + 1
            kept += track
        }
    }

    fun result(): List<MetadataTrack> = (kept + overflow).take(limit)
}
