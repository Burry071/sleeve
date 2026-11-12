# beta.4 diff review — runtime, lifecycle, Compose failure modes

Baseline: `HEAD ba79e156` plus the uncommitted beta.4 working tree, snapshot at 2026-11-11 15:32
(32 files, +1140 / -1695). Review is read-only; nothing in the tree was edited.

Scope: the nine areas named in the brief. Style, naming and visual taste are out of scope unless
they carry a runtime consequence.

---

## 1. Conclusion

**One must-fix before shipping** (correctness, restore path): a swapped pick is persisted as
"pending" while the only record of the file it replaces lives in memory, so a process death between
"swap applied" and "swap finished" leaves a song permanently stuck in `Replacing…` with no in-sheet
way out, and — if the death lands after the completion write — a duplicate file and a duplicate
history row that nothing will ever clean up.

Everything else found is non-blocking: one scheduler race newly reachable through the network
callback, one Compose layout block that grows outside the scrollable list, one unsaved dialog state,
one network-registration latch, and one UI promise ("Waiting for Wi-Fi") that the restore path does
not keep.

Tests re-run on this tree, not taken on trust: `testGenericDebugUnitTest --rerun-tasks
--no-build-cache` → 157 tests, 0 failures (48 tasks executed, no cache hit);
`python -m unittest discover -s scripts` → 84 tests, OK.

---

## 2. Must fix (1)

### 2.1 A pending swap survives process death; the file it is supposed to replace does not

