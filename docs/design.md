# Design notes

These notes cover the parts of the plugin whose rules span several files: the row cache, the storage keys, the
mirror index and the history log.

## Row cache

`RowCache` (`store/RowCache.kt`) keeps the ordered rows of one playlist or album as a `CachedRows` record and
extends it page by page through the Piped `nextpage` token. Public playlists and albums use it through
`PlaylistRows`, account playlists through `AccountRows`, and the account refresh writes its walks in the same
format.

Every writer keeps these rules:

- A fetch that returns a blank body, or a body without `relatedStreams`, failed. It is never an empty list.
- A blank `nextpage` is not an end. The cache stays open, and the next read restarts the chain from page 1.
- An empty page after rows is ambiguous, never an end.
- `complete` is set only when the final page, the one with a null token, converted at least one row.
- Album track numbers are raw page positions, so rows that did not convert leave gaps.
- `rawCount` counts every row the pages held, converted or not.
- `lastVerifiedAt` is the time of the last page 1 anchor.
- `listedTotal` is the row total that page 1, or for account playlists the account listing, reported when the
  cache was written, or -1.

A read anchors the cache against page 1 at most every 2 minutes while the cache is open and every 15 minutes once
it is complete. The anchor keeps the cache when the ids of page 1 and the cache agree over their shared length and
the listed total is unknown or fits: at least `rawCount`, and for a complete cache equal to `rawCount` or
`listedTotal`. Otherwise the fresh page 1 replaces the cache. When the cached rows already cover the request, the
anchor runs in the background and the read serves the cached rows at once.

A dead or blank token, a failed fetch or an empty page restarts the chain from page 1, once per read. A failed or
throttled page 1 ends the read with the cached rows. The read walks the new chain separately and swaps it in only
once it holds as many rows as the cache did or reaches the end of the list, so a page that fails part-way cannot
shorten a list. The end is read from the token, not from `complete`: a list whose last page held only rows that
did not convert ends with a null token and fewer rows, and it still replaces the cache.

An empty page 1 never replaces cached rows, whatever total it lists; the anchor only stamps the cache. So a list
emptied on the web keeps showing its old rows. An empty list that page 1 proved is not fetched again until its
anchor is due. The account refresh writes its walk through `cacheWalk`, which blanks a cache only when the walk
saw no rows and the account listing reports none either, since one empty page 1 proves nothing about the listing.

Reads do not walk again a chain that ended on rows that did not convert. Besides the usual prefix check, the anchor
restarts such a chain when the listed total exceeds both `rawCount` and `listedTotal`. On an instance that lists no
total, rows added on the web after the dead ones stay missing. A cache whose rows all failed to convert is kept
while page 1 still converts nothing.

All reads and writes of one key run under `EntityStore.withRowsLock`, so a background anchor and a foreground
extend never interleave.

## Storage keys

The host gives each plugin one key-value store. `EntityStore` (`store/Store.kt`) keeps an in-memory copy of up to
8,000,000 JSON characters and evicts the least recently used entries from memory first.

`schema.version` holds the storage schema, currently 3. `StorageMigration` runs once at start, before any service
reads storage. Each step moves the version to its own number once it and its writes succeed. A failed step stops
the run and runs again at the next start, without repeating the steps before it:

- Version 1 canonicalizes artist ids, turns owner stamps into arrays, stamps the account snapshot with its owner or
  deletes it when no owner is known, and deletes the old `piped-acct-*` playlist records.
- Version 2 changes the `CachedRows` format and deletes every `playlist.rows:` and `album.rows:` key, so the caches
  refill from page 1.
- Version 3 deletes the `album.none:` keys, which kept a no-album verdict per artist and title, and stamps the
  plain `none` values in `track-album:` keys with the current time, so they expire like new verdicts.

Bare `saved.playlist:` and `mirror.playlist:` bindings from before account stamps are adopted at start, at each
login and at each account refresh, not by a version step. A binding whose uuid is in the account's playlist
listing gets the account's stamp. One that is missing stays, since another account may own the copy, and
`binding.miss:<key>` records the account that lacked it. While the signed-in account has not checked a bare
`mirror.playlist:` binding, edits and deletes of that playlist fail rather than skip the copy. Signed out, or with
the listing unreachable, the bindings wait. A new binding for the key or a playlist delete removes its miss record.

