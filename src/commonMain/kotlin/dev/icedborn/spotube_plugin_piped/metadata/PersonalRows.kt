package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.canonicalArtistId
import dev.icedborn.spotube_plugin_piped.core.localOffsetMinutes
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.PLAYS_KEY
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlaySnapshot
import dev.icedborn.spotube_plugin_piped.sync.PlaySource
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseItem
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.browse.MetadataBrowseSection
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

private const val ROW_TRACKS = 20
private const val MIN_ROW_TRACKS = 5
private const val MIN_MIX_PLAYED = 3
private const val MIN_MIX_KNOWN_ONLY = 10

/** Ids looked up per row; ids without stored details drop out, so a row reads more than it shows. */
private const val DETAIL_SCAN = 100
private const val MIX_SCAN = 200
private const val SNAPSHOT_REUSE_MS = 60_000L

private fun artistKey(track: MetadataTrack): String? = track.artists.firstOrNull()?.let { canonicalArtistId(it.id) }

/** The For you rows built from play times. Only the daily mixes make a request, one radio each. */
internal class PersonalRows(
    private val source: PlaySource,
    private val history: PlayHistory,
    private val store: EntityStore,
    private val likedIds: suspend () -> List<String>,
    private val radioFor: suspend (MetadataTrack) -> List<MetadataTrack>,
    private val now: () -> Long,
    private val offsetMinutes: () -> Int = ::localOffsetMinutes,
) {
    private var snapshot: Triple<Long, String, PlaySnapshot>? = null

    // Lookups of the current snapshot, misses included, so the rows of one build read each track once.
    private val resolved = HashMap<String, MetadataTrack?>()

    suspend fun hasPlays(): Boolean = plays().plays.isNotEmpty()

    // The rows of one page build together, so they share one read of the plays.
    private suspend fun plays(): PlaySnapshot {
        val key = store.resolvedKey(PLAYS_KEY)
        snapshot?.let { (at, currentKey, cached) ->
            if (currentKey == key && now() - at < SNAPSHOT_REUSE_MS) return cached
        }
        val fresh = source.current().snapshot()
        snapshot = Triple(now(), key, fresh)
        resolved.clear()
        return fresh
    }

    private suspend fun details(ids: List<String>): Map<String, MetadataTrack> {
        val missing = ids.filterNot { it in resolved }
        if (missing.isNotEmpty()) {
            val local = history.all().associate { it.track.id to it.track }
            for (id in missing) resolved[id] = local[id] ?: store.cachedTrack(id)
        }
        val out = LinkedHashMap<String, MetadataTrack>()
        for (id in ids) resolved[id]?.let { out[id] = it }
        return out
    }

    private suspend fun row(title: String, ids: List<String>): MetadataBrowseSection? {
        val tracks = details(ids.take(DETAIL_SCAN)).values.take(ROW_TRACKS)
        if (tracks.size < MIN_ROW_TRACKS) return null
        return MetadataBrowseSection(
            title = title,
            description = null,
            items = tracks.map { MetadataBrowseItem.Track(it) },
            moreLink = null,
        )
    }

    suspend fun onRepeatRow(): MetadataBrowseSection? = row("On repeat", onRepeat(plays().plays, now()))

    suspend fun forgottenRow(): MetadataBrowseSection? = row("Forgotten favorites", forgottenFavorites(plays().totals, now()))

    suspend fun timeMixRow(): MetadataBrowseSection? {
        val offset = offsetMinutes()
        val name = TIME_WINDOWS[timeWindow(now(), offset)]
        return row("Your $name mix", timeOfDay(plays().plays, now(), offset))
    }

    /** Mix [index] from 0: the group's played and liked songs taking turns with a radio of its top track. */
    suspend fun dailyMixRow(index: Int): MetadataBrowseSection? {
        val time = now()
        val recent = recentPlays(plays().plays, time)
        val ranked = rankedIds(recent)
        val byId = details(ranked.take(MIX_SCAN))
        val artistOf = { id: String -> byId[id]?.let(::artistKey) }
        val group = artistGroups(recent.filter { it.id in byId }, artistOf).getOrNull(index)?.toSet() ?: return null
        val played = ranked.filter { artistOf(it) in group }
        if (played.size < MIN_MIX_PLAYED) return null
        val rested = recent.filter { time - it.at < MIX_REST_MS }.map { it.id }.toHashSet()
        val liked = details(likedIds().take(MIX_SCAN)).values.filter { artistKey(it) in group }
        val known = (played.map { byId.getValue(it) } + liked).distinctBy { it.id }.filterNot { it.id in rested }
        val knownIds = known.map { it.id }.toHashSet()
        val seed = byId.getValue(played.first())
        val radio = radioFor(seed)
        val fresh = radio.filterNot { it.id in rested || it.id in knownIds }
        if (fresh.isEmpty() && known.size < MIN_MIX_KNOWN_ONLY) return null
        if (known.size + fresh.size < MIN_ROW_TRACKS) return null
        val tracks = (known + fresh).associateBy { it.id }
        val names = played.mapNotNull { byId[it]?.artists?.firstOrNull()?.name }.distinct().take(3)
        return MetadataBrowseSection(
            title = "Daily mix ${index + 1}",
            description = names.joinToString(", "),
            items = alternate(known.map { it.id }, fresh.map { it.id }).map { MetadataBrowseItem.Track(tracks.getValue(it)) },
            moreLink = null,
        )
    }
}
