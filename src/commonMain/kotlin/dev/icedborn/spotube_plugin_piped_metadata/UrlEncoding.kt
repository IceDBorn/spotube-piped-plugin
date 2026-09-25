package dev.icedborn.spotube_plugin_piped_metadata

private const val HEX = "0123456789ABCDEF"

/** RFC 3986 percent-encoding over the UTF-8 bytes, so non-ASCII and surrogate pairs encode correctly. */
internal fun String.percentEncoded(): String = buildString {
    for (b in this@percentEncoded.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~") {
            append(c)
        } else {
            append('%').append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }
}

/** A request path without its query string, so a search term never reaches a log line. */
internal fun String.pathWithoutQuery(): String = substringBefore('?')
