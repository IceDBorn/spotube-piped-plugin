package dev.icedborn.spotube_plugin_piped.metadata

import dev.icedborn.spotube_plugin_piped.client.PipedClient
import dev.icedborn.spotube_plugin_piped.client.json
import dev.icedborn.spotube_plugin_piped.fakes.FakeHttp
import dev.icedborn.spotube_plugin_piped.fakes.FakeStorage
import dev.icedborn.spotube_plugin_piped.store.EntityStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The chart index read from the YouTube Music charts channel, and chart playlists. */
class ChartsTest {

    private val channelBody = """{"id":"UCrKZcyOJVWnJ60zM1XWllNw","name":"Charts","relatedStreams":[],
        "tabs":[{"name":"playlists","data":"TABDATA"}]}"""
    private val tabBody = """{"content":[
        {"url":"/playlist?list=PLglobal","name":"Top 100 Songs Global"},
        {"url":"/playlist?list=PLgreece","name":"Top 100 Songs Greece"},
        {"url":"/playlist?list=PLgenre","name":"Top 50 Pop music videos Greece"}],"nextpage":null}"""
    private val chartPage = """{"name":"Top 100 Songs Global","videos":1,"nextpage":null,"relatedStreams":[
        {"type":"stream","url":"/watch?v=vid00000010","title":"One","uploaderName":"A","uploaderUrl":"","duration":200}]}"""

    private class Harness(val http: FakeHttp = FakeHttp()) {
        val storage = FakeStorage()
        val store = EntityStore(storage)
        val charts = Charts(PipedClient(http) { "https://piped.example" }, store)
    }

    private fun Harness.serveIndex() {
        http.onPath("/channel/UCrKZcyOJVWnJ60zM1XWllNw", body = channelBody)
        http.onPath("/channels/tabs", body = tabBody)
    }

    @Test
    fun `the index is fetched once and a region gets its main and genre charts`() = runTest {
        val h = Harness()
        h.serveIndex()
        assertEquals(listOf("PLgreece", "PLgenre"), h.charts.forRegion("Greece").map { it.playlistId })
        assertEquals(listOf("PLglobal"), h.charts.forRegion("Global").map { it.playlistId })
        assertEquals(2, h.http.requests.size)
    }

    @Test
    fun `an expired index is fetched again`() = runTest {
        val h = Harness()
        h.serveIndex()
        h.store.put(
            "charts.index",
            buildJsonObject {
                put("fetchedAt", 1L)
                put("charts", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("PLold"), JsonPrimitive("Top 100 Songs Global"))))))
            },
        )
        assertEquals(listOf("PLglobal"), h.charts.forRegion("Global").map { it.playlistId })
        assertEquals(2, h.http.requests.size)
    }

    @Test
    fun `a failing channel falls back to the three global charts`() = runTest {
        val h = Harness()
        h.http.onPath("/channel/", status = 500, body = "")
        assertEquals(3, h.charts.forRegion("Global").size)
    }

    @Test
    fun `a chart playlist is cached for a few hours`() = runTest {
        val h = Harness()
        h.http.onPath("/playlists/PLglobal", body = chartPage)
        val chart = Chart("PLglobal", "Top 100 Songs Global")
        val (playlist, tracks) = assertNotNull(h.charts.load(chart))
        assertEquals("PLglobal", playlist.id)
        assertEquals(listOf("vid00000010"), tracks.map { it.id })
        h.charts.load(chart)
        assertEquals(1, h.http.requests.size)
    }

    @Test
    fun `a chart that cannot load and has no copy is null`() = runTest {
        val h = Harness()
        h.http.onPath("/playlists/", status = 500, body = "")
        assertNull(h.charts.load(Chart("PLglobal", "Top 100 Songs Global")))
    }
}
