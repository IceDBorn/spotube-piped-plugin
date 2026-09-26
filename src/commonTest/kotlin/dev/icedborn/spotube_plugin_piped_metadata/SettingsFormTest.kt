package dev.icedborn.spotube_plugin_piped_metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings form posts each change; a select must show the stored value and carry the name the handler reads. */
class SettingsFormTest {

    private fun html(channel: String = "AUTO", library: String = LibraryPlaylist.ALWAYS.name) = settingsFormHtml(
        instance = "https://piped.example",
        playback = "",
        username = "",
        lightTheme = false,
        region = REGION_GLOBAL,
        detectedRegion = "GR",
        channel = channel,
        library = library,
    )

    @Test
    fun `the channel select marks the stored value`() {
        assertTrue(html(channel = "NIGHTLY").contains("<option value=\"NIGHTLY\" selected>Nightly</option>"))
        assertTrue(html(channel = "STABLE").contains("<option value=\"STABLE\" selected>Stable</option>"))
        assertTrue(html(channel = "AUTO").contains("<option value=\"AUTO\" selected>Auto</option>"))
        val channelSelect = html(channel = "STABLE").substringAfter("<select id=\"channel\">").substringBefore("</select>")
        assertEquals(1, channelSelect.split(" selected").size - 1)
    }

    @Test
    fun `a change posts the channel the handler reads`() {
        val form = html(channel = "AUTO")
        assertTrue(form.contains("action: 'channel', channel: el('channel').value"))
        assertTrue(form.contains("<select id=\"channel\">"))
    }

    @Test
    fun `no unsubstituted placeholder is left in the form`() {
        val form = html(channel = "AUTO")
        assertFalse(Regex("__[A-Z]+__").containsMatchIn(form))
    }

    @Test
    fun `the region select still marks the stored region`() {
        assertTrue(html(channel = "AUTO").contains("<option value=\"$REGION_GLOBAL\" selected>Global</option>"))
    }

    @Test
    fun `three tab buttons and three panes exist`() {
        val form = html()
        assertTrue(form.contains("id=\"tabLogin\""))
        assertTrue(form.contains("id=\"tabInstance\""))
        assertTrue(form.contains("id=\"tabSettings\""))
        assertTrue(form.contains("id=\"paneLogin\""))
        assertTrue(form.contains("id=\"paneInstance\""))
        assertTrue(form.contains("id=\"paneSettings\""))
    }

    @Test
    fun `region channel and library selects are inside the settings pane`() {
        val form = html()
        val settings = form.substringAfter("<form id=\"paneSettings\"")
        assertTrue(settings.contains("id=\"region\""))
        assertTrue(settings.contains("id=\"channel\""))
        assertTrue(settings.contains("id=\"library\""))
    }

    @Test
    fun `the library select marks the stored value`() {
        val settings = html(library = LibraryPlaylist.ALWAYS.name).substringAfter("<form id=\"paneSettings\"")
        assertTrue(settings.contains("value=\"ALWAYS\" selected"))
    }
}
