# spotube-piped-plugin

Spotube plugin that provides both the audio and the metadata role through a Piped instance you choose. It needs no
Google account, and a Piped account is optional.

## Install

Stable builds are on the [Releases page](https://github.com/IceDBorn/spotube-piped-plugin/releases). The `nightly`
prerelease there is rebuilt from every push to `main`.

To build from source:

1. Build the plugin (see [Build](#build)).
2. In Spotube, open Settings -> Manage plugins -> Install a Plugin -> Install from file or URL. If you built with
   `--serve`, paste one of the URLs it prints.

## Setup

There is no default instance. Open Settings -> Manage plugins, choose Piped as the metadata plugin, the audio plugin
or both, then press the login button on Piped. The settings form has two tabs.

The **Instance** tab:

- **Instance**: the URL of any instance from the [TeamPiped list](https://github.com/TeamPiped/Piped/wiki/Instances),
  or your own.
- **Playback instance** (optional): resolve audio from a different instance than the one serving metadata.
- **Charts region**: *Auto* guesses the country from the system time zone. Countries without charts use Global.
- **Update channel**: which GitHub release the update check offers. *Auto* follows the installed build, so a
  nightly install stays on nightlies and a stable install stays on stable releases. *Stable* only ever looks at the
  latest release, *Nightly* at both and offers the newer of the two.

After switching from Nightly to Stable, no update is offered until a stable release is newer than the installed
nightly. To go back sooner, reinstall a stable build from the
[Releases page](https://github.com/IceDBorn/spotube-piped-plugin/releases).

The **Sign in** tab is optional. Sign in to, or register on, the instance. Saved tracks, albums and artists then sync
to the "Spotube - Favorites", "Spotube - Albums" and "Spotube - Artists" playlists on that account, and a copy stays
on the device for offline use. Without an account, everything stays local.

A session belongs to the instance it was created on. Changing the instance signs you out.

## Features

- **Audio**: searches YouTube Music first and falls back to plain YouTube when the match is weak.
- **Metadata**: tracks, albums, artists, playlists, search and a Home screen.
- **Home, For you tab**: recently played, your artists, radio mixes from recent plays, similar artists, albums and
  playlists of your most played artists, your playlists and saved albums.
- **Home, Charts tab**: YouTube Music charts for your region, read from the playlists of the "YouTube Music Global
  Charts" channel, because Piped has no charts endpoint.

Spotube passes plugins no play history, so the plugin keeps its own. It records every track it resolves audio for,
so a track that Spotube preloads counts as played even if you skip it.

Spotube caches Home until it restarts, so a region change shows up after a restart.

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
./gradlew -PpluginVersion=0.0.2-nightly.7 :generatePluginJson --rerun-tasks :packageProductionPlugin
```

## Test

```sh
./gradlew :jvmTest :jsNodeTest
```

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
library's `Logger`. Response bodies are cut to 200 characters. Log lines never contain search text, tokens or
passwords.

## Known limitations

- Spotube has a Region setting (Settings -> Language & Region), but the host does not pass it to plugins yet.
- Piped has no related-artists endpoint. Artist pages show no related artists, and the Home "Fans also like" section
  is built from the artists in radio mixes.
