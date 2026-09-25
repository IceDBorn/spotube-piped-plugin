# spotube-piped-plugin

Plugin for Spotube. Provides the audio and metadata roles through a
Piped instance of your choice. No account needed.

## Features

- Audio: YouTube Music search, with a plain-YouTube fallback for weak matches
- Metadata: tracks, albums, artists, playlists, browse
- No default instance: open Settings -> Manage plugins, choose Piped as metadata, audio or both, press the login button on Piped and save the URL of any instance
  from the [TeamPiped list](https://github.com/TeamPiped/Piped/wiki/Instances)
  (or your own). Login is optional for metadata sync
- Optional separate *playback instance*: set it in the same settings page to
  resolve audio from a different instance than the one serving metadata
- Home has two tabs:
  - *For you*: recently played, your artists, radio mixes based on recent
    plays, similar artists, albums and playlists of your most played artists,
    your playlists and saved albums
  - *Charts*: YouTube Music charts for your region, from the playlists of the
    "YouTube Music Global Charts" channel (Piped has no charts endpoint)
- Play history for *For you* is logged by the plugin itself, from every track
  it resolves audio for (Spotube passes plugins no history). A track Spotube
  preloads counts as played even if skipped
- Charts region: set it in the plugin settings (Instance tab). *Auto* guesses
  the country from the system time zone; countries without charts use Global.
  Spotube caches Home until it restarts, so a region change shows after a restart

## Future updates

- Spotube has a Region setting (Settings -> Language & Region), but the host
  does not pass it to plugins yet: `SystemInformationAPI.getLocale()` is
  hardcoded to `"en-US"`. Once it returns the user's setting, make *Auto* read
  the country from `getLocale()` first and keep the time zone as the fallback.
  The place to change is `RegionSetting.detected()` in `Region.kt`.

## Build

Needs a JDK 21.

```sh
nix run . # writes spotube-plugin-piped.smplug to the current directory
nix run . -- --serve [port] # same, then serves only that file over HTTP (default port 8000)

# or without nix:
./gradlew :generatePluginJson --rerun-tasks :packageProductionPlugin
```

The bundle lands in `build/distributions/plugin-production.smplug`.

## Install

Settings -> Manage plugins -> Install a Plugin -> Install from file or URL.
With `--serve`, paste one of the printed URLs.
