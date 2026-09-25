package dev.icedborn.spotube_plugin_piped_metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlEncodingTest {

    private fun enc(s: String) = s.percentEncoded()

    @Test
    fun `unreserved characters pass through`() {
        assertEquals("abc-_.~", enc("abc-_.~"))
        assertEquals("AZaz09", enc("AZaz09"))
    }

    @Test
    fun `spaces and reserved characters encode`() {
        assertEquals("a%20b", enc("a b"))
        assertEquals("a%26b%3Dc", enc("a&b=c"))
        assertEquals("%2F%3F%23", enc("/?#"))
    }

    @Test
    fun `non-ascii letters encode as utf-8`() {
        assertEquals("%CE%A3", enc("\u03A3"))
        assertEquals("%CE%AC", enc("\u03AC"))
        assertEquals("%E6%97%A5", enc("\u65E5"))
    }

    @Test
    fun `surrogate pairs encode as one utf-8 sequence`() {
        assertEquals("%F0%9F%8E%B5", enc("\uD83C\uDFB5"))
        assertNotReplacement(enc("\uD83C\uDFB5"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", enc(""))
    }

    private fun assertNotReplacement(encoded: String) =
        kotlin.test.assertFalse(encoded.contains("%EF%BF%BD"), "a lone surrogate half leaked U+FFFD")
}
