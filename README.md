# spotube-piped-plugin

Spotube Nightly plugin that provides the audio, the metadata and the scrobble role through a Piped instance you
choose. It needs no Google account, and a Piped account is optional. The roles are independent: pick any combination
of them.

## Install

Stable builds are on the [Releases page](https://github.com/IceDBorn/spotube-piped-plugin/releases). The `nightly`
prerelease there is rebuilt from every push to `main` that changes more than the docs.

To build from source:

1. Build the plugin (see [Build](#build)).
2. In Spotube Nightly, open Settings -> Manage plugins -> Install a Plugin -> Install from file or URL. If you
   built with `--serve`, paste one of the URLs it prints.

## Setup

There is no default instance. Open Settings -> Manage plugins, choose Piped as the metadata plugin, the audio plugin,
the scrobble plugin or any combination of them, then press the login button on Piped. The settings form has three
tabs: Login, Instance and Settings.

The **Instance** tab:

- **Piped instance** (required): the URL of any instance from the
  [TeamPiped list](https://github.com/TeamPiped/Piped/wiki/Instances), or your own.
- **Playback instance** (optional): resolve audio from a different instance than the one serving metadata.

The **Settings** tab:

- **Charts region**: *Auto* guesses the country from the system time zone. Countries without charts use Global.
- **Update channel**: which GitHub release the update check offers. *Auto* follows the installed build, so a
  nightly install stays on nightlies and a stable install stays on stable releases. *Stable* only ever looks at the
  latest release, *Nightly* at both and offers the newer of the two.
- **Library playlist**: the playlist the plugin adds to Library -> Playlists. *Always show Recently played* is the
  default, see [Known limitations](#known-limitations).

After switching from Nightly to Stable, no update is offered until a stable release is newer than the installed
nightly. To go back sooner, reinstall a stable build from the
[Releases page](https://github.com/IceDBorn/spotube-piped-plugin/releases).

The **Login** tab is optional. Sign in to, or register on, the instance. Saved tracks, albums and artists then sync
to the "Spotube - Favorites", "Spotube - Albums" and "Spotube - Artists" playlists on that account, and a copy stays
on the device for offline use. With Piped as the scrobble plugin, the play history syncs through a
"Spotube - History" playlist, see [History sync](#history-sync). Without an account, everything stays local.

While an account is signed in, the button on Piped reads Logout, and pressing it opens the same form on the
Settings tab. The Login tab then shows who is signed in, with **Log out** and **Done**. Done closes the form and
keeps the session.

A session belongs to the instance it was created on. Changing the instance signs you out. When the form was opened
with the login button while a session was stored, Done closes it and then checks the session in the background.
Only a 401 or 403 with an error body clears it; when the instance is down or you are offline, the session stays.
Done on the form opened from Logout keeps the session without a check.

## Features

- **Audio**: searches YouTube Music first and falls back to plain YouTube when the match is weak.
- **Metadata**: tracks, albums, artists, playlists, search and a Home screen.
- **Scrobbling**: takes the scrobble role and writes what Spotube Nightly reports into the play history that Home
  reads, so history fills even when Piped is not the audio plugin, as long as the tracks come from Piped's
  metadata. With an account signed in, the history syncs between your devices, see
  [History sync](#history-sync).
- **Home, For you tab**: recently played, your artists, radio mixes from recent plays, similar artists, new songs,
  albums and playlists of those similar artists, albums and playlists of your most played artists, your playlists
  and saved albums. Played artists go through the same artist search as related artists, described below. If the
  search fails, the played artist stays as it is.
- **Home discovery**: "New songs for you", "Albums you might like", "Playlists you might like" and "Playlists for
  fans of" list music by the "Fans also like" artists. New songs reuse the radio mixes and cost no requests. The
  album and playlist rows read the top 4 of those artists, and each costs 1 channel fetch, shared with artist
  pages, plus 1 album search and 1 playlist search. "Playlists for fans of" costs 1 YouTube Music playlist search
  for each of the top 2 artists. Home keeps a section for 30 minutes, so scrolling all of For you costs at most 14
  more requests in that time.
- **Related artists**: artist pages and the Home "Fans also like" section list artists taken from YouTube Music
  radio mixes, because Piped has no related-artists endpoint. A radio row names the channel that uploaded the
  video, so each name goes through one artist search, cached by name until restart. The plugin lists the artist
  whose name matches exactly and drops uploaders with no match, such as labels and fan channels.
- **Radio queue**: endless playback and the Home radio sections read the YouTube Music radio mix of a track. A mix
  seeded from a music video lists videos, so a seed whose title looks like a video costs one song search, cached
  until restart, and the mix is read from the matching song. Rows titled like videos, with "Official Video",
  "(Audio)", "Lyric Video" or an "Artist - " prefix naming the uploader or an artist of the mix, are dropped. A mix
  row names the YouTube channel of its video, which can be a fan or band channel rather than the YouTube Music
  artist. A row whose song is already cached, such as the seed from a song search, keeps the cached YouTube Music
  artist, and a channel named after that artist maps to it for the other rows, until restart. This costs no
  requests. Rows of unmapped channels keep the YouTube channel.
- **Endless playback**: each endless playback session is a station that remembers the songs it returned, so a
  later batch never repeats them. A batch also leaves out its seeds, songs played in the last 6 hours and other
  versions of those songs, and live, remix, cover, sped up, slowed, nightcore, karaoke and session takes unless a
  seed's title has the same tag. Songs rank by how high they sit in the mixes the station has read. An artist gets
  at most 2 songs per batch with 3 songs between them, and only a batch the other artists cannot fill drops that
  rule. Every 4th slot takes a song you liked or played by an artist of the station, if you have not played it
  in the last 2 days and it is not a music video. A song you skip lowers its artist for the rest of the station,
  and the station never reads its mix. A batch reads at most 4 mixes, 1 request each. Up to 2 come from seeds the
  station has not read yet, and up to 2 more from songs by other artists when the batch is still short. The first
  batch of a radio from one song usually costs 2 requests, and later batches 1 to 3. A seed that looks like a
  music video adds one song search.
  When the mixes cannot fill a batch, the seed artist's songs fill it for one /streams fetch and one song search.
  Home and related artists read the mix as it is.
- **Home, Charts tab**: YouTube Music charts for your region, read from the playlists of the "YouTube Music Global
  Charts" channel, because Piped has no charts endpoint.

The plugin keeps its own play history on the device, from two sources:

- **Scrobbles.** With Piped selected as the scrobble plugin, Spotube Nightly reports a play once a track has really
  played, after half its length or 240 seconds, whichever comes first. From the first such report on, the scrobbles
  are the history, and a track the player only preloaded is not counted.
- **Audio resolves.** Until the first scrobble arrives, every track the plugin resolves audio for counts as a play.
  The player preloads the next track and resolves again on a seek, so this over-counts: a skipped track counts, and
  so does a seek more than a minute after the track started.

A scrobble that follows an audio resolve of the same track, within the track's duration plus ten minutes (20
minutes when the duration is unknown), counts once, so one listen is not two plays. The switch to scrobbles lasts
for the session only and is not stored. A scrobble for a track Piped cannot resolve, for example a track that came
from another metadata plugin, is dropped and the audio role keeps feeding the history for it.

Spotube Nightly caches Home until it restarts, so a region change shows up after a restart.

### History sync

With Piped as the scrobble plugin and an account signed in, the play history syncs between your devices through a
"Spotube - History" playlist on the account. The playlist is a log. Every scrobbled play the device counts adds
one row at its end. The device does not count a scrobble less than a minute after the last counted play of the
same track, and such a scrobble adds no row. The plugin does not upload plays it counts from audio resolves alone,
without a scrobble.

- **New plays only.** The first scrobbled play creates the playlist. The plugin does not upload the history a
  device had before, so that history stays on that device.
- **Other devices.** Every account refresh reads the playlist. Each row another device added moves that track to
  the top of the history and adds 1 to its count. A row holds no time, so the device orders the plays of other
  devices by when it read them.
- **First read.** The first time a device reads a filled playlist, the tracks it lacks go below its own history,
  and a count only rises to the number of rows read. Devices do not share counts from before they joined, so two
  devices can show different counts for the same track.
- **Limits.** The playlist keeps 500 rows, and a refresh removes at most 20 of the oldest rows above that. Up to
  300 plays wait on the device while it cannot reach the instance. The plugin tries a video the instance refuses 4
  times, at least 6 hours apart, and then drops it from the upload. The play stays in the history of the device.

Home shows the merged plays after a restart of Spotube Nightly, which caches Home until then.

Library -> Playlists does not list the playlist. Like every Piped playlist, anyone who has its id can read it, and
a plugin version from before this feature lists it as a normal playlist. Removing rows on the web, or deleting the
playlist, does not clear the history on any device. The next scrobbled play creates a deleted playlist again, and
the playlist then holds only the plays made from then on. The history on a device belongs to the device, not to
an account, so plays merged from two accounts land in the same history.

### Audio streams

Before it offers a stream, the plugin sends a HEAD request to check that the instance's proxy serves it, because
some proxies answer every audio row of a video with an empty 403, which the host would save as a 0-byte file. The
probe is a HEAD because the proxy ignores `Range`, so a GET would download the whole file. A row counts as served
only when the answer is a 2xx with an `audio/` or `video/` Content-Type. After a proxy served a video, that video
skips the check for 2 minutes. Only a 403 or 404 counts as a refusal. Any other answer, such as a timeout, a 429, a
5xx or a 2xx without a media Content-Type, is no verdict. The plugin then probes the video's other rows, and when
none is served it offers the rows it could not disprove, or falls back as below, without remembering the video.

A video whose richest row serves costs 1 probe. A refused or inconclusive one costs one probe per row: the richest
probe first, then the rest in parallel. The host picks one row of what it is given, with no failover.
So a row with no verdict is dropped as soon as another row proves served: a blip on the richest row can cost its
bitrate, and the player is never handed a URL the plugin could not confirm.

When the proxy serves no audio-only row, the plugin falls back to the muxed row with itag 18. That row is a small
mp4 video file that carries the audio track, so the track plays, and a download of it holds a picture track too.

### Account requests

Saved tracks, albums and artists are written to three playlists on the account: "Spotube - Favorites",
"Spotube - Albums" and "Spotube - Artists". The plugin keeps an index of each one's rows, and while the account's
playlist listing reports the same row count as the index, a write needs no walk of the playlist:

- Liking a track costs 2 requests: the playlist listing and the add. Saving an album or artist also fetches the
  album or channel and checks a few videos, to pick a playable one that stands for it.
- Unsaving costs the listing plus one request per deleted row. When the account cannot confirm that a row is gone,
  the unsave fails and the item stays saved, so you can try again.
- When the row count changed on the web, the next write walks the playlist once. A reorder on the web that keeps
  the count is not noticed until the next refresh.

The account refresh runs at login, at start, and every 15 minutes while the app is used. It walks 3 playlists at a
time and reads at most 20 pages of each saved-items playlist; a longer one continues on the next refresh. Mapping
the album and artist rows back to albums and artists runs in the background, with at most 40 lookup requests per
list per refresh.

The history log, see [History sync](#history-sync), costs 1 request per scrobbled play, the add. The first play
after a start, or after an answer that proved nothing, also reads the playlist listing. A video the instance
refuses costs one more listing when that upload had not read it. The first play on an account without the
playlist costs the listing, the create and the add. Each refresh reads the playlist with 1 request. Above 500
rows it also removes up to 20 rows at 1 request each, or up to 3 each once a write collision has left the first
position without a row.

Opening an album from a track waits up to 4 seconds for the album lookup, which costs at most 17 requests. After 4
seconds the plugin shows a placeholder while the lookup finishes in the background, and opening the album again
shows it.

[docs/design.md](docs/design.md) lists the storage keys, the index format and the row cache rules.

## Build

Needs JDK 21. `nix develop` provides one.

```sh
nix run .                    # writes spotube-plugin-piped.smplug to the current directory
nix run . -- --serve [port]  # same, then serves only that file over HTTP (default port 8000)

# without nix:
./gradlew :generatePluginJson --rerun-tasks :packageProductionPlugin
```

Without nix, the bundle is written to `build/distributions/plugin-production.smplug`.

The version comes from `pluginVersion` in `gradle.properties`. That is the last stable release; to build a
nightly, pass the next patch with a prerelease suffix, which orders above that release and below the next one:

```sh
./gradlew -PpluginVersion=0.0.6-nightly.7 :generatePluginJson --rerun-tasks :packageProductionPlugin
```

## Test

```sh
./gradlew :jvmTest :jsNodeTest
./gradlew ktlintCheck    # lint; ktlintFormat fixes most findings
```

CI (`.github/workflows/ci.yml`) runs the lint, both test targets and `:packageProductionPlugin` on every push and
pull request. The bundle build is part of it because Zipline's QuickJS rejects some JS that Node runs, so a change
can pass every test and still fail to package.

The offline tests run on both the JVM target and the JS target. The JVM target exists only for the tests; the
shipped plugin is the JS bundle. The tests use fake host HTTP and storage APIs with canned Piped responses
inlined in `src/commonTest/`, so they need no network. Running them on JS catches the differences in regex
flavour, `Long` arithmetic and string handling that the JVM does not show.

`LiveSmokeTest` checks search, album playlists and channels against a real instance. It is JVM-only, because it
uses `java.net` and JUnit assumptions. It is skipped unless `PIPED_INSTANCE` is set:

```sh
PIPED_INSTANCE=https://your.instance ./gradlew :jvmTest --tests '*LiveSmokeTest*'
```

## Logging

Failed requests, sync errors and Home sections that fail to build go to the host log through the interfaces
library's `Logger`. Response bodies are cut to 200 characters. The plugin never puts search text, tokens or
passwords into a log line itself, but it logs the message of a failed host request as is, and that message may hold
the request URL with its query. Log lines also contain the account's username and instance URL, for example on
sign-in and when a write to the account fails, so check a log before sharing it.

## Known limitations

- Spotube Nightly has a Region setting (Settings -> Language & Region), but it does not pass it to plugins yet.
- Spotube Nightly does not pass its theme colors to plugins, so the settings form cannot follow the app theme. The
  form has its own light/dark toggle, remembered between openings, and does not switch itself when you change the
  theme in Spotube.
- Spotube Nightly has no settings button for plugins yet, only Login and Logout. The plugin opens its settings
  form from that button, so while an account is signed in, the button reads Logout but opens the settings. To
  sign out, press **Log out** on the Login tab. Spotube Nightly also clears the plugin's web view data on every
  press, which does not affect the Piped session.
- Spotube Nightly sends scrobbles to plugins since its commit `fab60107` of 2 October 2026. On an older build,
  selecting Piped as the scrobble plugin has no effect. Play history keeps coming from the tracks the plugin
  resolves audio for, see [Features](#features), and the plugin uploads nothing to the history log.
- Spotube Nightly does not ask plugins for updates yet, so the update channel has no effect and no update is
  offered in the app. To update, install from one of these URLs again:
  - stable: `https://github.com/IceDBorn/spotube-piped-plugin/releases/latest/download/spotube-plugin-piped.smplug`
  - nightly: `https://github.com/IceDBorn/spotube-piped-plugin/releases/download/nightly/spotube-plugin-piped.smplug`
- Spotube Nightly on Android shows no play or add-to-queue buttons on Home sections made of tracks, such as
  "Recently played" and "Because you listened to", so those tracks cannot be played from Home there. Desktop shows
  the buttons.
- Spotube Nightly shows its "Liked tracks" card in Library -> Playlists only when the plugin returns at least one
  playlist. To keep the card reachable, the plugin adds a generated "Recently played" playlist, holding the last 50
  tracks you played. With no history yet it shows a "Liked Songs" playlist of your saved tracks instead, and with
  neither it adds nothing. The Library playlist setting controls the entry: *Only when there are no playlists* adds
  it only while you have no other playlist, and *Off* removes it, which also hides the Liked tracks card when no
  other playlist exists. The entry cannot be edited or deleted.
