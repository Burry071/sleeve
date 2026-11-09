# Playlist batch downloads

Date: 2026-11-04
Status: Implemented in 2.0.0-beta.11
Scope: Spotify/Audible metadata playlists and yt-dlp playlists in Sleeve 2.0

## 1. Problem

Downloading a playlist today produces N independent downloads with no batch
identity:

- Spotify: `DownloadDialogViewModel.downloadMetadataTracks` loops over every
  track, runs one or two yt-dlp searches per track, and enqueues each match as
  a separate `Task`. The search helper toasts "Fetching playlist info…" on
  every call (`DownloadUtil.getPlaylistOrVideoInfo`), so the user sees a popup
  per song, and the loop has no cancel handle.
- yt-dlp playlists: `PlaylistSelectionPage` enqueues one `Task` per selected
  entry, likewise without a batch identity.
- Every task posts its own progress notification and can only be canceled from
  its own card.

Result observed on device: the user force-stopped the app because there was no
way to stop the whole playlist, and no way to keep the remaining songs for
later.

Success criteria:

1. A playlist is one visible batch with one Stop and one Resume.
2. Per-song control still exists (cancel/resume/retry inside the batch).
3. Songs that were not downloaded are recoverable after Stop, app kill, or
   force-stop.
4. One notification per playlist instead of one per song.
5. Single (non-playlist) downloads behave exactly as they do today.

## 2. Approach

Chosen: **every song becomes a task immediately, and matching moves into the
task's existing `FetchInfo` step.**

Why:

- Stop/Resume/Retry become the existing `cancel()` / `restart()` per-task
  functions, applied batch-wide. There is no second lifecycle to cancel, which
  is the root of the force-stop behavior.
- Tasks are already persisted by the task-list backup, so a stopped batch
  survives relaunch without new storage machinery.
- The scheduler's three slots match up to three songs concurrently instead of
  the current strictly serial loop, so the queue starts sooner.
- yt-dlp playlists already work this way (one task per entry), so both
  providers converge on one model.

Rejected:

- **Batch registry + side matching loop.** Smallest engine change, but keeps
  two lifecycles, loses unmatched songs on app kill, and needs Stop to
  coordinate loop and queue.
- **Resolve the whole playlist before queueing.** Slowest, cannot show rows
  while matching, and recreates the serial bottleneck.

## 3. Data model

`download/Task.kt`:

```kotlin
@Serializable
data class BatchInfo(
    val id: String,        // random, unique per playlist download
    val title: String,     // playlist/album title
    val provider: String,  // "Spotify", "Audible", provider name used by yt-dlp flow
)
```

- `Task` gains `val batch: BatchInfo? = null`.
- New `TypeInfo.MetadataTrack(index: Int, provider: String, title: String,
  artist: String, album: String = "")`.
- `TypeInfo.id` for `MetadataTrack` returns `"metadata:$index"`.
- Metadata tasks use a synthetic `Task.url`:
  `"metadata://$provider/${batch.id}/$index"`. `Task.id` is derived from url +
  type id + preferences hash, so including the batch id guarantees uniqueness
  across playlists and repeat downloads of the same playlist.
- The synthetic URL never reaches yt-dlp. The resolved YouTube URL is stored in
  `VideoInfo.originalUrl` by the normal fetch step; the download stage already
  downloads from `info`, not from `Task.url`.

Backup compatibility: `PreferenceUtil` decodes task backups with
`ignoreUnknownKeys = true` and `encodeDefaults` is off, so `batch` adds nothing
to single-task backups, and older builds ignore it if they read a new backup.
`MetadataTrack` is a new subtype; old backups cannot contain it.

## 4. Matching pipeline

`DownloadUtil`:

- `getPlaylistOrVideoInfo(..., silent: Boolean = false, processId: String? = null)`
  - Toast only when `!silent`.
  - Pass `processId ?: playlistURL` to the yt-dlp execute call so a canceled
    search can be killed through the existing `destroyProcessById`.
- New `resolveMetadataTrack(task, track, preferences): Result<VideoInfo>`:
  1. Build the query `artist + title + "audio"`.
  2. Search `ytmsearch5` (silent); if the best ranked score is below 0.5, also
     search `ytsearch10` (silent) and rank both pools together.
  3. Rank with `GhostEngine.rankCandidates` using the existing
     `RANKING_STRICTNESS` preference.
  4. Resolve `best.url`, or `https://www.youtube.com/watch?v=<id>` when blank.
  5. `fetchVideoInfoFromUrl(resolvedUrl, preferences, taskKey = task.id)`.