Every start then deletes the `mirror.playlist:`, `binding.miss:mirror.playlist:`, `mirror.pending:` and old
`mirror.playlistName:` keys of local playlists that no longer exist. It skips this when `piped.playlists` does not
decode, so a corrupt list cannot take every binding with it. Last, it evicts the least recently used cache entries
beyond the caps below and deletes expired `unresolvable:` latches.

### Settings and account

| Key | Value |
| --- | --- |
| `piped.instance` | Main instance URL, without a trailing slash |
| `piped.playback.instance` | Optional instance for audio only |
| `piped.account` | `PipedAccount`: instance, username, session token |
| `piped.region` | Chart region code or `GLOBAL`; absent means follow the system |
| `piped.update.channel` | `AUTO`, `STABLE` or `NIGHTLY` |
| `piped.library.placeholder` | `ALWAYS`, `WHEN_EMPTY` or `OFF` |
| `piped.form.theme` | Settings form theme |

### Local library

| Key | Value |
| --- | --- |
| `piped.library` | Saved track, album, artist and playlist ids on this device |
| `piped.playlists` | Locally created playlists with their track ids |
| `history.tracks` | Play history that Home reads, the last 200 tracks |

### Caches

| Key | Value | Limit |
| --- | --- | --- |
| `track:<videoId>` | Converted `MetadataTrack` | 5,000 entries |
| `streams:<videoId>` | `/streams` response | 2,000 entries |
| `list:<playlistId>` | Page 1 of a public playlist or album | 500 entries |
| `album.chain:<playlistId>:<token>` | A continuation page walked while resolving an album | 500 entries |
| `channel:<channelId>`, `channel.at:<channelId>` | Channel page and fetch time | Re-fetched after 12 hours |
| `track-album:<videoId>` | Album playlist id of a video, or `none@<time>` when it has none | `none` for 7 days |
| `charts.index`, `chart.at:<playlistId>` | Chart playlists and fetch times | Index 7 days, charts 6 hours |
| `playlist.rows:<uuid>`, `album.rows:<playlistId>` | `CachedRows` | 200 each; account playlists exempt |
| `cache.access` | Access counter per evictable key, flushed every 100 touches | |

Eviction treats a key without an access stamp as used at the time of the eviction.

### Account sync

| Key | Value |
| --- | --- |
| `acct.state` | `AccountCacheState`: playlists, saved sets, refresh time, owner |
| `saved.playlist:<kind>` | `StoredPlaylistId` of the mirror for `track`, `album` or `artist` |
| `saved.rep:<kind>:<id>` | The mirror row video that stands for a saved entity |
| `saved.owner:<kind>:<id>` | Array of `instance\|username` stamps of accounts that saved it |
| `mirror.index:<kind>` | Mirror index, see below |
| `mirror.walk:<kind>` | A mirror walk cut at 20 pages, continued by the next refresh |
| `mirror.playlist:<localId>` | `StoredPlaylistId` of the account copy of a local playlist |
| `mirror.pending:<localId>` | The copy failed to create or fill; the next refresh retries |
| `binding.miss:<key>` | Array of account stamps whose listing lacked a bare binding |
| `resolutionRetry:<kind>:<stamp>:<videoId>` | Failed resolutions in a row |
| `unresolvable:<kind>:<stamp>:<videoId>` | Time a video was latched after 3 failures; cleared after 1 hour |
| `saved.gone:<kind>:<stamp>` | Per mirror row, the ids an unsave confirmed while that row could not be mapped |
| `history.playlist` | `StoredPlaylistId` of the history log, see below |
| `history.pending` | `PendingPlays`: the plays waiting for upload and the ids uploaded since the last merge |
| `history.seen` | `SeenLog`: the log rows this device has merged, for one account and playlist |

Every binding carries the account it belongs to. A binding of another instance or username is ignored for writes,
so switching accounts never writes into the previous account's playlists.

