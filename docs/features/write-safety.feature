@write-safety
Feature: Write safety and recovery
  Every write to the folder is staged, journaled and verified, so a crash never leaves half a note behind. Queued
  work carries the folder generation it was made for, and one bad file never stops the rest of the sync.

  Background:
    Given the notes folder is "Notes"

  @WRS-01 @test:VaultRecoverableWriteTest.successfulWrite_publishesVerifiedContent @test:VaultRecoverableWriteTest.publishFailure_restoresPriorVerifiedContent @test:VaultRecoverableWriteTest.truncatedPublish_failsAndRestoresBackup
  Scenario: Writes are journaled and verified
    Given "Note.md" holds a verified version
    When a note write is staged, recorded with a backup of the previous bytes and published
    Then the published bytes are read back and verified
    And a failed or truncated publish restores the previous bytes
    And the journal is empty afterwards

  @WRS-02 @SC-16 @test:VaultRecoveryPersistenceTest.journalCrashRestart_completesPublishFromStaging @test:VaultRecoveryPersistenceTest.partialPublish_quarantinesPathAndLeavesScanToSkipIt @test:VaultQuarantineScenariosTest.staleGenerationJournal_withBackup_isRestoredAndLaterSaveSucceeds @test:VaultQuarantineScenariosTest.quarantinedPath_isNotImportedNotBinnedAndKeepsRoomContent @test:VaultQuarantineScenariosTest.quarantinedPathLeftOutOfScan_doesNotBinItsNote
  Scenario: An interrupted write is completed, rolled back or quarantined
    Given a save was in progress when the app was killed between staging and publishing
    When the app starts again
    Then pending journals are completed or rolled back and verified by hash
    And a path that cannot be settled is quarantined
    And the database keeps its copy of the note
    And that path is not imported, written or binned

  @WRS-02 @test:VaultRecoveryPersistenceTest.foreignVaultScope_doesNotApplyBackupContent @test:SafVaultWriteStoreRecoveryTest.legacyScopeRecoveryDir_isMigratedAndItsJournalStaysOurs
  Scenario: A journal from another folder is never applied
    Given a journal belongs to a different folder or an earlier pick of the same folder
    When recovery runs for the current folder
    Then no foreign backup text is written
    And a journal from an earlier pick of the same folder is restored into the current one

  @WRS-03 @test:VaultGenerationFenceTest.storageWriteContext_invalidAfterFolderBump @test:ProcessRemoteActionsVaultFenceTest.staleFence_abortsBeforeBackendWrite @test:ProcessRemoteActionsVaultFenceTest.missingFence_rejectsVaultWrite
  Scenario: Stale operations never reach a new folder
    Given a write is queued with folder generation 1
    When the user switches folders and the generation becomes 2
    Then the queued write is dropped before it touches any folder
    And a vault write without a fence is rejected

  @WRS-04 @test:SafVaultWriteStoreRecoveryTest.recoveryItemCount_and_clearAllRecoveryState
  Scenario: The user can clear stuck saves
    Given three unfinished saves exist
    When the user opens Settings and taps Clear unfinished saves
    Then the row shows the count of three
    And after confirmation all three are discarded
    And the row shows zero

  @WRS-05 @test:VaultNoteIdBackfillTest.unwritableFile_doesNotFailTheSync_orDropThePush @test:VaultFolderBlockerScenariosTest.blocker7_writeFailure_nextSyncStillWorks
  Scenario: One bad file never stops sync
    Given "Plain.md" is read-only
    When the user edits its note and a sync runs
    Then the write to that file fails only for that note
    And the sync still succeeds and its other pushes land
    And the next sync retries the failed file
