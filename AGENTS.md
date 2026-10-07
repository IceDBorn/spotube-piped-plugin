# Agent guide

Notes for coding agents working in this repository. [README.md](README.md) covers usage and the build,
[docs/design.md](docs/design.md) the storage keys, the row cache and the mirror index, and
[docs/releasing.md](docs/releasing.md) the release workflows.

## Project

A Spotube Nightly plugin that provides the audio, metadata and scrobble roles through a Piped instance. It is
Kotlin Multiplatform. The shipped plugin is a JS bundle that runs in Zipline's QuickJS inside Spotube. The JVM
target exists only for tests.

## Commands

Java may be missing from `PATH`. `nix develop` provides JDK 21, so prefix Gradle calls with `nix develop -c`.

```sh
./gradlew :jvmTest :jsNodeTest       # offline tests on both targets
./gradlew ktlintCheck                # lint, no baseline
./gradlew ktlintFormat               # fixes most lint errors
./gradlew :packageProductionPlugin   # builds the bundle in build/distributions/
PIPED_INSTANCE=https://your.instance ./gradlew :jvmTest --tests '*LiveSmokeTest*'
```

CI (`.github/workflows/ci.yml`) runs `ktlintCheck`, both test targets and `:packageProductionPlugin` on every push
and pull request. Run all three before calling a change done.

Gradle skips an up-to-date test task and still prints BUILD SUCCESSFUL. When a test run is meant as evidence, for
example to show a test fails with its fix reverted, pass `--rerun-tasks`.

## Layout

Main code is in `src/commonMain/kotlin/dev/icedborn/spotube_plugin_piped/`:

| Package | Contents |
| --- | --- |
| `core` | `RealCoreAPI` (login, settings form, logout), account session, instance source, update checker |
| `client` | `PipedClient`, `AccountHttp`, the Piped models and the converters to Spotube types |
| `store` | `EntityStore`, local library, play history and its sync, `RowCache`, account sync, `StorageMigration` |
| `metadata` | The metadata APIs, `AlbumLookup`, charts, related artists, endless playback |
| `audio` | `RealPipedAudioAPI` and stream selection |
| `scrobble` | The scrobble role |
| `settings` | Settings form HTML and the stored settings |

The account sync in `store` is split into `PipedSavedLibrary` (the facade the APIs call), `AccountCache` (snapshot
and refresh), `MirrorWriter` and `MirrorRemoval` (save and unsave), `PlaylistMirror` (copies of local playlists),
`SavedSetResolver` (maps mirror rows back to albums and artists), `SavedBindings` and `HistorySync` (uploads
scrobbled plays to the history log and merges the plays of other devices, with its pure rules in `HistoryLog.kt`).

`src/jsMain/.../Main.kt` takes the host services from Zipline, runs `StorageMigration` before any role is bound,
binds the roles, then starts the account refresh when a session is stored.
`core/Time.kt` has one implementation per platform.

Tests mirror the package layout under `src/commonTest/`. The fakes in `fakes/`:

- `FakeHttp` matches routes in registration order. `countMatching` counts requests, for request budget tests.
- `FakePiped` is a stateful Piped instance with account playlists, paging, streams, search and channels.
  `unfetchable` holds the video ids a playlist add refuses.
- `FakeStorage` stands in for host storage. `failPutsFor` makes writes under a key prefix fail.
- `store/AccountHarness` wires the account side over a `FakePiped`. `store/HistoryFixture.kt` holds the helpers of
  the history tests.

`vendor/maven` holds the Spotube Gradle plugin, which is not published anywhere. Do not edit it.

## Rules

- **QuickJS catch and finally.** A suspend call inside a `catch` of a `try` that also has a `finally` breaks
  `:packageProductionPlugin` with "unconsistent stack size", while the JVM and Node tests still pass. Use
  `runCatching` and handle the failure after it, or move the `try` into its own function. A suspend call nested in
  the arguments of another call inside such a `try` fails the same way, and the body of `Mutex.withLock` is such a
  `try`, so assign the result to a `val` first. Build the bundle after any change to suspend code with `try`,
  `catch` and `finally` or inside `withLock`.
- **Failed fetches.** A blank body, `{}` or a body without the expected key is a failed fetch, never an empty list.
  Decode with `decodeKeyed`.
- **Ids.** The host keys list items by id and fails on an empty or repeated one. In lists, drop failed items
  instead of emitting a placeholder.
- **Scopes.** Common code takes an injected `CoroutineScope`. Only `Main.kt` picks a dispatcher. Keep state in
  instance fields, not top-level mutable globals.
- **Storage.** Add every new key to the table in `docs/design.md`. A change to a stored shape needs a
  `StorageMigration` step and a `SCHEMA_VERSION` bump. Account writes go through bindings stamped with the
  account, so a switched account never writes into another account's playlists.
- **Request counts.** Tests that hit the network assert the request count with `countMatching`. README lists the
  costs of a like, an unsave, a refresh, an album lookup and a scrobbled play; update it when they change.
- **Visibility.** Everything is `internal` except `main`. Use imports, not fully qualified names.
- **Size.** Files stay under 500 lines and functions under 80. ktlint allows 140 characters per line.
- **Comments.** At most 2 lines each, and only where the code does not explain itself. The `CachedRows` KDoc is
  the one longer block. Rules that span files go in `docs/design.md`.
- **Logs.** Log lines must not contain search text, tokens or passwords.

## Docs

Change `README.md` and `docs/design.md` in the same commit as the behaviour they describe: storage keys, cache
rules, request costs and limits.

## Commits

Conventional commits, lowercase and imperative: `fix(audio): pick the muxed row by itag 18`. Types in use are
`feat`, `fix`, `docs`, `test`, `refactor` and `bump`. Scopes follow the packages (`audio`, `core`, `client`,
`store`, `metadata`, `settings`, `scrobble`) plus `ci` and `readme`. A version bump is
`bump(version): 0.0.4 -> 0.0.5`.
