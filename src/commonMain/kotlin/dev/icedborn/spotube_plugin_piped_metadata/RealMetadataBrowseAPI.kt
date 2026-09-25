package dev.icedborn.spotube_plugin_piped_metadata

import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseAPI
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseGenre
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseItem
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseSection
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

private const val FOR_YOU = "for-you"
private const val CHARTS = "charts"
private const val SECTIONS_PER_PAGE = 3
private const val ITEMS_PER_SECTION = 20
private const val CHART_ITEMS = 30
private const val SEEDS = 3
private const val SECTION_TTL_MS = 30 * 60_000L

/** One Home section, built only when the host scrolls to it. */
private class SectionSpec(val key: String, val build: suspend () -> MetadataBrowseSection?)

/**
 * Home: a "For you" tab built from the plugin's play log and saved items, and a "Charts" tab from YouTube's music
 * charts. The host caches every result until restart, and any exception blanks the whole Home, so nothing here throws.
 */
internal class RealMetadataBrowseAPI(
    private val library: LocalLibrary,
    private val mirror: PipedSavedLibrary,
    private val history: PlayHistory,
    private val region: RegionSetting,
    private val charts: Charts,
    private val tracks: RealMetadataTrackAPI,
    private val artists: RealMetadataArtistAPI,
    private val albums: RealMetadataAlbumAPI,
    private val playlists: RealMetadataPlaylistAPI,
) : MetadataBrowseAPI {

    private val built = HashMap<String, Pair<Long, MetadataBrowseSection?>>()
    private val radios = HashMap<String, List<MetadataTrack>>()

    override suspend fun featured(): List<MetadataBrowseItem> = orNull {
        // The carousel shows albums and playlists only (it drops artists and ignores taps on tracks).
        val recentAlbums = history.recentTracks(50).mapNotNull { it.album?.toBasic() }.distinctBy { it.id }.take(6)
        val saved = orNull { playlists.savedPlaylists(PaginationStrategy.Offset(0, 8)).items }.orEmpty()
        val savedAlbums = orNull { albums.savedAlbums(PaginationStrategy.Offset(0, 6)).items }.orEmpty()
        val personal = recentAlbums.map { MetadataBrowseItem.Album(it) } +
            saved.map { MetadataBrowseItem.Playlist(it) } +
            savedAlbums.map { MetadataBrowseItem.Album(it.toBasic()) }
        val items = personal.ifEmpty {
            chartsFor(regionName()).take(3).mapNotNull { chart -> charts.load(chart)?.first }
                .map { MetadataBrowseItem.Playlist(it) }
        }
        // The carousel keys items by hashCode, so a repeat would crash it.
        items.distinctBy { it.itemId() }.take(12)
    }.orEmpty()

    override suspend fun genres(): List<MetadataBrowseGenre> {
        val forYou = MetadataBrowseGenre(FOR_YOU, "For you")
        val chartsTab = MetadataBrowseGenre(CHARTS, "Charts")
        // The host opens the first tab; with nothing to personalize yet, that is Charts.
        return if (hasPersonalData()) listOf(forYou, chartsTab) else listOf(chartsTab, forYou)
    }

    override suspend fun list(
        genreId: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataBrowseSection> {
        val specs = orNull {
            when (genreId) {
                FOR_YOU -> forYouSpecs()
                CHARTS -> chartSpecs()
                else -> emptyList()
            }
        }.orEmpty()
        var next = (pagination as? PaginationStrategy.Offset)?.offset?.coerceAtLeast(0) ?: 0
        val out = mutableListOf<MetadataBrowseSection>()
        // Empty sections are skipped, so keep building until the page has content or the specs run out.
        while (next < specs.size && out.size < SECTIONS_PER_PAGE) {
            val batch = specs.subList(next, minOf(next + SECTIONS_PER_PAGE - out.size, specs.size))
            next += batch.size
            out += batch.mapConcurrently { section(it) }.filterNotNull()
        }
        return PaginationResult(
            items = out,
            totalCount = specs.size,
            nextPagination = if (next < specs.size) PaginationStrategy.Offset(next, SECTIONS_PER_PAGE) else null,
        )
    }

    // The host never calls sublist, and sections carry no "see more" link.
    override suspend fun sublist(
        genreId: String,
        sectionId: String,
        pagination: PaginationStrategy?,
    ): PaginationResult<MetadataBrowseItem> = emptyPagination()

    private suspend fun section(spec: SectionSpec): MetadataBrowseSection? {
        built[spec.key]?.let { (at, cached) -> if (epochMillis() - at < SECTION_TTL_MS) return cached }
        val result = orNull("home section ${spec.key}") { spec.build() }?.takeIf { it.items.isNotEmpty() }
        built[spec.key] = epochMillis() to result
        return result
    }

    // ── For you ──────────────────────────────────────────────────────────────

    private suspend fun hasPersonalData(): Boolean = orNull {
        history.all().isNotEmpty() || library.savedPlaylists().isNotEmpty() || library.storedPlaylists().isNotEmpty() ||
            mirror.allSavedTrackIds().isNotEmpty() || mirror.allSavedArtistIds().isNotEmpty() ||
            mirror.allSavedAlbumIds().isNotEmpty() || mirror.cachedState()?.playlists.orEmpty().isNotEmpty()
    } ?: false

    private suspend fun forYouSpecs(): List<SectionSpec> {
        val recent = history.recentTracks(50)
        // One seed per artist, so the radio sections do not all sound the same.
        val seeds = recent.distinctBy { it.artists.firstOrNull()?.id ?: it.id }.take(SEEDS)
        val savedArtistIds = mirror.allSavedArtistIds()
        val topArtistIds = (history.topArtists(10).map { canonicalArtistId(it.id) } + savedArtistIds).distinct()
        // Albums and playlists need a real channel id; "channel:Name" ids have none.
        val browsable = topArtistIds.filter { it.startsWith("UC") }.take(SEEDS)

        val specs = mutableListOf(
            SectionSpec("recent") { recentSection(recent) },
            SectionSpec("your-artists") { yourArtistsSection(topArtistIds) },
        )
        for (i in 0 until maxOf(seeds.size, browsable.size)) {
            seeds.getOrNull(i)?.let { seed -> specs += SectionSpec("radio:${seed.id}") { radioSection(seed) } }
            browsable.getOrNull(i)?.let { id -> specs += SectionSpec("albums:$id") { moreFromSection(id) } }
            if (i == 0) specs += SectionSpec("fans") { fansSection(seeds, topArtistIds) }
            browsable.getOrNull(i)?.let { id -> specs += SectionSpec("playlists:$id") { artistPlaylistsSection(id) } }
            if (i == 0) {
                specs += SectionSpec("your-playlists") { yourPlaylistsSection() }
                specs += SectionSpec("saved-albums") { savedAlbumsSection() }
            }
        }
        if (seeds.isEmpty() && browsable.isEmpty()) {
            specs += SectionSpec("your-playlists") { yourPlaylistsSection() }
            specs += SectionSpec("saved-albums") { savedAlbumsSection() }
        }
        return specs
    }

    private fun recentSection(recent: List<MetadataTrack>) = MetadataBrowseSection(
        title = "Recently played",
        description = null,
        items = recent.take(ITEMS_PER_SECTION).map { MetadataBrowseItem.Track(it) },
        moreLink = null,
    )

    private suspend fun yourArtistsSection(ids: List<String>): MetadataBrowseSection {
        val items = ids.take(ITEMS_PER_SECTION).mapConcurrently { id -> orNull { artists.getArtist(id) } }
            .filterNotNull()
            .filter { it.name.isNotBlank() }
            .map { MetadataBrowseItem.Artist(it.toBasic()) }
        return MetadataBrowseSection(title = "Your artists", description = null, items = items, moreLink = null)
    }

    private suspend fun radioFor(seed: MetadataTrack): List<MetadataTrack> = radios.getOrPut(seed.id) {
        orNull { tracks.recommendationsBasedOnTracks(listOf(seed.id), ITEMS_PER_SECTION) }.orEmpty()
    }

    private suspend fun radioSection(seed: MetadataTrack) = MetadataBrowseSection(
        title = seed.title,
        description = "Because you listened to",
        items = radioFor(seed).map { MetadataBrowseItem.Track(it) },
        moreLink = null,
    )

    /** Piped has no related-artists API: take the artists of the radio mixes that the user does not play yet. */
    private suspend fun fansSection(seeds: List<MetadataTrack>, known: List<String>): MetadataBrowseSection {
        val knownIds = known.toHashSet()
        val candidates = seeds.flatMap { radioFor(it) }
            .mapNotNull { it.artists.firstOrNull() }
            .filter { it.id.startsWith("UC") && canonicalArtistId(it.id) !in knownIds }
            .distinctBy { canonicalArtistId(it.id) }
            .take(12)
        // Radio rows carry no artist avatars; the channel fetch adds them and is cached for hours.
        val items = candidates.mapConcurrently { basic -> orNull { artists.getArtist(basic.id) }?.toBasic() ?: basic }
            .map { MetadataBrowseItem.Artist(it) }
        return MetadataBrowseSection(title = "Fans also like", description = null, items = items, moreLink = null)
    }

    private suspend fun moreFromSection(artistId: String): MetadataBrowseSection? {
        val artist = orNull { artists.getArtist(artistId) } ?: return null
        val items = artists.getArtistAlbums(artistId, null).items
            .distinctBy { it.id }
            .take(ITEMS_PER_SECTION)
            .map { MetadataBrowseItem.Album(it.toBasic()) }
        return MetadataBrowseSection(title = artist.name, description = "More from", items = items, moreLink = null)
    }

    private suspend fun artistPlaylistsSection(artistId: String): MetadataBrowseSection? {
        val artist = orNull { artists.getArtist(artistId) } ?: return null
        val items = artists.featuredPlaylists(artistId, null).items
            .distinctBy { it.id }
            .take(ITEMS_PER_SECTION)
            .map { MetadataBrowseItem.Playlist(it) }
        return MetadataBrowseSection(title = artist.name, description = "Playlists with", items = items, moreLink = null)
    }

    private suspend fun yourPlaylistsSection(): MetadataBrowseSection {
        val items = playlists.savedPlaylists(PaginationStrategy.Offset(0, ITEMS_PER_SECTION)).items
            .map { MetadataBrowseItem.Playlist(it) }
        return MetadataBrowseSection(title = "Your playlists", description = null, items = items, moreLink = null)
    }

    private suspend fun savedAlbumsSection(): MetadataBrowseSection {
        val items = albums.savedAlbums(PaginationStrategy.Offset(0, ITEMS_PER_SECTION)).items
            .map { MetadataBrowseItem.Album(it.toBasic()) }
        return MetadataBrowseSection(title = "Saved albums", description = null, items = items, moreLink = null)
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    private suspend fun regionName(): String = CHART_COUNTRIES[region.resolved()] ?: "Global"

    private suspend fun chartsFor(name: String): List<Chart> {
        val local = if (name == "Global") emptyList() else charts.forRegion(name)
        return (local + charts.forRegion("Global")).distinctBy { it.playlistId }
    }

    private suspend fun chartSpecs(): List<SectionSpec> = chartsFor(regionName()).map { chart ->
        SectionSpec("chart:${chart.playlistId}") {
            val (playlist, rows) = charts.load(chart) ?: return@SectionSpec null
            MetadataBrowseSection(
                title = chart.title,
                description = "YouTube Music charts",
                // The playlist card first opens the whole chart; the tracks after it play directly.
                items = listOf(MetadataBrowseItem.Playlist(playlist)) +
                    rows.take(CHART_ITEMS).map { MetadataBrowseItem.Track(it) },
                moreLink = null,
            )
        }
    }
}

private fun MetadataBrowseItem.itemId(): String = when (this) {
    is MetadataBrowseItem.Album -> "album:" + data.id
    is MetadataBrowseItem.Artist -> "artist:" + data.id
    is MetadataBrowseItem.Playlist -> "playlist:" + data.id
    is MetadataBrowseItem.Track -> "track:" + data.id
    else -> toString()
}

private fun MetadataAlbum.Detailed.toBasic() = MetadataAlbum.Basic(
    id = id,
    title = title,
    description = description,
    thumbnails = thumbnails,
    albumType = albumType,
    artists = artists,
    externalUri = externalUri,
)

private fun MetadataArtist.Detailed.toBasic() = MetadataArtist.Basic(
    id = id,
    name = name,
    thumbnails = thumbnails,
    externalUri = externalUri,
)
