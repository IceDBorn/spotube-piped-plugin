package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.cleanArtistName
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** Songs one artist may place in a radio batch before the rest of theirs wait for the leftover slots. */
internal const val RADIO_PER_ARTIST = 2

/** Songs that play between two songs of one artist, counted across batches. */
internal const val ARTIST_GAP = 3

/** Every 4th slot of a batch takes a familiar song, so they fill at most a quarter of it. */
internal const val FAMILIAR_EVERY = 4

private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
private val DASH_SUFFIX = Regex(" [-–—] ")

/** Title tags that mark another take of a song. A song with one is usable only when a seed has it too. */
private val VERSION_TAGS = mapOf(
    "live" to Regex("""\blive\b"""),
    "remix" to Regex("""\b(remix|rmx)\b"""),
    "cover" to Regex("""\bcover\b"""),
    "sped up" to Regex("""\b(sped|speed) up\b"""),
    "slowed" to Regex("""\bslowed\b"""),
    "nightcore" to Regex("""\bnightcore\b"""),
    "karaoke" to Regex("""\bkaraoke\b"""),
    "session" to Regex("""\b(sessions?|from the basement)\b"""),
)

/** A song a batch may take, with its [score] in the pool. */
internal class Candidate(val track: MetadataTrack, val score: Double) {
    val artist = artistKeyOf(track)
    val version = versionKey(track)
}

/** The first artist's name in letters and digits, so an artist's topic, band and fan channels share one key. */
internal fun artistKeyOf(track: MetadataTrack): String =
    nameKey(cleanArtistName(track.artists.firstOrNull()?.name.orEmpty())).ifEmpty { "track:${track.id}" }

/** [title] without bracketed parts and a " - " suffix, in letters and digits. */
internal fun baseTitle(title: String): String {
    val lower = title.lowercase()
    val head = DASH_SUFFIX.split(BRACKETS.replace(lower, ""), 2).first()
    return nameKey(head).ifEmpty { nameKey(lower).ifEmpty { lower } }
}

/** [title] minus a leading "<artist> - " part that names [artistName], which uploads carry. */
private fun stripArtistPrefix(title: String, artistName: String): String {
    // The dash regex has no letters, so the original title finds the same cut, and lowercasing would shift it.
    val match = DASH_SUFFIX.find(title) ?: return title
    val prefix = title.substring(0, match.range.first)
    return if (namesMatch(prefix, cleanArtistName(artistName))) title.substring(match.range.last + 1) else title
}

/** One key for every version of a song by one artist, such as "Song" and "Song (Remastered)". */
internal fun versionKey(track: MetadataTrack): String =
    "${artistKeyOf(track)}|${baseTitle(stripArtistPrefix(track.title, artistNameOf(track)))}"

private fun artistNameOf(track: MetadataTrack) = track.artists.firstOrNull()?.name.orEmpty()

/** The [VERSION_TAGS] in [title]'s bracketed parts and " - " suffix, where titles name another take. */
private fun versionTags(title: String): Set<String> {
    val lower = title.lowercase()
    val parts = BRACKETS.findAll(lower).map { it.value }.toList() + DASH_SUFFIX.split(lower, 2).drop(1)
    return VERSION_TAGS.filterValues { tag -> parts.any { tag.containsMatchIn(it) } }.keys
}

/** The [VERSION_TAGS] of [track]'s title after its own artist prefix, which uploads carry, is dropped. */
internal fun versionTags(track: MetadataTrack): Set<String> = versionTags(stripArtistPrefix(track.title, artistNameOf(track)))

/** Up to [limit] songs of [discovery] in order, at most [RADIO_PER_ARTIST] per artist and [ARTIST_GAP] songs between
 * two of one artist, [recentArtists] included. Every [FAMILIAR_EVERY]th slot takes from [familiar] first. */
internal fun pickBatch(discovery: List<Candidate>, familiar: List<Candidate>, limit: Int, recentArtists: List<String>): List<Candidate> {
    val picks = ArrayList<Candidate>()
    val counts = HashMap<String, Int>()
    val recent = ArrayList(recentArtists)
    val open = ArrayList(discovery)
    val openFamiliar = ArrayList(familiar)

    fun allowed(candidate: Candidate) =
        (counts[candidate.artist] ?: 0) < RADIO_PER_ARTIST && candidate.artist !in recent.takeLast(ARTIST_GAP)

    fun take(from: MutableList<Candidate>): Boolean {
        val index = from.indexOfFirst { allowed(it) }
        if (index < 0) return false
        val pick = from.removeAt(index)
        picks += pick
        counts[pick.artist] = (counts[pick.artist] ?: 0) + 1
        recent += pick.artist
        return true
    }

    while (picks.size < limit) {
        if ((picks.size + 1) % FAMILIAR_EVERY == 0 && take(openFamiliar)) continue
        if (!take(open)) break
    }
    return picks
}

/** [picks] topped up from [rest] in order without the artist rules, skipping versions already picked. */
internal fun fillBatch(picks: List<Candidate>, rest: List<Candidate>, limit: Int): List<Candidate> {
    val taken = picks.mapTo(HashSet()) { it.version }
    return picks + rest.filter { it.version !in taken }.take((limit - picks.size).coerceAtLeast(0))
}
