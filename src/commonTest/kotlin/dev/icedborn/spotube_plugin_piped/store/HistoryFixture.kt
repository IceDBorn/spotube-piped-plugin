package dev.icedborn.spotube_plugin_piped.store

import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.FakePiped
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.assertNotNull

internal fun logTrack(index: Int) = MetadataTrack(
    id = vid(index),
    title = "Track $index",
    durationMs = 200_000,
    trackNumber = null,
    discNumber = null,
    artists = listOf(MetadataArtist.Basic(id = "UCa", name = "Alpha", thumbnails = emptyList(), externalUri = null)),
    album = null,
    thumbnails = emptyList(),
    explicit = null,
    popularity = null,
    isrcCode = null,
    externalUri = null,
)

/** A device of the account on [piped]. Two of them over one [FakePiped] are two devices of that account. */
internal fun TestScope.logDevice(piped: FakePiped = FakePiped(pageSize = 100)) = AccountHarness(backgroundScope, piped)

internal fun AccountHarness.logPlaylist(): FakePiped.Playlist? = piped.account.values.firstOrNull { it.name == HISTORY_PLAYLIST_NAME }

internal suspend fun AccountHarness.waitingPlays(): PendingPlays? = store.getDecoded(HISTORY_PENDING_KEY, PendingPlays.serializer())

/** A scrobbled play the way the scrobble role reports it: counted on the device, then handed to the sync. */
internal suspend fun TestScope.play(h: AccountHarness, index: Int) {
    h.history.record(logTrack(index))
    h.mirror.played(logTrack(index))
    testScheduler.runCurrent()
}

internal suspend fun TestScope.refresh(h: AccountHarness) {
    h.mirror.refreshCache()
    testScheduler.runCurrent()
}

/** Ends the wait of every refused play, the way six hours would. */
internal suspend fun AccountHarness.endWaits() {
    val box = assertNotNull(waitingPlays())
    store.put(HISTORY_PENDING_KEY, json.encodeToJsonElement(box.copy(plays = box.plays.map { it.copy(retryAt = 0) })))
}
