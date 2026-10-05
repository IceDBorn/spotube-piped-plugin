package dev.icedborn.spotube_plugin_piped.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the body of a mutation's answer proves. Piped answers 200 for a refusal too. */
class PostOutcomeTest {

    @Test
    fun `an object without an error confirms the write`() {
        assertEquals(PostOutcome.CONFIRMED, postOutcome("""{"message":"ok"}"""))
        assertEquals(PostOutcome.CONFIRMED, postOutcome("""{"playlistId":"abc"}"""))
    }

    @Test
    fun `an object with an error is a refusal`() {
        assertEquals(PostOutcome.REFUSED, postOutcome("""{"error":"Playlist not found"}"""))
        assertEquals(PostOutcome.REFUSED, postOutcome("""{"error":"Video Index not found"}"""))
    }

    @Test
    fun `any other body proves nothing`() {
        listOf(null, "", "{}", "[]", "<html>").forEach { assertEquals(PostOutcome.UNCLEAR, postOutcome(it), "body: $it") }
    }

    @Test
    fun `only a confirmed write passes postConfirmed`() {
        assertEquals(true, postConfirmed("""{"message":"ok"}"""))
        assertEquals(false, postConfirmed("""{"error":"Playlist not found"}"""))
        assertEquals(false, postConfirmed("{}"))
    }
}
