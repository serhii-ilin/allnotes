@sync
Feature: Scanning, change detection, push and pull
  The folder is the source of truth and Room is a rebuildable index. A scan lists the tree once, reads only what
  changed and compares a canonical rendering of each note. Pushes carry the folder generation they were made for,
  and a sync that cannot trust its listing changes nothing.

  Background:
    Given phone A and phone B share the folder "Notes" through Syncthing
    And the app may also run with only one device

  @SYN-01 @test:VaultSyncOrchestrationTest.oneSidedDesktopEdit_pullsWithoutConflict @test:VaultImportBaselineOrderingTest.importLifecycle_secondSyncClean_desktopEditPulls_noFalseConflict
  Scenario: The folder is the source of truth
    Given a mapping records the canonical hash of the last agreed version
    When the file changes on one side only
    Then the app can tell a local edit, an external edit and a true conflict apart
    And rebuilding from the folder restores the same notes

  @SYN-02 @test:VaultFolderChangeMonitorTest.rapidChanges_collapseToSingleSync @test:VaultFolderChangeMonitorTest.changeWithinSelfWriteWindow_isDeferredNotDropped @test:VaultFolderChangeMonitorTest.changeAfterSelfWriteWindow_triggersSync @test:SyncWorkerResultTest.genericError_isRetriedSoAnIncompleteScanTriesAgain
  Scenario: Changes are picked up without the user asking
    Given the folder observer reports a burst of changes
    When the debounce elapses
    Then exactly one sync runs
    And the app's own writes inside the self-write window are deferred, not dropped
    And a failed sync is retried with backoff
    And there is no switch to turn scanning off

  @SYN-03 @test:VaultScanReadCountTest.clearScanCache_forcesReread_whenListingLooksUnchanged
  Scenario: Rescan notes folder re-reads everything
    Given the scan cache holds an older body for "A.md" with the same size and mtime
    When the user taps Rescan notes folder
    Then the cache is cleared and every file is read again
    And the row shows the fresh result

  @SYN-03 @gap
  Scenario: Rescan shows progress and keeps the result on the row
    Given the user taps Rescan notes folder
    When the scan runs
    Then the row is disabled and shows progress
    And afterwards it keeps the result (notes indexed or the failure reason)
    # Gap: the re-read is covered; the progress and result-on-row behavior has no automated test.

  @SYN-04 @test:VaultScanCacheTest.hitOnSameMtimeAndSize_whenOlderThanRacyWindow @test:VaultScanCacheTest.entryCachedWithinRacyWindow_isNotReused @test:VaultScanCacheTest.missOnChangedMtimeOrSize @test:VaultScanReadCountTest.scanReadsEachNoteOnce_warmCacheReadsNothing
  Scenario: Scans are cheap when little changed
    Given "A.md" and "B.md" are unchanged and older than the racy window
    When a scan runs twice
    Then the tree is listed once per scan
    And the first scan reads each file at most once
    And the warm second scan reads nothing

  @SYN-04 @gap
  Scenario: No other sync step re-reads every mapped file
    Given every mapped file already has a "allnotes-id"
    When a sync runs
    Then no step outside the scan reads every mapped file
    # Partial: refreshVaultAttachmentIndex still reads each mapped file once per sync (AUD-8 backfill part fixed).

  @SYN-05 @SC-07 @test:SafVaultFsListingFailureTest.getAll_returnsNullInsteadOfPartialList @test:SafVaultFsListingFailureTest.getAll_failsWhenSnapshotOmitsAMappedFileThatExistsLive @test:VaultFolderBlockerScenariosTest.emptyScanWithSeveralMappedNotes_failsScan_binsNothing_keepsReminders @test:VaultMissingFileSafetyTest.singleNoteVault_emptyListing_isNotBinned
  Scenario: An unreliable scan changes nothing
    Given ten synced notes
    When a scan's listing fails, or returns nothing while notes are mapped
    Then the sync stops before changing any note
    And no note is binned, edited or told about
    And the next good scan proceeds normally

  @SYN-06 @SYN-07 @SC-01 @test:VaultSyncOrchestrationTest.oneSidedDesktopEdit_pullsWithoutConflict
  Scenario: An edit on A appears on B
    Given "Plan" is synced on A and B
    When the user edits it on A and Syncthing copies the file
    Then after B's next rescan, B shows the edit
    And B has no conflict copy and no second note

  @SYN-06 @test:ProcessRemoteActionsPushCasTest.pushApply_skippedWhenNoteChangedDuringPush_sameSecond @test:VaultQuarantineScenariosTest.staleSyncUpdate_doesNotReplaceNewerEditorSave
  Scenario: A queued push uses the newest note state
    Given an older push for "Plan" is still queued when a newer editor save lands
    When the queue runs
    Then the file receives the newer text
    And a push for a note binned or deleted since is skipped

  @SYN-07 @test:VaultSyncOrchestrationTest.oneSidedDesktopEdit_pullsWithoutConflict @test:VaultFolderBlockerScenariosTest.syncthingAddedFile_visibleOnNextScanWithoutLocalWrite @test:VaultFolderBlockerScenariosTest.externallyDeletedMappedFile_doesNotFailScan
  Scenario: External edits reach the app
    Given "Plan" is synced and has no unsaved local change
    When the desktop edits "Plan.md" and adds "Groceries.md"
    Then the app pulls the edited text
    And it imports "Groceries.md" as a note
    And notebook, tags and reminders from the front matter are applied

  @SYN-08 @SC-19 @test:VaultFolderChangeMonitorTest.sync_reschedulesReminders @test:KoinGraphTest.appModules_resolveTheSingletonsAppOnCreateNeeds
  Scenario: The app starts after an update and alarms follow every sync
    Given any install
    When the app starts
    Then the dependency graph resolves and the folder observer starts
    And after any sync (worker, resume, manual, observer) reminder alarms are rescheduled

  @SYN-09 @test:BackupRulesTest.fullBackupContent_excludesPrivateData @test:BackupRulesTest.dataExtractionRules_excludePrivateDataFromBothTransfers @test:BackupRulesTest.manifestReferencesBothRules
  Scenario: Private data stays private
    Given the app keeps a scan cache and recovery journals in app-private storage
    When Android backs up or transfers the app
    Then those directories and the encrypted preferences are excluded
    And no note text is written to a log