**Location**
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:531` — `pendingSwapCleanup` is a
  per-instance `ConcurrentHashMap`.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:581-605` — `cleanupSwappedFile` is
  the only caller of `confirmSelection()` and the only place the parked path is consumed.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:238-268`, esp. `260-262` —
  `enqueueFromBackup` rewrites `ReadyWithInfo` to `Canceled(Download)` and leaves `state.match`
  untouched.
- `app/src/main/java/com/junkfood/seal/download/Match.kt:56-96` — `pending` / `previousSelectedUrl`
  are `@Serializable` fields of `MatchResult`.
- `app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt:108-113` — `acknowledgeMatches`
  deliberately skips `match.pending`.

**Problem.** `Task.State.match` is serialized into the MMKV task backup
(`PreferenceUtil.encodeTaskListBackup`, `PreferenceUtil.kt:450-451`; `Task.State.match` at
`Task.kt:118`), so `pending = true` and `previousSelectedUrl` survive a restart. The parked old
path (`pendingSwapCleanup`, line 531) does not — it is process-local. On restore the task comes back
as `Canceled`, `match.pending` is still true, and no code path clears it: `acknowledgeMatches` skips
pending picks (line 111), `MatchReviewSheet` disables every radio for a pending row
(`MatchReviewSheet.kt:179-186`, `enabled = !pending`), and `confirmSelection()` is only reached from
`cleanupSwappedFile`, which requires a *completed* download.

**Impact.** Reachable in ordinary use, not just a crash: applying a swap on a metered network parks
it as `ReadyWithInfo` with no foreground service, so the process is a prime background-kill target
while the swap waits.

- The song is stuck at `Replacing…`, its candidates cannot be chosen, and `Accept all` is *enabled*
  (no swap differs from the stored pick) yet changes nothing — it closes the sheet and the review
  entry is still there on the next frame. The only exit is to leave the sheet and resume the batch.
- If the death lands between `downloadState = Completed(newPath)` (`DownloaderV2.kt:927`) and
  `cleanupSwappedFile` finishing, the old file and its Room row are never deleted and the pending
  pick is never promoted: a duplicate file, a duplicate `DownloadedVideoInfo` row, and a match that
  still points at the *new* URL while claiming to be pending forever.

**Evidence.** `MatchTransactionTest` "the pending window survives a json round-trip" proves the flag
is persisted; `enqueueFromBackup` (238-268) proves nothing re-reads it; the map at 531 is the only
holder of the old path; `cleanupSwappedFile` (581-605) is the only consumer. The two halves of the
"swap in flight" state therefore have different lifetimes by construction.

**Fix direction.** Make the invariant "pending ⇒ a swap is in flight" true after restore: in
`enqueueFromBackup`, cancel the pending pick when the restored state is not `ReadyWithInfo`
(`match?.takeIf { it.pending }?.cancelPending()` for `Canceled`/`Error`), or — if the swap must
survive — persist the parked path in `Task.State` and re-run the cleanup on startup. Either way, a
pending pick must not be able to outlive the record it depends on.

---

## 3. Worth considering (5)

### 3.1 The scheduler now has a second, unsynchronized entry point

**Location.** `DownloaderV2.kt:209-211` (`onNetworkRestored` launches a new coroutine on
`Dispatchers.Default`), `:650` (`kickFromNetwork = doYourWork()`), `:656-683` (`doYourWork`),
`:852-853` (`Task.download` guard), `:982` (`downloadState = Running`).

**Problem.** Until this diff the scheduler ran from one `snapshotFlow` collector, which serialized
every tick. `kickFromNetwork` starts a second coroutine that calls `doYourWork` directly. The pick
is check-then-act with no lock: `countRunning()` → `firstOrNull { ReadyWithInfo }` → `download()`,
and `download()`'s own guard (`if (downloadState != ReadyWithInfo) return`, line 853) is also
non-atomic — the state is only rewritten at line 982.

**Why it matters.** Two ticks landing in that window both pass the guard and both launch
`downloadVideo` for the same task and the same output path. This is not hypothetical: the comment at
`DownloaderV2.kt:858-861` records exactly this failure ("ran downloadVideo twice per task … observed
on hardware") as the reason for the current LAZY/state-before-start shape.

**Suggestion.** Serialize the tick (a `Mutex` or `Semaphore(1)` around `doYourWork`), or make the
network kick feed the existing collector instead of calling the scheduler from a second coroutine.

### 3.2 Swap results are rendered outside the scrollable list

**Location.** `MatchReviewSheet.kt:128-129` (the `LazyColumn` with `weight(1f, fill = false)`),
`:221-245` (one status line per result), `:247-254` (the accept-all hint), `:256` (action row). The
sheet's content column (`ModalBottomSheetM3.kt:44-50`) has no `verticalScroll`.

**Problem.** Only the candidate list scrolls. Everything else — title, hint, one 2-line status row
per swapped song, the accept-all hint, the button row — is in the fixed part of the column. The
results block has neither a cap nor its own scroll.

**Impact.** After a batch-wide apply, the fixed block grows by roughly 36 dp per song. With a dozen
or so swapped songs on a ~640 dp-tall screen the `LazyColumn` is measured with 0 dp and the action
row is laid out past the sheet's bottom edge — the same "buttons out of reach" failure the weight
change was meant to remove. At 200% font it takes far fewer rows.

**Suggestion.** Move the summary and the per-song status lines into the `LazyColumn` as `item {}`
blocks at the top, or wrap the results in `heightIn(max = …)` + `verticalScroll`.

### 3.3 The parked metered action is not saved state

**Location.** `DownloadPageV2.kt:269` (`remember { mutableStateOf<Action?>(null) }`),
`QuickDownloadActivity.kt:79` (an Activity field), `AndroidManifest.xml:99-102`
(`MainActivity android:configChanges="orientation"` only).

**Problem.** Any configuration change that recreates the host — font size, dark mode, fold/unfold,
and rotation, because `screenSize` changes together with `orientation` and is not declared — drops
the parked action. Neither host can restore it: the slot holds a lambda (`QuickDownloadActivity`) or
a data class (`DownloadPageV2`), and neither is `rememberSaveable`.

**Impact.** The user taps Download on cellular, the dialog is up, the device rotates: the dialog
disappears, nothing is queued, and no message explains it. For `DownloadMetadataTracks` this also
throws away the fetched track list.

**Suggestion.** Keep the pending action in the ViewModel (it already survives the host), or persist
an action token that the host can re-post; `QuickDownloadActivity` at minimum needs the same
`configChanges` treatment or a saveable nonce.

### 3.4 `NetworkPolicy.init` latches "registered" before registration succeeds

**Location.** `NetworkPolicy.kt:47-48` (`if (registered) return` / `registered = true`),
`:59` (`registerDefaultNetworkCallback`), `:92-95` (failure only logged).

**Problem.** `registered` is set before the call that can throw. If `registerDefaultNetworkCallback`
fails once (a `SecurityException`, or `TooManyRequestsException` when the process is near the
100-callback limit), the object never retries and `metered` stays frozen at its `init` value for the
life of the process.

**Impact.** A device that starts on Wi-Fi keeps `metered = false` after switching to cellular, so
downloads start on mobile data with no dialog and no preference — the one outcome the gate exists to
prevent. Low probability, silent when it happens.

**Suggestion.** Set `registered = true` only after a successful registration, and on failure fall
back to re-reading `isActiveNetworkMetered` (with a short TTL) instead of trusting a stale flag.

### 3.5 "Waiting for Wi-Fi" promises a resume the restore path does not perform

**Location.** `VideoCardV2.kt:557-561` (`ReadyWithInfo` → `download_waiting_wifi` when the gate
blocks), `DownloaderV2.kt:260-262` (restore maps `ReadyWithInfo` → `Canceled(Download)`),
`:668-683` (`doYourWork` only picks `Idle` / `ReadyWithInfo`).

**Problem.** The new pill tells the user the queued song will start when Wi-Fi returns. That holds
only while the process lives: after a restart the same task is `Canceled` and nothing auto-resumes
it. The parked queue is also the state with no foreground service, so it is the most likely to be
killed.

**Impact.** A queue the user was told to leave alone quietly turns into a list of canceled rows.

**Suggestion.** Either restore gate-parked tasks as `ReadyWithInfo` (the scheduler will then honour
the gate and start them when it opens) or drop the promise from the copy.

---

## 4. Noted only

- `App.kt:155,276` — `onDownloadServiceDestroyed` clears the *started* latch (correct) but
  `isServiceRunning` is still not cleared on destroy; `startService()` will skip `bindService` for the
  rest of the process. Harmless today because it still issues `startService`.
- Untracked scratch lives in the repo root: `.tmp/` (16 probe scripts plus JSON) and
  `cleanup-before.patch`. Must not be committed with the beta.
- `BatchMatchInvariantTest` still claims "a swap targets exactly one file" but the assertion that
  pinned that (`MatchOps.swapTargets`) was deleted together with the function; no test now proves a
  swap stays scoped to one task.
- `MatchReviewSheet.kt:71-77` — `remember(flagged)` still resets the user's picks whenever any listed
  task's state changes (pre-existing, now more visible because a swap mutates state while the sheet
  stays open).
- `sleeve_engine.py:344` — `_TASKS` never evicts completed tasks (pre-existing memory growth).
- `DownloadService.onStartCommand(null)` → `stopSelf()` → `onDownloadServiceDestroyed()` is the right
  order for a sticky restart; checked, no finding.

---

## 5. Review coverage and gaps

**Baseline used.** `HEAD ba79e156` + the uncommitted working tree at 15:32 on 2026-11-11
(32 files, +1140 / -1695). The tree moved during the review: an earlier snapshot (15:10) still
still contained `DownloadUtil.matchBestCandidate` and lacked several files the 15:32 state has
(`MatchOps.kt`, the `DownloadPage.kt` / `DownloadSettingsDialog.kt` deletions, `Type.kt`,
`MetadataMatchingTest.kt`, `ExampleInstrumentedTest.kt`). Every file whose content differed was
re-read at 15:32; line numbers refer to that state and will shift if the tree is edited again.

**Claims checked against evidence, not against the author's description**
- Swap transaction and cleanup: `swapOne` / `cleanupSwappedFile` / `abandonSwappedFile`, the
  `pendingSwapCleanup` lifetime, `MatchResult.confirmSelection` / `cancelPending`, and the Room
  delete (`VideoInfoDao.kt:33-34`, `DELETE … WHERE videoPath = :path` — exact-path match, so the row
  and the file really do refer to the same artifact).
- Restore path: `enqueueFromBackup` against `BatchOps.isUnfinished` / `isResumable`, and
  `PreferenceUtil.encodeTaskListBackup` to confirm `match` is persisted.
- Metered gate: the whole of `NetworkPolicy.kt`; both hosts (`DownloadPageV2.kt:269-292, 388-401`,
  `QuickDownloadActivity.kt:79, 195-200, 270-280, 334-349`); the single enforcement point
  (`doYourWork` is the only caller of `download()` — checked by grep, so no lane bypasses it);
  `MeteredNetworkDialog` copy and return values.
- Ghost engine: `watch` retry budget and `unreadableSnapshot`, `DownloadUtil`'s terminal-verdict
  rewrite, and `sleeve_engine.py:359-365` (`progress` on an unknown id returns an ERROR JSON, it does
  not raise — so a completed-but-forgotten task cannot be mistaken for an unreadable poll).
- Compose: `SealModalBottomSheet` (M3 1.4.0-alpha11) content column, the `weight` pattern already
  shipped in `MetadataTracksPage.kt:133` (so the weight itself is not new or crash-prone), and the
  fixed-vs-scrolling split in `MatchReviewSheet`.
- Update channel: `channelAccepts` against `STABLE = 0` / `PRE_RELEASE = 1`
  (`PreferenceUtil.kt:156-157`) and the filter's position before `mapNotNull` / `maxWithOrNull`.
- Service latch: `App.startService` / `stopService` / `onDownloadServiceDestroyed` against
  `DownloadService.onStartCommand` (null-intent sticky restart) and `onUnbind`.
- Error text: `Task.DownloadState.Error.message` serialization, `errorText`, `getErrorReport`,
  `DownloadDialogV2.ErrorPage` fallback.

**Verification actually run**
- `./gradlew testGenericDebugUnitTest --rerun-tasks --no-build-cache` → BUILD SUCCESSFUL, 48 tasks
  executed, 157 tests / 0 failures / 0 errors / 0 skipped (27 suites). The first attempt reported
  `FROM-CACHE`; that result is not evidence and was discarded.
- `python -m unittest discover -s scripts -p "test_*.py"` → 84 tests, OK.
- Not re-run: `assembleGenericDebug` (the unit-test task did compile main + unit-test sources),
  instrumented tests, and any lint/static analysis.

**Not covered — needs a device or emulator**
1. **Process death mid-swap** (the must-fix): apply a swap, park it on cellular, swipe the app away,
   reopen — does the song return as `Replacing…`, and can `Accept all` clear it?
2. **Metered transition during a batch**: Wi-Fi → cellular with the preference off. Confirm the
   in-flight transfer is not interrupted, the queue parks, the card reads "Waiting for Wi-Fi", and
   returning to Wi-Fi resumes without a tap (`onNetworkRestored` → `kickFromNetwork`).
3. **Airplane mode / no network**: does the "use cellular data?" dialog appear when there is no
   network at all? The gate has only one observable (`metered`), and I did not verify on this run how
   `ConnectivityManager.isActiveNetworkMetered()` reports the no-default-network case.
4. **Rotation with the metered dialog open**, both hosts (see 3.3), plus the dialog's z-order over
   the quick-share sheet.
5. **MatchReviewSheet at 200% font, landscape, 12+ swapped songs**: is the Apply row still reachable
   and is the list non-zero height (see 3.2)?
6. **Ghost engine end-to-end**: a completed transfer (file + history row), a failed one, and a
   deliberate 2 s stall in `progress` — confirm 1.5 s of unreadable polls does not abort a live
   transfer or delete its partial output.
7. **Service latch**: quick download → queue drains → badge disappears; a later download starts the
   service again without a zombie notification.

