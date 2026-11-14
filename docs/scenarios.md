# User scenarios — index

The stories now live as Gherkin in [`docs/features/`](features/). Each one keeps its `@SC-xx` tag and names the tests
that prove it. This file is only an index; keep it in step with the feature files per [docs/README.md](README.md).

"Phone A" and "phone B" share one folder through Syncthing. "Desktop" is any other tool editing the same folder.

| ID | Scenario | Feature file | Requirements | Status | Tests |
|---|---|---|---|---|---|
| SC-01 | Edit on A appears on B | [sync.feature](features/sync.feature) | SYN-06, SYN-07 | Covered | `VaultSyncOrchestrationTest.oneSidedDesktopEdit_pullsWithoutConflict` |
| SC-02 | Title rename on A | [identity.feature](features/identity.feature) | IDN-03, CON-03 | Covered | `VaultRemoteRenameNoConflictTest.remoteTitleRename_pullsWithoutConflictCopy` |
| SC-03 | Notebook move on A | [identity.feature](features/identity.feature) | IDN-03, FMT-04 | Covered | `VaultRemoteRenameNoConflictTest.remoteNotebookMove_pullsWithoutConflictCopy`, `VaultNoteIdScenariosTest.renamedFile_keepsTheNoteAndFollowsTheNotebook` |
| SC-04 | Same note edited on A and the desktop | [conflicts.feature](features/conflicts.feature) | CON-01, CON-02 | Covered | `VaultFolderBlockerScenariosTest.blocker2_externalEditThenAppEdit_oneConflictCopy`, `VaultDuplicateIdScenariosTest.conflictCopy_carriesItsOwnId_andIsListedInSettings` |
| SC-05 | Rename on A while B edits the old file | [identity.feature](features/identity.feature) | IDN-05, CON-01, CON-02 | Covered | `VaultDuplicateIdScenariosTest.resurrectedOldPathAndEditedNewPath_bothTextsSurvive_withDistinctIds`, `VaultRemoteRenameNoConflictTest.remoteRenameWithLocalEdit_keepsBothSides` |
| SC-06 | Note deleted on the desktop | [deletion-and-bin.feature](features/deletion-and-bin.feature) | DEL-03, DEL-05 | Covered | `VaultMissingFileSafetyTest.missingOnTwoScans_isBinned_withoutMarkingUserBin` |
| SC-07 | Provider glitch returns an empty or partial listing | [sync.feature](features/sync.feature) | SYN-05 | Covered | `SafVaultFsListingFailureTest.getAll_returnsNullInsteadOfPartialList`, `VaultFolderBlockerScenariosTest.emptyScanWithSeveralMappedNotes_failsScan_binsNothing_keepsReminders` |
| SC-08 | Most files vanish at once | [deletion-and-bin.feature](features/deletion-and-bin.feature) | DEL-04 | Covered | `VaultMissingFileSafetyTest.threeOfFiveMissing_syncProceeds_butKeepsNotesWithoutCounting` |
| SC-09 | Last reminder cleared on A | [reminders.feature](features/reminders.feature) | REM-03, SYN-08 | Covered | `VaultRemindersScenariosTest.clearingTheLastReminder_writesAnEmptyKey`, `VaultRemindersScenariosTest.removingReminderFromFile_deletesTheRow` |
| SC-10 | Bin and restore on A | [reminders.feature](features/reminders.feature) | DEL-01, DEL-05, REM-04 | Covered | `VaultTrashBinTest.restore_movesFileBack_andNoteIsLiveWithOneMapping`, `VaultRemindersScenariosTest.restore_putsRemindersFromTheFileBack` |
| SC-11 | A and B upgrade before syncing | [identity.feature](features/identity.feature) | IDN-04 | Covered | `VaultNoteIdBackfillTest.twoPhones_backfillTheSameBytes` |
| SC-12 | Move to an unrelated folder | [notes-folder.feature](features/notes-folder.feature) | FLD-08 | Covered | `VaultFolderMoveKeepsOldFilesTest.move_intoFolderThatContainsOldOne_keepsFilesAndTheNote` |
| SC-13 | Move into a folder that contains the old one | [notes-folder.feature](features/notes-folder.feature) | FLD-08 | Covered | `VaultFolderMoveKeepsOldFilesTest.move_intoFolderThatContainsOldOne_keepsFilesAndTheNote`, `VaultFolderMoveKeepsOldFilesTest.move_emitsOldFilesKeptMessage_andNoRemovalFailure` |
| SC-14 | Android Files app deletes a note | [deletion-and-bin.feature](features/deletion-and-bin.feature) | DEL-06, FMT-07 | Covered | `VaultTrashBinTest.androidTrashFile_isNotImported_andATrashTitleShowsTheOriginalName` |
| SC-15 | Obsidian edits foreign front matter | [file-format.feature](features/file-format.feature) | FMT-03, CON-03 | Covered | `VaultForeignYamlMetadataTest.externalAliases_triggersPull_notConflict` |
| SC-16 | Crash in the middle of a save | [write-safety.feature](features/write-safety.feature) | WRS-01, WRS-02 | Covered | `VaultRecoveryPersistenceTest.journalCrashRestart_completesPublishFromStaging` |
| SC-17 | Desktop creates a bare Markdown file | [file-format.feature](features/file-format.feature) | FMT-09, IDN-04 | Covered | `VaultNoteDateBackfillScenariosTest.aFileFromAnotherToolReceivesTheFullRequiredAttributeSet` |
| SC-18 | User copies a note file on the desktop | [identity.feature](features/identity.feature) | IDN-05 | Covered | `VaultDuplicateIdScenariosTest.copiedFile_isImportedAsItsOwnNote_withAPathDerivedId` |
| SC-19 | App starts after an update | [sync.feature](features/sync.feature) | SYN-08 | Covered | `KoinGraphTest.appModules_resolveTheSingletonsAppOnCreateNeeds` |
| SC-20 | Bin on A is recorded in A's own log | [history.feature](features/history.feature) | HST-01, HST-04 | Covered | `FolderHistoryScenariosTest.aUserBin_isRecordedInTheDevicesOwnLogOnly` |
| SC-21 | A conflict copy of a log is read once | [history.feature](features/history.feature) | HST-03 | Covered | `HistoryReaderTest.aSyncthingConflictCopyOfALog_isReadOnce` |
| SC-22 | A bin on A bins the note on B without waiting two scans | [history.feature](features/history.feature) | HST-09 | Covered | `HistoryNoteApplierScenariosTest.aBinOnOnePhone_binsTheNoteOnTheOtherWithoutWaitingTwoScans` |
| SC-23 | Three phones converge whatever the delivery order | [history.feature](features/history.feature) | HST-12 | Covered | `MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder` (20 seeds) |
| SC-24 | A note or folder name is valid on every file system | [file-format.feature](features/file-format.feature) | FMT-11 | Covered | `VaultPathsNameValidationTest`, `VaultFolderBlockerScenariosTest.blocker7_cjkLongTitle_savesAndSyncs`, `NotebookDialogViewModelTest`, `NotebookRepositoryNameGuardTest`, `VaultNotebookFolderTest` |
| SC-25 | A name from outside never creates an invalid notebook | [file-format.feature](features/file-format.feature) | FMT-11 | Covered | `VaultNotebookFolderTest.aNoteInADotFolder_createsNoNotebook_andKeepsItsFile`, `HistoryCatalogApplierSafetyTest.aNotebookCreateWithAnInvalidName_isIgnored` |
| SC-26 | A backup with an invalid notebook name restores safely | [file-format.feature](features/file-format.feature) | FMT-11 | Covered | `BackupRestoreVaultTest.restore_sanitizesAnInvalidNotebookName_withACollisionSuffix_andNotesFollow` |
| SC-27 | Existing invalid notebooks are renamed once | [file-format.feature](features/file-format.feature) | FMT-11 | Covered | `NotebookNameMigrationTest.invalidNames_areRenamedSafely_andTheirNotesFollow` |
