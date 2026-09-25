package dev.icedborn.spotube_plugin_piped_metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings form posts each change; a select must show the stored value and carry the name the handler reads. */
class SettingsFormTest {

    private fun html(channel: String) = settingsFormHtml(
        instance = "https://piped.example",
        playback = "",
        username = "",
        lightTheme = false,
        region = REGION_GLOBAL,
        detectedRegion = "GR",
        channel = channel,
    )

    @Test
    fun `the channel select marks the stored value`() {
        assertTrue(html("NIGHTLY").contains("<option value=\"NIGHTLY\" selected>Nightly</option>"))
        assertTrue(html("STABLE").contains("<option value=\"STABLE\" selected>Stable</option>"))
        assertTrue(html("AUTO").contains("<option value=\"AUTO\" selected>Auto</option>"))
        val channelSelect = html("STABLE").substringAfter("<select id=\"channel\">").substringBefore("</select>")
        assertEquals(1, channelSelect.split(" selected").size - 1)
    }

    @Test
    fun `a change posts the channel the handler reads`() {
        val form = html("AUTO")
        assertTrue(form.contains("action: 'channel', channel: el('channel').value"))
        assertTrue(form.contains("<select id=\"channel\">"))
    }

    @Test
    fun `no unsubstituted placeholder is left in the form`() {
        val form = html("AUTO")
        assertFalse(Regex("__[A-Z]+__").containsMatchIn(form))
    }

    @Test
    fun `the region select still marks the stored region`() {
        assertTrue(html("AUTO").contains("<option value=\"$REGION_GLOBAL\" selected>Global</option>"))
    }
}
