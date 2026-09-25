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