`DownloaderV2.Task.prepare()` gains one branch, before the direct-media and
`fetchInfo()` paths:

```
if (type is TypeInfo.MetadataTrack) { resolveMetadata(); return }
```

`resolveMetadata()` mirrors the existing `fetchInfo()` lifecycle:

- Launch a lazy job; set `FetchingInfo(job, taskId = id)`; start the job.
- Success: write `info`, `viewState`, then `ReadyWithInfo`. The scheduler starts
  the download (same rule as the direct lane: never call `download()` from
  inside the job).
- Failure: `Error(DownloadErrorMessages.asFriendly(th), FetchInfo)`.
- Cancellation: `cancelImpl()` kills the yt-dlp search through
  `destroyProcessById(taskId)` because the search was registered under the
  task id, then cancels the job. The job must re-check the state before writing
  so a canceled match cannot resurrect the task.

Concurrency: matching counts as `FetchingInfo`, so `MAX_CONCURRENCY = 3`
applies. No extra semaphore is added; if YouTube rate-limits three parallel
searches, reduce concurrency for metadata tasks only.

## 5. Enqueue points

- `MetadataTracksPage`: rows get checkboxes and a select-all control, default
  all selected. The download button counts and enqueues only selected tracks.
- The sheet action becomes
  `DownloadMetadataTracks(provider, playlistTitle, tracks)` carrying only the
  selected rows.
- `downloadMetadataTracks` stops being a loop: it creates one `BatchInfo`,
  builds one `Task(TypeInfo.MetadataTrack(...), batch = batch)` per selected
  track, enqueues them, shows one toast ("N songs queued"), and closes the
  sheet. Matching happens later in the queue.
- yt-dlp `PlaylistSelectionPage`: tasks created by
  `TaskFactory.createWithPlaylistResult` are stamped with a `BatchInfo` at
  enqueue time. No other change to that flow.

## 6. Downloads UI

- `Filter` gains `Playlist`, displayed after `All`: All · Playlist ·
  Downloading · Canceled · Finished.
- Tasks with `batch != null` are excluded from All/Downloading/Canceled/Finished
  and appear only inside their batch card.
- New `PlaylistBatchCard`:
  - Header: playlist title, provider line, and progress summary
    ("7/12 done · 1 failed").
  - Actions are independent of each other: **Stop** is shown while any song is
    Idle, ReadyWithInfo, FetchingInfo, or Running; **Resume** is shown while any
    song is Canceled or Error. When both conditions hold, both buttons appear;
    Stop applies only to unfinished songs and Resume only to canceled/failed
    ones. No confirmation on either.
  - Body: expanded by default, collapsible by tapping the header. Songs are
    compact rows that reuse the existing `CardStateIndicator`, progress bar
    styling, and action button, so state animation, colors, and typography
    match current cards.
  - Tapping a song opens the same action sheet singles use (cancel, resume,
    delete, copy error report, copy URL).
  - Expansion state is per session.
- Empty states: the Playlist filter shows "No playlists yet"; All can be empty
  while a playlist is the only active download and shows its normal empty
  state, because batch songs live only inside the card.
- Dismiss (overflow menu on the card): confirm, cancel every unfinished song,
  remove those tasks, clear the batch tag from completed songs so they remain
  visible under Finished, and record the batch id as dismissed. Clearing the
  tag re-keys the task map (remove the batched task, insert an identical task
  with `batch = null` and the same `Completed` state; `Task.id` is unchanged
  because it excludes `batch`). Files are never deleted.

Batch operations:

- `stopBatch(batchId)`: `cancel()` every task in the batch whose state is Idle,
  ReadyWithInfo, FetchingInfo, or Running.
- `resumeBatch(batchId)`: `restart()` every task in the batch whose state is
  Canceled or Error.
- Both operate on the existing task-state map; no new scheduler logic.

## 7. Notifications

- A `Task` with `batch != null` posts no task notification of its own
  (progress, error, or finish).
- One batch notification per active batch, stable id derived from
  `batch.id.hashCode()`:
  - Ongoing while at least one song is active.
  - Text: playlist title + "x/y" completed count; progress bar uses aggregate
    completed fraction.
  - Removed when the batch has no active song (nothing Running or FetchingInfo),
    including when it is stopped or dismissed. Errors and canceled songs remain
    visible in the card without a system notification.