## Mirror index

Saved tracks, albums and artists are written through to three account playlists: "Spotube - Favorites",
"Spotube - Albums" and "Spotube - Artists". Piped deletes playlist rows by position, so a write needs the row order.
`mirror.index:<kind>` keeps it:

```json
{
  "instance": "https://pipedapi.example",
  "username": "alice",
  "playlistId": "0b6f...",
  "videos": ["dQw4w9WgXcQ", "", "kJQP7kiw5Fk"]
}
```

`videos` lists every row's video id in playlist order; a row whose video id could not be read is `""`. The index is
trusted only when its account and playlist id match and the playlist listing reports exactly `videos.size` rows.
Otherwise the writer walks the whole mirror.

- A refresh writes the index when its walk proved the end of the mirror and drops it otherwise, unless a save or
  unsave changed the mirror since the walk began. Then it leaves the index alone.
- A save writes the index with the pushed rows appended, and a failed save drops it.
- An unsave writes the index without the deleted positions and with any repointed rows appended, but only when
  every POST was confirmed and the rows came from a complete view. Otherwise it drops the index.
- A newly created mirror starts with an empty index.

One mutex per kind orders all index writes, so the index follows every POST in order. Each save or unsave write
also bumps a per-kind generation and drops `mirror.walk:<kind>`. A refresh records the generation before its walk
and writes the index, the partial walk and a proven mirror state only when the generation is unchanged, so a walk
that overlapped a write never overwrites it. A refresh checks the account after its walks and again inside each
mirror lock. When the account was switched out, it stops before the snapshot, the pending retries and the
resolution; playlists it handled before the check keep what it wrote for them. The listing count is the only
check against edits made on the web, so a reorder that keeps the row count goes unnoticed until the next refresh
rewrites the index.

The background resolution of albums and artists takes a ticket from a counter. Resolutions run one at a time, and
one that waited behind another is skipped when a newer refresh took a later ticket, since that refresh has fresher
rows. The resolution merges its sets against the snapshot read before the walk, so a save or unsave made during
the walk or the resolution survives.

Each artist row costs one request. Each album row gets at most 17 of the list's 40 requests: the streams fetch,
then a search and 7 first pages for each of 2 queries, with chain pages drawn from the same share. So no single
row can spend the budget and starve the rows behind it. A lookup that spends its whole share counts as a failed
resolution, and 3 failures in a row latch the row for an hour. A lookup cut short by earlier rows had less than a
share, so running out does not count against it.

A "none" verdict needs every candidate chain proven to end without the video. One lookup fetches at most six
pages per candidate chain. A deeper chain is cached page by page under `album.chain:`, and only fetched pages count
against the limit, so the next refresh reads the pages already walked without requests and reaches further. Only a
page that proves something is cached. An empty page or a blank token is a throttle, and caching one would leave
the chain unproven on every later refresh. A chain that is never proven caches no verdict; the lookup counts as a
failed resolution, so 3 in a row latch the row.

An unsave is confirmed past a surviving row that is latched or, in the album mirror, carries a "none" verdict of
any age, since such a row could otherwise block every unsave. A fresh verdict gets the same treatment, because it
expires and a later lookup can then map that row to the unsaved id. `saved.gone:<kind>:<stamp>` records, for that
account and that row, the ids the unsave confirmed. A later resolution does not claim a recorded id from that row,
but another row that maps to it, such as one added by a save on the web, still brings it back. A save on this
device lifts the id, and a complete walk without the row drops the row's record.

## History log

With an account signed in, the play history syncs through one more account playlist, "Spotube - History". It is a
log. `HistorySync` in `store/HistorySync.kt` adds one row at its end for every scrobbled play the device counted,
so the row order is the play order. The pure rules are in `store/HistoryLog.kt`.

### Uploads

`ScrobbleHistory` hands a play to `HistorySync.onPlay` only when the device counted it, which means that
`PlayHistory.record` returned true or the audio path had already counted the same listen. Signed out, the sync
does not queue the play. Signed in, the play joins `history.pending`, which carries the account's stamp. The sync
does not read a queue with another account's stamp, and the next write replaces it. The queue keeps the newest 300
plays.

