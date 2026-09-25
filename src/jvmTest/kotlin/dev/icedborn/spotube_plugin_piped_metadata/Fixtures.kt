package dev.icedborn.spotube_plugin_piped_metadata

/** Canned Piped bodies, trimmed to 2-3 rows so the tests stay readable. */
internal object Fixtures {
    private fun read(name: String): String =
        checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes().decodeToString() }

    val searchMusicSongs: String get() = read("search_music_songs.json")
    val playlistAlbumPage1: String get() = read("playlist_album_page1.json")
    val playlistAlbumPage2: String get() = read("playlist_album_page2.json")
    val channel: String get() = read("channel.json")
    val streams: String get() = read("streams.json")
}
