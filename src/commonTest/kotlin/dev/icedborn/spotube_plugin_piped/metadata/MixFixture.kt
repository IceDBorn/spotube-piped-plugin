package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedSearchItem
import dev.icedborn.spotube_plugin_piped.client.toTrack
import dev.icedborn.spotube_plugin_piped.fakes.vid
import dev.krtirtho.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A mix row the way /playlists lists it. */
internal fun mixRow(videoId: String, uploader: String, channel: String, title: String = "Song $videoId") = buildJsonObject {
    put("type", "stream")
    put("url", "/watch?v=$videoId")
    put("title", title)
    put("uploaderName", uploader)
    put("uploaderUrl", "/channel/$channel")
    put("duration", 200)
}

internal fun mixBody(rows: List<JsonObject>) = buildJsonObject {
    put("name", "Mix")
    put("nextpage", null as String?)
    put("relatedStreams", JsonArray(rows))
}.toString()

/** A channel id made from an artist name, so each test artist has its own channel. */
internal fun artistChannel(name: String) = "UC" + name.padEnd(22, '0')

internal fun artistRow(index: Int, artist: String, title: String = "Song ${vid(index)}") =
    mixRow(vid(index), artist, artistChannel(artist), title)

/** The track [artistRow] decodes to. */
internal fun rowTrack(index: Int, artist: String, title: String = "Song ${vid(index)}"): MetadataTrack =
    Json.decodeFromJsonElement(PipedSearchItem.serializer(), artistRow(index, artist, title)).toTrack()!!