- Single tasks keep today's notification ids and behavior unchanged.
- Tapping the batch notification opens the app as the current notifications do.
- No Stop action on the notification in this change.

## 8. Errors

- A song that fails to match or download becomes an Error row inside the batch;
  the batch continues with the next song.
- Header summary counts failures; Resume retries failed and canceled songs.
- Playlist-level failures (empty/unresolvable playlist) keep the current sheet
  Error page and retry button.
- `DownloadErrorMessages.asFriendly` supplies user-facing text as today.

## 9. Persistence and restart

- Batch songs are ordinary tasks in `taskStateMap` and are covered by
  `PreferenceUtil.encodeTaskListBackup`. After force-stop or relaunch, the
  existing restore rule turns in-flight states into `Canceled` states; batch
  cards rebuild from the tasks and show Resume.
- Dismissed batch ids are stored in a new string-set preference
  (`BATCH_DISMISSED`), read by the Playlist filter.
- No new database table.

## 10. Tests

JVM unit tests:

- Grouping: tasks with the same `batch.id` produce one card; singles are
  excluded from the Playlist filter and batch tasks from other filters.
- `stopBatch` cancels only unfinished states; Completed and already Canceled
  tasks are untouched.
- `resumeBatch` restarts Canceled and Error tasks only.
- `Task.id` is unique for identical tracks with different batch ids and stable
  across process restarts for the same batch.
- Backup round-trip: `batch` and `MetadataTrack` survive encode/decode; a v1
  backup (no `batch`) decodes with `null`.
- Notification policy: batch tasks call no per-task notification; singles do.

On-device QA (Pixel 7, debug package first, then release build):

1. Spotify playlist: track list shows checkboxes; select 5 of 12; download.
2. Playlist filter shows one card with 5 rows; every row reaches a state;
   previously seen per-search toasts do not appear.
3. Stop mid-song; verify one notification disappears, rows become Canceled,
   partial file remains.
4. Force-stop the app; relaunch; card and canceled rows are still there.
5. Resume; the stopped song continues where possible; batch completes; card
   stays until dismissed; completed songs appear under Finished after dismiss.
6. Download a single URL; verify its notification, progress, cancel, and finish
   behave exactly as before.
7. yt-dlp playlist: selection flow unchanged apart from appearing as a batch
   card.

## 11. Files touched

- `download/Task.kt` — `BatchInfo`, `batch` field, `MetadataTrack`, type id.
- `download/DownloaderV2.kt` — metadata prepare branch, batch stop/resume/
  dismiss helpers, notification suppression.
- `download/TaskFactory.kt` — batch stamping for yt-dlp playlists.
- `util/DownloadUtil.kt` — silent/processId search params,
  `resolveMetadataTrack`.
- `util/NotificationUtil.kt` — batch notification.
- `util/PreferenceUtil.kt` — dismissed batch set.
- `ui/page/downloadv2/configure/DownloadDialogViewModel.kt` — enqueue-only
  metadata flow, batch creation.
- `ui/page/downloadv2/configure/MetadataTracksPage.kt` — checkboxes,
  select-all, selected count.
- `ui/page/downloadv2/configure/PlaylistSelectionPage.kt` — batch stamping.
- `ui/page/downloadv2/DownloadPageV2.kt` — Playlist filter, card list,
  dismissal.
- New `ui/page/downloadv2/PlaylistBatchCard.kt` — card and song rows.
- Strings in `res/values/strings.xml` and existing translations.

## 12. Risks and mitigations

- **Engine regression:** the metadata branch is reached only for
  `MetadataTrack`; URL, playlist, gallery, and command tasks keep their current
  paths. Unit tests assert existing prepare routing.
- **Notification regression:** suppression is keyed on `batch != null` only;
  singles keep current ids. Covered by a test and QA step 6.
- **Backup compatibility:** defaults + `ignoreUnknownKeys`; round-trip test
  including a legacy payload.
- **Canceled match racing a state write:** the match job re-checks state before
  writing, matching the existing cancel/remove guards.
- **YouTube throttling with three parallel matches:** monitor on device; if it
  appears, limit metadata tasks to one concurrent match.

## 13. Out of scope

- Instagram "empty media response" on Custom format fetch.
- Stuck "downloading" notification after a failed sheet fetch.
- Stop/Resume actions on the system notification.
- Deleting downloaded files, editing or reordering a playlist after it starts.
- Audible beyond using the same metadata-task pipeline it already shares with
  Spotify.
