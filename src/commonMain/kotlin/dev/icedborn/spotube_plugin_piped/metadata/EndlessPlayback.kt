package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.core.epochMillis
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import dev.icedborn.spotube_plugin_piped.store.PlayHistory
import dev.icedborn.spotube_plugin_piped.store.PlayedTrack
import dev.icedborn.spotube_plugin_piped.store.orNull
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow

/** Mixes of other artists' songs that a short radio batch reads, one request each. */
internal const val RADIO_HOPS = 2

private const val SEED_MIXES_PER_BATCH = 2

/** Seed mixes stop once the pool holds this many batches of usable songs. */
private const val POOL_BATCHES = 2

private const val RECENT_PLAY_MS = 6 * 60 * 60_000L
private const val FAMILIAR_REST_MS = 2 * 24 * 60 * 60_000L
private const val LIKED_SCAN_LIMIT = 200
private const val HOP_WEIGHT = 0.7
private const val CATALOG_WEIGHT = 0.3
private const val SKIP_FACTOR = 0.5
private const val LIKED_BONUS = 3.0

/** Builds endless playback batches from the station the seeds belong to, so no batch repeats an earlier one. */
internal class EndlessPlayback(
    private val mixes: RadioMixes,
    private val stations: RadioStations,
    private val store: EntityStore,
    private val history: PlayHistory,
    private val now: () -> Long = { epochMillis() },
    private val likedIds: suspend () -> List<String>,
) {

    // Batches read and then rewrite the stations, so they run one at a time.
    private val lock = Mutex()

    suspend fun batch(seedIds: List<String>, limit: Int): List<MetadataTrack> {
        if (seedIds.isEmpty() || limit <= 0) return emptyList()
        return lock.withLock { build(seedIds.distinct(), limit) }
    }

    private suspend fun build(seeds: List<String>, limit: Int): List<MetadataTrack> {
        val at = now()
        val live = stations.load().filter { at - it.updatedAt < STATION_IDLE_MS }
        // One seed means the listener just picked a song, so only a station that already queued it goes on.
        val chosen = live.filter { it.overlap(seeds) > 0 && (seeds.size > 1 || seeds[0] in it.queued) }
            .maxWithOrNull(compareBy<RadioStation> { it.overlap(seeds) }.thenBy { it.updatedAt })
        val plays = history.all().associateBy { it.track.id }
        val liked = likedIds().takeLast(LIKED_SCAN_LIMIT).toSet()
        val start = (chosen ?: RadioStation(at)).withSkips { plays[it]?.lastPlayedAt ?: 0L }.withQueued(seeds)
        val batch = StationBatch(start, at, seeds, limit, plays, liked)
        batch.prepare()
        batch.readSeedMixes()
        batch.readHops()
        var picks = batch.picks()
        if (picks.size < limit) {
            batch.readCatalog(seeds.first())
            picks = batch.picks()
        }
        val saved = batch.station.afterBatch(picks.map { StationTrack(it.track.id, it.version) }, at)
        stations.save(listOf(saved) + live.filter { it !== chosen })
        val tracks = picks.map { it.track }
        store.rememberTracks(tracks)
        return tracks
    }

    /** One batch in the making. [station] collects the mixes the batch reads. */
    private inner class StationBatch(
        var station: RadioStation,
        private val at: Long,
        private val seeds: List<String>,
        private val limit: Int,
        private val plays: Map<String, PlayedTrack>,
        private val liked: Set<String>,
    ) {
        // Memoized per batch, since every store read of a track touches the eviction index.
        private val tracks = HashMap<String, MetadataTrack?>()
        private val seedArtists = HashSet<String>()
        private val seedTags = HashSet<String>()
        private val excludedIds = HashSet<String>()
        private val excludedVersions = HashSet<String>()
        private val returnedArtists = HashSet<String>()
        private val skippedIds = station.skipped.mapTo(HashSet()) { it.id }
        private val skips = station.skipped.groupingBy { it.version.substringBefore('|') }.eachCount()

        init {
            excludedIds += seeds + station.queued + station.returnedIds()
            for (batch in station.batches) {
                for (track in batch.tracks) {
                    excludedVersions += track.version
                    returnedArtists += track.version.substringBefore('|')
                }
            }
            for (play in plays.values) {
                if (at - play.lastPlayedAt >= RECENT_PLAY_MS) continue
                excludedIds += play.track.id
                excludedVersions += versionKey(play.track)
            }
        }

        suspend fun prepare() {
            for (seed in seeds) seedTrack(seed)?.let { learnSeed(it) }
            // Older seeds may still wait in the queue, so their other versions stay out too.
            for (id in station.queued) seedTrack(id)?.let { excludedVersions += versionKey(it) }
        }

        suspend fun readSeedMixes() {
            val order = seeds.filter { it !in station.read && it !in skippedIds }
                .sortedWith(compareByDescending<String> { plays[it]?.lastPlayedAt ?: -1L }.thenByDescending { it in liked })
                .take(SEED_MIXES_PER_BATCH)
            for (seed in order) {
                if (discovery().size >= limit * POOL_BATCHES) break
                val mix = mixes.mix(seed) ?: continue
                read(seed, mix, 1.0)
                excludedIds += mix.songSeed
                station = station.withQueued(listOf(mix.songSeed))
                mix.seed?.let { learnSeed(it) }
            }
        }

        suspend fun readHops() {
            repeat(RADIO_HOPS) {
                if (strict().size >= limit) return
                val hop = discovery().firstOrNull { it.artist !in seedArtists && it.track.id !in station.read } ?: return
                seedArtists += hop.artist
                val mix = mixes.mix(hop.track.id) ?: return@repeat
                read(hop.track.id, mix, HOP_WEIGHT)
            }
        }

        suspend fun readCatalog(seed: String) {
            val catalog = orNull("radio catalog") { mixes.catalogTracks(seed) } ?: return
            store.rememberTracks(catalog)
            for (track in catalog) tracks[track.id] = track
            station = station.withRanked(catalog.map { it.id }, CATALOG_WEIGHT)
        }

        suspend fun picks(): List<Candidate> {
            val strict = strict()
            val open = discovery()
            return fillBatch(strict, open, limit)
        }

        private suspend fun strict(): List<Candidate> {
            val open = discovery()
            val familiar = familiar(open)
            return pickBatch(open, familiar, limit, station.lastArtists())
        }

        private suspend fun discovery(): List<Candidate> {
            val out = ArrayList<Candidate>()
            val versions = HashSet<String>()
            for (entry in station.pool) {
                val track = track(entry.id) ?: continue
                val candidate = Candidate(track, entry.score * penalty(artistKeyOf(track)))
                if (usable(track, candidate.version) && versions.add(candidate.version)) out += candidate
            }
            return out.sortedByDescending { it.score }
        }

        private suspend fun familiar(discovery: List<Candidate>): List<Candidate> {
            val artists = seedArtists + returnedArtists + discovery.map { it.artist }
            val taken = discovery.mapTo(HashSet()) { it.version }
            val out = ArrayList<Candidate>()
            for (id in plays.keys + liked) {
                val play = plays[id]
                if (play != null && play.lastPlayedAt > 0 && at - play.lastPlayedAt < FAMILIAR_REST_MS) continue
                val track = play?.track ?: track(id) ?: continue
                if (looksLikeVideo(track.title, track.artists.firstOrNull()?.name.orEmpty())) continue
                val bonus = if (id in liked) LIKED_BONUS else 0.0
                val candidate = Candidate(track, ((play?.plays ?: 0) + bonus) * penalty(artistKeyOf(track)))
                if (candidate.artist in artists && candidate.version !in taken && usable(track, candidate.version)) out += candidate
            }
            return out.sortedByDescending { it.score }.distinctBy { it.version }
        }

        private suspend fun read(seed: String, mix: RadioMixes.Mix, weight: Double) {
            store.rememberTracks(mix.tracks)
            for (track in mix.tracks) tracks[track.id] = track
            station = station.withRanked(mix.tracks.map { it.id }, weight).withRead(listOf(seed, mix.songSeed))
        }

        private fun learnSeed(track: MetadataTrack) {
            seedArtists += artistKeyOf(track)
            seedTags += versionTags(track)
            excludedVersions += versionKey(track)
        }

        private fun usable(track: MetadataTrack, version: String) =
            track.id !in excludedIds && version !in excludedVersions && versionTags(track).all { it in seedTags }

        private fun penalty(artist: String) = SKIP_FACTOR.pow(skips[artist] ?: 0)

        private suspend fun seedTrack(id: String): MetadataTrack? = plays[id]?.track ?: track(id)

        private suspend fun track(id: String): MetadataTrack? {
            if (id in tracks) return tracks[id]
            val track = store.cachedTrack(id)
            tracks[id] = track
            return track
        }
    }
}
