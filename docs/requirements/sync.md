# Scanning, change detection, push and pull (SYN)

How the app notices file changes and keeps the database and the folder in step. Use cases: UC-03, UC-08, UC-09,
UC-13. Scenarios: SC-01, SC-07, SC-15, SC-19. Gherkin: [sync.feature](../features/sync.feature).

### SYN-01 The folder is the source of truth
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultSyncOrchestrationTest`, `VaultImportBaselineOrderingTest`, `VaultExportStampScenariosTest`
Room is an index and cache that can be rebuilt from the folder. Each mapping records a baseline (the canonical hash of
the last agreed version) so the app can tell local edits, external edits and true conflicts apart.

**Acceptance criteria**
- AC1: A note's mapping records the canonical hash of the last agreed version.
- AC2: A one-sided external edit is pulled and a one-sided local edit is pushed without a conflict.
- AC3: After an import the baseline matches the state Room holds after notebook and tag application, so the second sync
  is clean.
- AC4: A note that the app just exported can be edited or renamed elsewhere without a conflict copy.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-01`

### SYN-02 Changes are picked up without the user asking
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderChangeMonitorTest`, `SyncWorkerResultTest`
A sync runs:
- on app resume and while a note list is visible (throttled, at most every 2 minutes);
- on pull-to-refresh;
- when the folder observer fires (best effort; SAF often misses Syncthing writes; bursts are collapsed, and the app's
  own writes are not mistaken for external ones);
- every hour from a background worker with no network constraint.
A sync that returns an error is retried with backoff. There is no switch to turn this off (see UI-03).

**Acceptance criteria**
- AC1: A burst of observer events collapses into exactly one sync after the debounce.
- AC2: An event inside the self-write window is deferred until the window ends, never dropped.
- AC3: An event after the window triggers exactly one sync.
- AC4: An error result is retried with backoff.

**Screens:** SCR-01
**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-02`

### SYN-03 Manual rescan re-reads everything
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultScanReadCountTest.clearScanCache_forcesReread_whenListingLooksUnchanged`
"Rescan notes folder" ignores the scan cache and re-reads every file, shows progress while it runs, and leaves the
result on the row afterwards.

**Acceptance criteria**
- AC1: A rescan clears the scan cache and re-reads a file even when its size and mtime look unchanged.
- AC2: While it runs the row is disabled and shows progress.
- AC3: Afterwards the row keeps the result (notes indexed or the failure reason).

**Screens:** SCR-09
**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-03`

### SYN-04 Scans are cheap when little changed
**Status:** Partial · **Scope:** Core · **Tests:** `VaultScanCacheTest`, `VaultScanReadCountTest`
A scan lists the tree once and reads each changed file at most once. Unchanged files (same mtime and size, outside the
racy window) come from an app-private cache, which is dropped when the folder changes.
*Gap:* `refreshVaultAttachmentIndex` still reads every mapped file once per sync in addition to the scan, so not every
extra full read is gone yet.

**Acceptance criteria**
- AC1: A cold scan reads each note exactly once.
- AC2: A warm scan reuses cached text and reads nothing.
- AC3: An entry with unknown size, or inside the 3-second racy window, is never reused.
- AC4: No step outside the scan reads every mapped file (intended; `refreshVaultAttachmentIndex` still does).

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-04`

### SYN-05 An unreliable scan changes nothing
**Status:** Implemented · **Scope:** Core · **Tests:** `SafVaultFsListingFailureTest`, `VaultFolderBlockerScenariosTest.emptyScanWithSeveralMappedNotes_failsScan_binsNothing_keepsReminders`, `VaultMissingFileSafetyTest.singleNoteVault_emptyListing_isNotBinned`
If any folder listing fails, a mapped file is visible live but missing from the snapshot, or the listing is empty while
notes are mapped, the sync stops before changing any note.

**Acceptance criteria**
- AC1: A failed listing returns no partial snapshot and the sync changes nothing.
- AC2: A mapped path that is visible live but absent from the snapshot fails the scan.
- AC3: An empty listing while notes are mapped bins nothing and keeps their reminders.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-05`

### SYN-06 App edits reach the file
**Status:** Implemented · **Scope:** Core · **Tests:** `ProcessRemoteActionsPushCasTest`, `VaultQuarantineScenariosTest.staleSyncUpdate_doesNotReplaceNewerEditorSave`
A saved edit is written to the note's file after a short debounce. The write uses what the database holds at that
moment, never an older queued snapshot, and is skipped for a note that has been binned or deleted since.

**Acceptance criteria**
- AC1: A saved edit reaches the note's file after the debounce.
- AC2: A newer editor save is not overwritten by an older queued push, even within the same second.
- AC3: A push for a note binned or deleted since it was queued does not restore it.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-06`

### SYN-07 External edits reach the app
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultSyncOrchestrationTest.oneSidedDesktopEdit_pullsWithoutConflict`, `VaultFolderBlockerScenariosTest.syncthingAddedFile_visibleOnNextScanWithoutLocalWrite`, `VaultFolderBlockerScenariosTest.externallyDeletedMappedFile_doesNotFailScan`
A file changed outside the app is pulled when the note has no unsaved local change. A new file is imported. Front
matter is applied to notebook, tags and reminders.

**Acceptance criteria**
- AC1: A one-sided desktop edit is pulled with no conflict copy.
- AC2: A file added by Syncthing is visible on the next scan without a local write.
- AC3: An externally deleted mapped file does not fail the scan (it follows DEL-03/DEL-05).
- AC4: Notebook, tags and reminders from the front matter are applied to the note.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-07`

### SYN-08 Alarms follow every sync
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderChangeMonitorTest.sync_reschedulesReminders`, `KoinGraphTest.appModules_resolveTheSingletonsAppOnCreateNeeds`
After any sync (worker, resume, manual, folder observer), reminder alarms are rescheduled so a reminder that arrived in
a file fires on time. `SyncModule` builds `VaultWriteClock` and `VaultFolderChangeMonitor` explicitly; the whole graph
resolves at start-up. (AUD-1: the previous `singleOf(::VaultFolderChangeMonitor)` crashed every launch, including on
the already-pushed master.)

**Acceptance criteria**
- AC1: An observer-triggered sync calls the reminder rescheduler once.
- AC2: The Koin graph containing `SyncModule` resolves `VaultFolderChangeMonitor` and `VaultWriteClock` at start-up.
- AC3: No constructor-DSL singleton depends on a Kotlin default parameter.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-08`

### SYN-09 Private data stays private
**Status:** Implemented · **Scope:** Core · **Tests:** `BackupRulesTest`
The scan cache and recovery journals live in app-private storage and are excluded from Android backup and device
transfer. Note text never appears in logs.

**Acceptance criteria**
- AC1: `backup_rules.xml` excludes the scan cache and recovery directories and the encrypted preferences.
- AC2: `data_extraction_rules.xml` applies the same excludes to cloud backup and device transfer.
- AC3: The manifest references both rule files.

**Gherkin:** [`sync.feature`](../features/sync.feature) — `@SYN-09`
