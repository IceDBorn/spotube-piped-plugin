# Releasing

How a release is cut, and how the commit list in its body is generated.

## Cutting a release

1. Bump `pluginVersion` in `gradle.properties` and push. The push also starts a
   nightly, which step 2 replaces.
2. Run the `Release` workflow by hand on `main`. It tests, builds, and tags
   `pluginVersion` as the release, then refreshes the nightly.

## The rule

| Release | Commits listed |
| --- | --- |
| First stable | Every commit up to and including the tag. |
| Stable | Only commits since the previous stable tag. |
| Nightly | Commits since the latest stable tag. |

## The script

`.github/scripts/release-notes.sh <stable|nightly> <version>` prints the list, one
`- <subject> (<short hash>)` per line. Both workflows call it and write the output
to the body file.

It picks the previous tag by version rather than by ancestry:

- A tag counts as stable only when it is shaped `X.Y.Z`. `nightly`, `piped-*`
  and prereleases are ignored.
- A stable release takes the highest stable tag below its own version.
- A nightly takes the highest stable tag.

It then lists the range with `--right-only --cherry-pick`, which skips commits the
tag side already has under a different hash. That matters because `0.0.1` points
at `fb641cc` while `main` carries the rewrite `7917213` of the same change, and
`0.0.1` is not an ancestor of `main`. With no previous tag it falls back to the
full log. An empty result prints "No changes since X.Y.Z." so the body is never
blank.

## The nightly refresh

The version bump push starts a nightly before the new tag exists, so that build
would list the wrong range. `release.yml` ends with
`gh workflow run nightly.yml --ref main` to rebuild it once the tag is in place.
The nightly's concurrency group cancels the stale run if it is still going, and
otherwise the refresh just replaces its release. This needs `actions: write`
alongside `contents: write` in the release job, and it re-runs the Gradle test
suite, so a release costs a second build.

## Known quirk

A nightly built on the exact stable commit is still titled `X.Y.Z-nightly.N` with
the next patch, so it outranks the stable release while carrying the same code.
It is replaced by the next real change.
