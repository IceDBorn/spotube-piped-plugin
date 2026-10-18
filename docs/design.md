# Design notes

These notes cover the parts of the plugin whose rules span several files: the row cache, the storage keys, the
mirror index, storage sync, the history log and endless playback.

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

`schema.version` holds the storage schema, currently 4. `StorageMigration` runs once at start, before any service
reads storage. Each step moves the version to its own number once it and its writes succeed. A failed step stops
the run and runs again at the next start, without repeating the steps before it:

- Version 1 canonicalizes artist ids, turns owner stamps into arrays, stamps the account snapshot with its owner or
  deletes it when no owner is known, and deletes the old `piped-acct-*` playlist records.
- Version 2 changes the `CachedRows` format and deletes every `playlist.rows:` and `album.rows:` key, so the caches
  refill from page 1.
- Version 3 deletes the `album.none:` keys, which kept a no-album verdict per artist and title, and stamps the
  plain `none` values in `track-album:` keys with the current time, so they expire like new verdicts.
- Version 4 gives the device-wide library, local playlists, history and storage sync state to one profile, see
  [Profiles](#profiles).

Bare `saved.playlist:` and `mirror.playlist:` bindings from before account stamps are adopted at start, at each
login and at each account refresh, not by a version step. A binding whose uuid is in the account's playlist
listing gets the account's stamp. One that is missing stays, since another account may own the copy, and
`binding.miss:<key>` records the account that lacked it. While the signed-in account has not checked a bare
`mirror.playlist:` binding, edits and deletes of that playlist fail rather than skip the copy. Signed out, or with
the listing unreachable, the bindings wait. A new binding for the key or a playlist delete removes its miss record.

Every start then deletes the `mirror.playlist:`, `binding.miss:mirror.playlist:`, `mirror.pending:` and old
`mirror.playlistName:` keys of local playlists that no longer exist. The playlists of every profile keep their
bindings. It skips this when a playlist list does not decode, so a corrupt list cannot take every binding with it.
Last, it evicts the least recently used cache entries beyond the caps below and deletes expired `unresolvable:`
latches.

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
| `piped.library` | Saved track, album, artist and playlist ids of the profile |
| `piped.playlists` | Locally created playlists with their track ids |
| `history.tracks` | Play history that Home and endless playback read, the last 200 tracks |
| `history.plays` | Every counted play on this device as `[videoId, at]`, newest first, the last 5,000. Home's personal rows read it when storage is off |
| `radio.stations` | The last 3 endless playback stations, each with its returned songs, pool and skips |

`piped.library`, `piped.playlists`, `history.tracks`, `history.pending`, `history.seen`, `history.plays` and every
storage sync key below except `storage.device` and `storage.support` are per profile, see [Profiles](#profiles).

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

### Storage sync

| Key | Value |
| --- | --- |
| `piped.storage.sync` | `OFF`; absent means on |
| `storage.support` | `StorageVerdict` for one account |
| `storage.device` | This device's id, 8 letters and digits |
| `profiles.legacy` | Account stamp, or `out`, of the profile that reads the bare keys |
| `storage.own:<suffix>` | Entries authored here, per suffix (`lib`, `set`, `pl`, `h0`-`h15`, `agg`, `t0`-`t15`) |
| `storage.dirty` | Kinds waiting for a push |
| `storage.versions` | This device's manifest versions |
| `storage.manifest.dirty` | The manifest still needs a push |
| `storage.registered` | This device is in `spdevices` |
| `storage.registry` | Cached `spdevices` |
| `storage.etag:<file>` | Etag of the last read of a registry or manifest |
| `storage.remote:<device>:<suffix>` | Another device's file with its manifest version |
| `storage.imported` | Account stamp of the finished import |
| `storage.pl.touched` | Local playlists changed since the last push, with times |
| `storage.hist.cursor` | Generation of the open play segment |
| `storage.dict.pending` | Track ids waiting for the dictionary |
| `storage.imported.history` | Account stamp of the finished history import |

## Profiles

Each account on the device and the signed-out state have a profile. An account's id is the 64-bit FNV-1a hash
of its `instance|username` stamp in hex, and the signed-out profile's id is `out`. The profile named in
`profiles.legacy` reads the bare keys, and every other profile reads `p<id>.<key>`. A missing or unreadable
`profiles.legacy` gives no profile the bare keys, which then stay in storage unread.

`EntityStore` resolves the per-profile keys (`isProfileKey`). A coroutine pinned with `forAccount` or `inProfile`
uses that profile, and any other call uses the profile of the session at that moment. The account refresh, the
storage sync and the history log uploads run pinned to their account, so work that ends after an account switch
writes only into its own account. The writes of `LocalLibrary` and `PlayHistory` pin the active profile, so a
read and its write never land in two profiles.

A switch changes only which profile the session resolves to. Nothing is cleared, so a returning account finds
its own files, marks and tombstones as it left them. Saves and plays made signed out stay in `out` and never
reach an account.

Schema 4 assigns the bare keys. Signed in, the account takes them, and `out` gets a copy of `piped.library`,
`piped.playlists` and `history.tracks`. When the old `storage.owner` named another account, the bare sync keys
are dropped and the account imports again. Signed out, `out` takes them, and the bare sync keys,
`history.pending` and `history.seen` are dropped. `storage.owner` is then removed.
A rerun after a failed write keeps the owner the first run chose.

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

## Storage sync

When the instance provides Piped storage and the setting is enabled, `StorageSync` syncs saved library items,
settings, and local playlists through account storage files instead of the playlist mirrors.

### Files and naming conventions

Storage files belong to the account and use short names:

- `spdevices`: Device registry (`DeviceRegistry`) listing registered device ids and their registration timestamps.
- `sp<device>m`: Device manifest (`DeviceManifest`), mapping kind suffixes (`lib`, `set`, `pl`) to version numbers.
- `sp<device><suffix>`: Synced data documents (`SyncDoc`) authored by a device (`lib` for library items, `set` for
  settings, `pl` for local playlists, `h0` to `h15` for play segments, `agg` for folded play counts, `t0` to `t15`
  for the track dictionary). Each document holds a list of `LwwEntry` records.

Each device generates a random 8-character lowercase alphanumeric id stored in `storage.device`.

### LWW entries and resolution

Every entry is `{"k": key, "a": at, "v": value}`, where `at` is epoch milliseconds and a missing or null `v` is a
removal. Library keys are `t:<id>`, `a:<id>`, `r:<canonical artist id>` and `p:<id>` with `v = 1`. Settings keys
are `piped.region` and `piped.library.placeholder` with the stored value. A playlist key is the local playlist id
with the whole `StoredPlaylist` as value. A file whose `v` field is not 1, or that lacks its list, is a failed fetch.

`mergeLww` picks, per key, the entry with the highest `at`. A tie goes to the larger device id.

### Imports at time zero

The first sync of an account imports the local library, the snapshot's saved sets, the two settings and every
local playlist with `at = 0`, so any later change on any device wins over them. `storage.imported` records the
account stamp. Imported settings never override another device; only a change made in Settings (`at > 0`) does.

### Manifest and versions

`storage.versions` holds a counter per kind, raised after each confirmed push and published in `sp<device>m`. When
the counter is missing, after a reset or an account switch, the device starts from its published manifest, so it
never reuses a number another device has cached.

At each refresh, the device reads the registry and each other device's manifest, fetching only the data files whose
version differs from the one cached in `storage.remote:<device>:<suffix>`. Each file that arrives is stored at once.
The manifest etag is kept only when every changed file was stored, so the next refresh fetches the rest. A pull
applies a kind only when one of its files was stored since the process last applied it, or the account or the
device list changed.

### Device registry compare-and-swap

`spdevices` is the only file more than one device writes. A device adds itself on its first sync: it stats the
file, reads it when it exists, appends its id and puts it back with the stat's etag in `x-last-etag` (no etag when
the file is missing). An "Invalid etag" answer starts the round again, at most 3 times. A registry that does not
decode is never overwritten, since that would drop every other device.

### Own files

Every other file has one writer, its device. A push stats the file and puts it with that etag. A conflict means an
earlier put landed late, so the device stats once more and retries once.

### Dirty marks

A change marks its kind in `storage.dirty` and schedules a push 30 seconds later. Each mark also bumps an in-memory
counter for the kind. A push reads the counter before it uploads and clears the mark only when the counter has not
moved, so a change made during the upload stays marked for the next push.

### Local playlists and the size cap

A playlist change records its id and time in `storage.pl.touched`. The entry is built from the stored playlist at
push time. A pull overwrites a touched playlist only with another device's newer edit, which would win once the
touch is pushed. A cover image over 16,384 characters is sent as an empty string, and a device that receives an
empty image keeps its own. A pulled playlist is added to the library list, and a pulled delete removes it. Ids with
the local `piped-` prefix stay out of the library kind, so only the playlist kind decides whether a local playlist
is listed.

A file holds at most 500,000 bytes (`MAX_FILE_BYTES`; the backend allows 512,000). `fitToCap` drops the oldest
removals first. When the live entries alone do not fit, the file is not sent and its kind stays marked.

### Library and refresh in storage mode

With storage active, saved state comes only from `piped.library`. A save or unsave records an entry and writes the
local library, and an unsave skips the mirror ownership check. The first sync adds the items of the last playlist
snapshot to the local library, so items saved on other devices through the mirrors stay saved. A pull applies only
other devices' winning entries.

The account refresh skips the three mirror playlists and their resolution, still caches the account's own
playlists, walks and merges the history log once before the history import, and leaves it alone after that. It
runs the storage sync before it writes a snapshot with empty saved sets, so the import still reads the old ones. A
failed storage sync does not stop the refresh.

### Plays and the track dictionary

A scrobbled play the device counted becomes an event `e:<videoId>:<at>` in the open segment. A segment holds 1,000
events and the ring has 16. `storage.hist.cursor` holds the generation of the open segment, a number that grows by
one per segment opened. The open segment is `h<generation % 16>`, and each event stores its generation as its value.

Before a segment is reused, its events fold into `c:<videoId>` entries of `agg` as `[count, lastAt]`, and the `w`
entry of `agg` records the newest generation folded. A pull skips a device's events at or below that device's `w`.
So a device that holds the new `agg` and an old copy of the emptied segment counts each play once. One that holds
the old `agg` and the emptied segment undercounts until the new `agg` arrives, and `combinePlays` keeps the higher
count it showed before. The fold writes `agg`, empties the segment, then moves the cursor. When a write fails, the
play goes into the open segment and the next play retries the fold, which skips generations `w` already covers.
`agg` is pushed before the segments and keeps the 5,000 most played folded tracks.

The first sync per account writes the device's `history.tracks` counts into `agg` as `b:<videoId>` entries at
time 0, after one last merge of the history log. Plays recorded before that import only count locally, since the
import includes them. When the device's own manifest already lists `agg` or a segment, the device published plays
for this account before its sync state was dropped, for example by a reinstall or the schema 4 migration, and
`history.tracks` holds them. The import then reads those files back into `storage.own:` and sets
`storage.hist.cursor` to the newest generation of their events, at least one above `w`, instead of writing a new
baseline that would count them twice. A failed read leaves the import to the next refresh.

A pull counts, per track, every device's events above its `w` and folded counts plus the largest baseline of any
device. Baselines overlap, because the history log already spread plays between devices. The 400 tracks with the
newest plays, minus those without known metadata, raise `history.tracks` through `combinePlays`: a count or play
time only grows, so plays counted from audio resolves alone stay.

The dictionary has 16 shards `t0` to `t15`, chosen by the sum of the id's character codes. Saves of tracks, plays
and the history import queue ids in `storage.dict.pending`. At the next push each queued id adds `t:<videoId>`
with the cached track and `al:<videoId>` with a found album id. Only changed shards are marked, and a shard keeps
its newest entries up to 120,000 bytes. A pull fills empty `track:` and `track-album:` keys from other devices'
entries, each entry once per start.

### Detection

Detection runs inside the account refresh when the account has no verdict or the last check is older than 24 hours
(`SUPPORT_RECHECK_MS`). It reads `/config` for `"s3Enabled"`, then stats `spdevices` with the session. A failed read
keeps the previous verdict. The verdict in `storage.support` counts only for its instance and username.

## Personal Home rows

For you shows On repeat, Forgotten favorites, a time-of-day mix and Daily mix 1 to 3. With storage active they read
the play events of every device of the account from the store, and the folded counts for totals. Otherwise they
read `history.plays`. `PlaySource` picks the source at each build.

- On repeat: plays of the last 30 days by count, at least 2 each.
- Forgotten favorites: tracks with 5 plays or more and none in the last 60 days.
- Time-of-day mix: plays of the last 30 days in the current local window of 4 hours (late night 0-4, early
  morning 4-8, morning 8-12, afternoon 12-16, evening 16-20, night 20-24). Synced plays use this device's offset.
- Daily mixes: the plays of the last 30 days split into sessions at gaps over 30 minutes. The most played artist
  left starts a group and takes up to 4 more artists that share at least 2 sessions with it. A group needs 3
  played songs. Its played and liked songs take turns with a radio of its top track, up to 25, without songs
  played in the last 6 hours. Without the radio it shows with 10 known songs.

A row needs 5 tracks and shows up to 20, a daily mix up to 25. Rows built together share one request for the
same radio. Ids without stored track details drop out, and nothing is fetched for them.

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

### Profile history

`history.tracks` belongs to the profile, so plays merged from two accounts never land in the same history.

## Endless playback

`EndlessPlayback` in `metadata/EndlessPlayback.kt` answers `recommendationsBasedOnTracks`, which Spotube Nightly
calls only for endless playback. The host calls it when the last song of the queue starts, with a limit of 20. The
seeds are the whole queue while it holds 5 songs or fewer, and otherwise 5 songs picked at random from the whole
queue, played ones included. The host appends the batch without removing repeats, and an empty batch ends
playback. No skip or progress signal reaches plugins.

### Stations

A station is the state of one endless playback session. `radio.stations` holds the 3 most recently updated ones,
newest first.

| Field | Holds |
| --- | --- |
| `updatedAt` | The time of the station's last batch |
| `queued` | The seeds the host sent and the songs the station read seed mixes from, the last 200 |
| `read` | The seeds whose mix the station read, the last 200 |
| `pool` | Up to 200 songs not yet returned, each with a score |
| `batches` | Each batch's time and songs, with their version keys, up to 500 songs |
| `skipped` | The last 100 returned songs the listener skipped, with their version keys |

A batch continues the live station that queued or returned the most of its seeds, the most recently updated one
on a tie. A single seed continues only a station that already queued it. Any other song played on its own, from a
Home card or a search, starts a fresh station and reads its mix. A song that started a station still continues it.
A station is live for 24 hours after its last batch. Seeds that no live station knows start a new one.
A save drops stations that are no longer live and keeps the 3 newest. Past 500 returned songs the oldest batch
goes, so a session of 25 batches or more may repeat songs from its start. A station takes up to about 50 KB.

`radio.stations` belongs to the device, like `history.tracks`. `RadioStations` keeps the list in memory after the
first read. When a write fails, the memory copy stays in use, so the station lasts until the plugin restarts.

### Building a batch

1. The station checks its last batch for skips and adds the seeds to `queued`.
2. The seeds' tracks, from play history or the track cache, give the seed artists, the seeds' title tags and their
   version keys. Other versions of the older queued songs stay out too.
3. The batch reads the mixes of up to 2 seeds the station has not read and that were not skipped, the most
   recently played first, then liked ones. It stops early once the pool holds 2 batches of usable songs. The mix's
   own row for its seed adds to the seed artists, tags and versions. A mix that fails to load leaves its seed
   unread, so a later batch tries it again.
4. The batch picks songs from the pool by score, at most 2 per artist, with 3 songs between two songs of one
   artist, counted across the end of the previous batch. Every 4th slot takes a familiar song first.
5. When the artist rules leave the batch short, the batch reads the mixes of up to 2 pool songs by artists other
   than the seed artists, one artist each, and picks again.
6. Pool songs fill the slots still empty, without the artist rules.
7. When the batch is still short, the seed artist's catalog from a music_songs search joins the pool, and the batch
   picks and fills again.
8. The picks leave the pool and become the station's newest batch.

Each mix adds to the pool by reciprocal rank fusion. The song at rank r, counted from 1, adds `weight / (20 + r)`
to its score. A seed mix weighs 1, the mix of another artist's song 0.7 and the catalog 0.3. Queued and returned
songs never enter the pool.

A song is usable when it is not a seed, a queued song or a returned song, was not played in the last 6 hours, and
is not another version of a seed, a queued song, a returned song or a song played in the last 6 hours.

The version key is the artist key plus the base title. The base title drops bracketed parts and a " - " suffix and
keeps letters and digits, so "Song", "Song (Remastered)" and "Song - 2011 Remaster" share one key. A leading
"Artist - " part that names the track's own artist is dropped first, since uploads carry it as a prefix. A title
whose brackets or " - " suffix name a live, remix, cover, sped up, slowed, nightcore, karaoke or session take is
usable only when a seed's title has the same tag.

The artist key is the first artist's name without the " - Topic" suffix, in letters and digits. It comes from names
rather than channel ids, since one artist's songs arrive from a topic channel, a band channel and fan channels.

### Familiar songs

A familiar song is one the listener liked or played, by a seed artist, an artist the station returned or an artist
in the pool. It must not have been played in the last 2 days, and one that looks like a music video is left out,
since the station stays on YouTube Music songs. Each play counts 1 and a like counts 3. Familiar songs take only
every 4th slot, so they fill at most a quarter of a batch, and they never fill other slots. The batch scans only
the last 200 saved songs.

### Skips

A song of the last batch counts as skipped when it was not played after the plugin returned the batch, while a
later song of that batch was. The host asks for a batch when the last song starts, so the songs before it are over.
The check reads only the last batch, because the history keeps 200 plays and an older batch could look skipped once
its plays age out. A skipped song never seeds a mix, and each skip halves the scores of its artist's songs in that
station.

With scrobbles off, every audio resolve counts as a play, preloads included, so fewer skips show.

### Failures

The batch skips a pool entry whose track left the `track:` cache, since the station keeps only ids. A failed
catalog fetch leaves the batch with what the mixes gave. Any other failure logs `radio queue from <id> failed` and
returns an empty batch, which ends playback.
