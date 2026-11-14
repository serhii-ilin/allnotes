# Audit: uncommitted note-identity changes (2026-11-10)

Scope: the uncommitted working tree on top of `cb10b9b` (22 modified files, 5 new). It was produced by a Cursor
agent session to fix the issues its own audit of the vault migration found. Requirement IDs refer to
[`docs/requirements/`](../requirements/README.md).

Verification:

- Full unit suite on a snapshot of the working tree: **491 tests, 0 failures**.
- Findings marked *reproduced* were confirmed with a probe test or a standalone run, not only by reading the code.

## Did the changes fix the issues they targeted?

| Issue (from the Cursor audit) | Result | Notes |
|---|---|---|
| A renamed or moved file becomes a second note on other devices | **Partly fixed** | One note is kept. A title rename made on another device also writes a spurious conflict copy ([AUD-3](#aud-3)). |
| Clearing the last reminder does not reach other devices | **Fixed** | `reminders: []` is written when the key already existed. Restoring from `.trash` restores reminders. |
| Folder-observer syncs do not reschedule alarms | **Fixed, but the DI change crashes the app at launch** | [AUD-1](#aud-1) |
| "Move my notes here" leaves the old files behind | **Not fixed, and unsafe** | Only linked notes are deleted. Overlapping folders lose live files ([AUD-2](#aud-2)). |
| Drawer shows "External storage" / "File storage" | Fixed | It now shows only the folder path. |
| Rescan and clear actions give no feedback | Fixed | The message appears twice ([AUD-10](#aud-10)). |
| Android `.trashed-…` files shown as notes | Fixed | Minor bookkeeping gaps ([AUD-9](#aud-9)). |

**Verdict: do not commit as is.** AUD-1 makes the app crash on every launch, and AUD-2 can delete live note files.
Both fixes are small. AUD-3 and AUD-4 should be fixed before this ships to more than one device.

## Resolution (2026-11-10)

All of AUD-1…AUD-8 are fixed and committed on `master` under this branch's base.

- **AUD-1** — `SyncModule` now builds `VaultWriteClock` and `VaultFolderChangeMonitor` explicitly instead of
  `singleOf(::…)`. Proven by `KoinGraphTest.appModules_resolveTheSingletonsAppOnCreateNeeds`. The crash was already
  on the previously pushed `master` (since `b28073d`), not only in the Cursor work.
- **AUD-2** — Owner decision: "Move my notes here" copies notes into the new folder and never deletes anything in the
  old folder; the user is told the old files remain. `VaultPreviousTree` was removed. Proven by
  `VaultFolderMoveKeepsOldFilesTest` (2 tests).
- **AUD-3** — After a remote move the local side is hashed at the path the baseline was taken at, so the front-matter
  change caused by the path change is a pull. Proven by `VaultRemoteRenameNoConflictTest` (3 tests).
- **AUD-4** — Backfill catches per file, logs the path and never decides the sync result. Proven by
  `VaultNoteIdBackfillTest.unwritableFile_doesNotFailTheSync_orDropThePush`.
- **AUD-5** — A conflict copy is written with its own id and imported as its own note, so it is listed in
  Settings → Sync conflicts. Proven by `VaultDuplicateIdScenariosTest.conflictCopy_carriesItsOwnId_andIsListedInSettings`.
- **AUD-6** — The identity key is `allnotes-id`; a foreign `id:` is preserved verbatim. A file whose id another note
  owns is imported as its own note and re-identified with `VaultNoteIds.forFile(path, created)`. Proven by
  `VaultNoteIdKeyTest`, `VaultDuplicateIdScenariosTest` and
  `VaultNoteIdScenariosTest.twoCopiesOfTheSameId_becomeTwoNotesWithDistinctIds`.
- **AUD-7** — Backfill ids are a UUID derived from `VaultNoteIds.forFile(path, created)`, and the write is a
  compare-and-swap (`ISyncBackend.replaceNoteText(path, text, expectedRawHash)`). Proven by
  `VaultNoteIdBackfillTest.twoPhones_backfillTheSameBytes` and
  `VaultNoteIdBackfillTest.externalEditDuringBackfill_isNotOverwritten_andGetsAnIdNextSync`.
- **AUD-8** — Backfill takes contents from the scan already in memory. Proven by
  `VaultNoteIdBackfillTest.filesWithIds_addNoReadsOutsideTheScan`. The separate
  `refreshVaultAttachmentIndex` read remains and keeps SYN-04 `Partial`.
- **AUD-9** and **AUD-10** remain open; DEL-06 and UI-02 stay `Partial`.

## Findings

### AUD-1
**Critical: the app crashes on launch.** *Reproduced.*
`SyncModule` changed `VaultFolderChangeMonitor` to `singleOf(::VaultFolderChangeMonitor)`. Its constructor ends with
`debounceDelayMs: Long = 2_000L` and `selfWriteWindowMs: Long = 3_000L`. Koin's constructor DSL calls `get()` for
every parameter and ignores Kotlin defaults. A standalone Koin 4.1.0 run with the same constructor shape throws
`InstanceCreationException`. `App.onCreate` calls `vaultFolderChangeMonitor.start()`, so every launch fails. No unit
test builds the Koin graph, which is why the suite stays green.
**Fix:** restore the explicit `single { VaultFolderChangeMonitor(context = get(), …, reminderManager = get(),
vaultWriteClock = get()) }`. Add a Koin `verify()`/`checkModules` test for all modules.
Requirement: SYN-08.

### AUD-2
**Critical: "Move my notes here" can delete the files it just adopted, and it does not move the notes it exported.**
`resolveFolderSwapNow(MOVE_NOTES)` calls `deleteOriginalsIfExported` right after `syncNotes()` returns.
1. *Overlapping folders delete live files.* `isSameVaultTree` compares authority and tree id only, so a folder nested
   inside the old one, a parent of it, or the same directory through another documents provider counts as
   "different". The first sync of the new tree sees the old files (for example `Notes/Plan.md` when the new tree is the
   old one's parent). `linkLocalExportToRemote` links each note to that file because title and body match. The note
   then reads back fine, so `VaultPreviousTree.deleteNoteFiles` deletes `Plan.md` **and its asset folder** through the
   old tree URI. That is the same physical file the note is now mapped to. Attachment bytes live only in the vault, so
   they are lost. Syncthing propagates the deletion to every device.
2. *Exported notes keep their old files.* Exports are queued through `ProcessRemoteActions` with a 500 ms debounce, so
   their mappings do not exist yet when `deleteOriginalsIfExported` runs. Only notes that were *linked* to a file
   already in the new folder (case 1) have mappings at that point. The feature therefore mostly deletes exactly the
   files it must not delete.

The existing `move_*` test mocks `syncNotes()`, so neither path runs in tests.
**Fix:** never delete when either tree contains the other or both resolve to the same document. Delete only files
whose new mapping came from a *created* file, never a linked one. Wait for the queued exports (or run the creates
inline) before deleting. Re-check that the new file still exists after each delete, and stop at the first failure.
Until then, revert to leaving the old folder untouched, as the committed code does.
Requirement: FLD-08.

### AUD-3
**High: a title rename made on another device produces a spurious conflict copy.** *Reproduced.*
Device A renames "Plan" to "Ideas", so it writes `Ideas.md` with a newer `modified` and no `title:` line, because the
title equals the file name. Then it removes `Plan.md`. On device B, `SynchronizeNotes` retargets the mapping but
computes `localHash` at the **new** path. The baseline was taken at the old path, so the canonical document gains a
`title: "Plan"` line and differs from the baseline. The remote also differs (title and `modified`), so the pair is
classified as divergent. Result: `Ideas (conflict <epoch>).md` is written into the vault, a "conflict" toast appears,
and the copy syncs to every device. Probe: after two syncs the mapping is `Ideas.md`, the title is `Ideas`, and
`conflictFiles = [Ideas (conflict 1794346783).md]`.
The new scenario tests only move files with identical bytes, so they do not catch this.
**Fix:** when a match is `moved`, compute the local hash and the dirty check at the path the baseline was taken at, or
rebaseline the mapping state at the new path before comparing. Add the probe as a scenario test
(SC-02 in [scenarios](../scenarios.md)).
Requirements: IDN-03, CON-03.

### AUD-4
**High: one file that cannot take an id fails every sync.** *Reproduced.*
`backfillNoteIds` calls `replaceNoteText` with no try/catch. Any exception (a read-only file, a provider error,
"recovery incomplete", a generation change) propagates out of the locked phase. `syncNotes` returns `GenericError`,
and the pushes computed by that sync are dropped. `SyncWorker` retries with backoff, and the same file fails again on
the next attempt. Probe: second `syncNotes()` returns `GenericError`.
**Fix:** catch per file, log the path, and continue. Never let backfill decide the sync result.
Requirement: IDN-04.

### AUD-5
**Medium: new conflict copies never appear in Settings → Sync conflicts.**
`createConflictCopy` writes the remote bytes verbatim, including `id`. On the next scan `VaultNoteIdentity` sees two
files with one id, marks the copy as a duplicate, and skips importing it. The Settings list only shows notes *mapped*
to a conflict-copy path, so it stays empty for every conflict created from now on. The only trace is a toast.
(The AUD-3 probe shows this: the copy exists but the note count stays at 1.)
**Fix:** give a conflict copy a fresh id (or strip it) when it is written, or build the Settings list from
`conflictCopyPath` on the mappings.
Requirement: CON-02.

### AUD-6
**Medium: the front-matter key `id` collides with other tools, and copies made outside the app disappear.**
`id` is a common key in Obsidian vaults (Zettelkasten ids, plugins, templates). It used to be preserved verbatim. Now
it is parsed as AllNotes's identity, rewritten quoted, and a blank `id:` is replaced with a UUID. Two files that share
an `id` value, such as a file duplicated in Obsidian or a file manager, or a template id, are treated as one note: the
second file is never imported and is only reported as a conflict.
**Fix:** use a namespaced key (for example `allnotes-id`). When an id is duplicated while the mapped file still exists
at its own path, treat the other file as a copy: import it as a new note and give it a new id.
Requirements: IDN-01, IDN-05, FMT-03.

### AUD-7
**Medium: the upgrade rewrites every note file, and devices that upgrade independently pick different ids.**
On the first sync after updating, `backfillNoteIds` writes a random UUID into every mapped file. Two devices that
upgrade before syncing with each other write different bytes into every file, and Syncthing creates a
`.sync-conflict-…` file per note. Identity still converges, because path matching wins, but the user is left with one
conflict file per note. The read-modify-write also has no check for an external edit landing between the read and the
write, unlike `updateNote`.
**Fix:** derive the backfill id deterministically, for example a UUID v5 of the normalized path plus `created`, so
both devices write identical bytes. Before writing, compare the raw hash with what was just read.
Requirement: IDN-04.

### AUD-8
**Low (performance): every sync reads every mapped file one extra time.**
`backfillNoteIds` calls `getNote` for each mapping on every sync, even when all files already have ids. That is a
per-file SAF lookup outside the directory snapshot, on top of the existing `refreshVaultAttachmentIndex` pass.
**Fix:** use the scan result that is already in memory (`allRemoteNotes` by path).
Requirement: SYN-04.

### AUD-9
**Low: bookkeeping gaps in `retireAndroidTrashNotes`.**
Retired rows set `trashedPath` and `userBinned` but not `binnedVaultPath`. Emptying the bin then returns early in
`permanentlyDeleteNotesInBin`, so the mapping row is orphaned. When another note owns the same id, the duplicate is
hard-deleted from Room without going through the bin.
Requirement: DEL-06.

### AUD-10
**Low: UI polish.**
`SettingsFragment` shows every vault message as a Snackbar *and* a Toast. `vault_move_files_remain` is a plain string
with `%1$d`, so it reads "1 notes". Use a plurals resource.
Requirement: UI-02.

### AUD-11
**Medium (pre-existing): right after a note is exported, it looks locally edited until the next sync.**
`ProcessRemoteActions.insertRemote` records the baseline, then `noteDao.updateLastModified(note.id, created.lastModified)`
stamps the file mtime into the note's `modifiedDate`, while the file's `modified:` keeps the old value. Until the
creating device syncs again (which re-pushes the note), any edit or rename of that note on another device is
classified as a true conflict and produces a conflict copy. Reproduced 2026-11-10 with the audit probe
`crossDeviceTitleRename` (note created with `modifiedDate = 1000`, renamed elsewhere before the creator's next
sync → `Ideas (conflict …).md`). Likely fix: compare against the front-matter `modified` instead of the file mtime,
or rebaseline after stamping.
Requirements: CON-03, SYN-01.

### AUD-12
**Low: which file keeps a shared id depends on local mapping order.**
When two mapped files carry one `allnotes-id`, `NoteRepositoryImpl.backfillNoteIds` lets the mapping with the lowest
local `mappingId` keep it. Mapping ids are per device, so two devices can keep different files as the original. Each
then re-identifies a different file. The bytes still converge and no text is lost, but both files get rewritten once.
Found by the independent review (2026-11-10). A device-independent tie-break (for example `created`, then path) would
avoid the extra rewrites.
Requirement: IDN-05.

### AUD-13
**Low: path-first matching ignores ids, so swapping two files' names swaps their notes.**
`VaultNoteIdentity.resolve` matches a mapped note to whatever file is at its path before looking at ids. If `Plan.md`
and `Other.md` exchange names, each note takes the other's text, and an unmatched file is re-imported. No bytes are
lost. A fix would skip the path match when the file's id is set and differs from the mapping's, but a stale recorded id
must not make a note look missing (and binned after two scans). It needs its own design.
Requirement: IDN-02.

## Review round (2026-11-10)

An independent review (Sonnet) of `cb10b9b..master` found no data-loss path. Its findings, after verification:
- **M1** (a rename keeping bytes and mtime would push the old title back): *not reproduced*. `applyFileName` moves the
  title to the new file name, and nothing is written back. Kept as the regression test
  `VaultRemoteRenameNoConflictTest.remoteRenameWithSameBytesAndMtime_titleFollowsTheFileAndNothingIsWrittenBack`.
- **M2** (backfilled ids depended on each device's import time when the file has no `created`): fixed in `068fa27`.
  Ids come from the file's own `created`, or the path alone. Test
  `VaultNoteIdBackfillTest.twoPhones_differentImportTimes_fileWithoutCreated_backfillTheSameBytes`.
- **C1** (backfill rewrote non-UTF-8 or BOM files through a lossy decode): fixed. Such files are left byte-for-byte
  alone. Test `VaultNoteIdBackfillTest.filesThatDoNotRoundTrip_areNotRewritten`.
- **C2** ("notes were copied" shown even when the sync failed): fixed. Test
  `VaultFolderMoveKeepsOldFilesTest.move_whenTheSyncFails_doesNotClaimTheNotesWereCopied`.
- **C3** (the inserted id line used LF in CRLF files): fixed. Test `VaultNoteIdKeyTest.withNoteId_keepsWindowsLineEndings`.
- **M3** and **L1**: recorded as AUD-12 and AUD-13.

## Resolution, round 3 (2026-11-11)

- **AUD-9**: retired Android-trash notes get full bin bookkeeping (`binnedVaultPath`, not a user bin); emptying the
  bin removes their rows, the `.trashed-` file is never touched, a duplicate goes to the bin instead of being
  hard-deleted, and restore writes a fresh file. `VaultAndroidTrashRetirementTest`.
- **AUD-10**: folder messages are shown once (Snackbar; the row keeps the result). The plural string went away with
  AUD-2. Not covered by an automated UI test.
- **AUD-11**: the mtime stamp after an export is gone, and a clean sync with a baseline is decided by hashes only, so
  a just-exported note no longer looks edited. This also removed identical-content re-pulls and is the likely cause of
  the intermittent `"Body"` vs `"Body\n"` test failure seen earlier. `VaultExportStampScenariosTest`.
- **AUD-12**: the file that keeps a shared id is chosen from file facts every device sees (already-mapped first, then
  `created`, then path). `VaultNoteIdOwnerOrderTest`.
- Still open: **AUD-13** (Low, swapped file names swap notes).

## Review round 4 (2026-11-11)

An independent review of FMT-09, HST-13 and the AUD-9…12 fixes found no data loss. Fixed, each with a test written
first: the date backfill uses the note's own dates (never the file's current time) and reads the note after this
sync's pulls; the shared-id owner is chosen only from file facts (`created`, file time, path); retention measures
"now" from other devices' logs, keeps the newest own month and runs at most once a day; an empty front-matter block
and quoted date keys no longer produce a second block or a duplicate key; the legacy-only push arm is documented;
front matter is parsed once per file.

## Still open from the committed migration (not touched by these changes)

- `refreshVaultAttachmentIndex` reads every mapped file on every sync, in addition to the scan.
- ~~`updateRemote` does not record the file's mtime…~~ Resolved by AUD-11: clean syncs no longer compare mtimes.

### AUD-14
**Low (backlog, review round 5 on 2026-11-11): front-matter and retention edge cases.**
- An empty `---\n---` header followed by a later `---` line in the body is parsed as one front-matter block (a
  pre-existing parser quirk), so a backfilled id or date lands inside it. `VaultNoteDocument.frontMatterRegex`.
- A quoted `"allnotes-id":` key is not recognised by the parser or `replaceNoteId`, so a second, unquoted key is added.
  The app itself never writes it quoted.
- A long-abandoned other device's stale clock caps retention, so this device stops pruning its old months (growth
  only, no loss).
Requirements: FMT-09, IDN-01, HST-13.
