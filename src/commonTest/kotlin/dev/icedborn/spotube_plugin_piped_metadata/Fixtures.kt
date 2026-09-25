package dev.icedborn.spotube_plugin_piped_metadata

/** Canned Piped bodies, trimmed to 2-3 rows so the tests stay readable. Inlined as raw strings
 * because resource loading is JVM-only. */
internal object Fixtures {
    val searchMusicSongs: String = """{
  "items": [
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=vid00000000",
      "title": "Alpha Song",
      "name": "Alpha Song",
      "thumbnail": "https://piped.example/thumb0.jpg",
      "uploaderName": "Alpha Artist - Topic",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "uploaderAvatar": "https://piped.example/av.jpg",
      "duration": 181,
      "views": 1000,
      "subscribers": -1,
      "verified": false,
      "description": null
    },
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=vid00000010",
      "title": "Beta Song",
      "name": "Beta Song",
      "thumbnail": "https://piped.example/thumb1.jpg",
      "uploaderName": "Alpha Artist - Topic",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelbeta00020000000",
      "uploaderAvatar": "https://piped.example/av.jpg",
      "duration": 245,
      "views": 1001,
      "subscribers": -1,
      "verified": false,
      "description": null
    },
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=vid00000020",
      "title": "Gamma Song",
      "name": "Gamma Song",
      "thumbnail": "https://piped.example/thumb2.jpg",
      "uploaderName": "Alpha Artist - Topic",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelgamma0030000000",
      "uploaderAvatar": "https://piped.example/av.jpg",
      "duration": 300,
      "views": 1002,
      "subscribers": -1,
      "verified": false,
      "description": null
    },
    {
      "type": "playlist",
      "url": "https://www.youtube.com/playlist?list=OLAK5uy_abcdefg",
      "title": "Album - Single",
      "name": "Album - Single",
      "thumbnail": "https://piped.example/cover.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": -1,
      "views": -1,
      "subscribers": -1,
      "verified": false,
      "description": null
    }
  ],
  "nextpage": "TOKEN2"
}"""
    val playlistAlbumPage1: String = """{
  "name": "Album - Single",
  "description": "the album",
  "thumbnailUrl": "https://piped.example/cover.jpg",
  "uploader": "Alpha Artist",
  "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
  "uploaderAvatar": "https://piped.example/av.jpg",
  "videos": 3,
  "nextpage": "PT2",
  "relatedStreams": [
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000000",
      "title": "Track 0",
      "name": "Track 0",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 200,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    },
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000010",
      "title": "Track 1",
      "name": "Track 1",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 201,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    }
  ]
}"""
    val playlistAlbumPage2: String = """{
  "name": "Album - Single",
  "videos": 3,
  "nextpage": null,
  "relatedStreams": [
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000020",
      "title": "Track 2",
      "name": "Track 2",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 200,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    }
  ]
}"""
    val channel: String = """{
  "id": "UCchannelalpha01a0000000",
  "name": "Alpha Artist",
  "avatarUrl": "https://piped.example/av.jpg",
  "bannerUrl": null,
  "description": "an artist",
  "subscriberCount": 1234,
  "verified": true,
  "nextpage": null,
  "relatedStreams": [
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000000",
      "title": "Track 0",
      "name": "Track 0",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 200,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    },
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000010",
      "title": "Track 1",
      "name": "Track 1",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 201,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    }
  ]
}"""
    val streams: String = """{
  "title": "Alpha Song",
  "description": null,
  "duration": 181,
  "uploader": "Alpha Artist",
  "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
  "uploaderAvatar": null,
  "thumbnailUrl": "https://piped.example/cover.jpg",
  "relatedStreams": [
    {
      "type": "stream",
      "url": "https://www.youtube.com/watch?v=s00000010",
      "title": "Track 1",
      "name": "Track 1",
      "thumbnail": "https://piped.example/t.jpg",
      "uploaderName": "Alpha Artist",
      "uploaderUrl": "https://www.youtube.com/channel/UCchannelalpha01a0000000",
      "duration": 200,
      "views": 1,
      "subscribers": -1,
      "verified": false,
      "description": null
    }
  ],
  "audioStreams": [
    {
      "url": "https://piped.example/stream.webm",
      "format": "251",
      "quality": "160kbps",
      "mimeType": "audio/webm; codecs=\"opus\"",
      "itag": 251,
      "bitrate": 160000
    }
  ]
}"""
}
