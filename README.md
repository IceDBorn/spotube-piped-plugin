# spotube-piped-plugin

Zipline plugin for Spotube. Provides the audio and metadata roles through a
Piped instance (default: https://pipedapi.kavin.rocks). No account needed.

## Features

- Audio: YouTube Music search, with a plain-YouTube fallback for weak matches
- Metadata: tracks, albums, artists, playlists, browse
- No default instance: open Settings, choose Piped, save the URL of any instance
  from the [TeamPiped list](https://github.com/TeamPiped/Piped/wiki/Instances)
  (or your own). Login is optional and just adds your account on that instance
- Optional separate *playback instance*: set it in the same settings page to
  resolve audio from a different instance than the one serving metadata

## Build

Needs a JDK 21.

```sh
nix run . # writes spotube-plugin-piped.smplug to the current directory

# or without nix:
./gradlew :generatePluginJson --rerun-tasks :packageProductionPlugin
```

The bundle lands in `build/distributions/plugin-production.smplug`.

## Install

Settings -> Plugins -> install from file or URL.