One add request carries one video. Piped answers a batch add with success when it added any of the videos and
skips the others without naming them, so only a single-video add tells which play landed. `postOutcome` sorts the
answer three ways:

- `CONFIRMED` is a JSON object without `error`. The play leaves the queue and its id joins `sent`.
- `REFUSED` is an object with `error`. Piped answers 200 this way for a missing playlist and for a video it cannot
  fetch. A run that has not read the listing reads it first, and a changed log id gets the play again. Otherwise
  the play waits 6 hours, and the fourth refusal drops it. Three refusals in a row end the run.
- `UNCLEAR` is anything else, such as no answer, a status that is not 2xx, `{}` or a body that is not an object.
  The play stays queued and the run ends, because the add may have landed.

An upload does not read the listing while `trusted`, the log id the listing last showed, equals the binding. It is
unset at start and after an unclear answer. When the write of the queue fails after a confirmed add, uploads stop
until the next start, because another run would send the same play again.

### Log identity

The log is the account playlist named "Spotube - History". When the listing shows several, every device takes the
one with the smallest id, so two devices that created a log in the same moment settle on the same one. The rows of
the other stay where they are. `history.playlist` binds the id to the account, and a changed id clears `sent`.

### Joining a log

The log carries only plays made after it exists. A device does not upload the history it had before, so that
history stays on the device. A stored `history.seen` for the account and playlist marks the device as joined, and
it decides how a merge reads new rows. A device that creates the log, or finds it without rows in the listing of
an upload or in the walk of a refresh, stores a `history.seen` with no rows, so every row that follows is a new
play. A device that first meets a filled log stores its `history.seen` at the first merge, which reads the rows as
plays from before it joined. After a delete on the web, the next scrobbled play creates the log again under a new
id, and it holds only the plays from then on.

### Merges

Every account refresh walks the log, keeps it out of `acct.state`, the row cache and the `track:` cache, and
merges it when the walk proved the end:

1. `appendedRows` finds the rows added since `history.seen`. The log only grows at its end, so the old rows are
   the longest start of the walk that `history.seen` holds in the same order, whatever a trim removed in between.
   A sequence of rows that repeats across a trim can hide new rows. The rule never counts a row twice.
2. `withoutOwn` drops one row per id in `sent`, the rows this device added itself.
3. The merge writes `history.seen` first, then clears `sent`, then writes `history.tracks`. A failure between the
   writes loses the new plays instead of counting them twice.

The merge rule depends on whether the device had a `history.seen` for the log:

- With one, every new row is a play since the last merge. Each adds 1 to its track's count, and the tracks go on
  top of the history, the newest row first.
- Without one, the rows are plays from before the device joined. Tracks the device lacks go below its history, and
  a count only rises to the number of rows read. Repeating this changes nothing, so a device that switches
  accounts and comes back does not count the log again.

An entry keeps the device's own `lastPlayedAt`, and a track new to the device gets 0. A merge is not a play on
this device, and `lastPlayedAt` guards the replay window of `PlayHistory.record`. A merged track takes the device's
copy, then the `track:` cache, then the log row.

### Order of writes

`syncLock` lets one upload run, merge or trim work at a time, and a merge that finds it taken does nothing. Every
confirmed add bumps a generation counter. A refresh reads the counter before its walk and skips the merge when it
changed, so a merge never reads an own row that `sent` does not hold yet. Every play sets a flag that makes a
running upload go around once more.

### Trim

After a walk that proved the end and held more than 500 rows, the refresh removes the oldest row up to 20 times in
the background and stops at the first removal the instance does not confirm. Then it uploads the waiting plays.
Piped removes by stored position and refuses a position that holds no row. An add that overlapped a removal can
leave position 0 without one, so the trim tries positions 1 and 2 after a refusal at position 0. With three empty
positions at the head the trim finds no row, and the log grows past the cap.

### Device history

`history.tracks` is one history per device, not per account. The plays merged from two accounts land in the same
history.
