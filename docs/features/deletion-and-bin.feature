@deletion-and-bin
Feature: Bin, deletion and missing files
  Binning moves a note's file into ".trash/" instead of deleting bytes. A file missing from one scan is never a
  deletion, a mass disappearance is treated as a glitch, and a file that comes back restores its note.

  Background:
    Given phone A and phone B share the folder "Notes" through Syncthing

  @DEL-01 @test:VaultTrashBinTest.binning_movesFileToTrash_andKeepsAssets @test:VaultTrashBinTest.binning_keepsOriginalFolderUnderTrash_andTakenNamesGetSuffix @test:VaultTrashBinTest.restore_movesFileBack_andNoteIsLiveWithOneMapping @test:VaultTrashBinTest.restore_whenOriginalPathTaken_usesUniqueSibling @test:VaultTrashBinTest.restore_whenTrashFileIsGone_recreatesTheFile
  Scenario: Binning moves the file into .trash/ and restoring brings it back
    Given "Work/Plan.md" has the attachment "Work/Plan/photo.jpg"
    When the user bins the note
    Then the file is at ".trash/Work/Plan.md"
    And "Work/Plan/photo.jpg" stays so links still work
    And a taken trash name gets a suffix
    When the user restores it
    Then the file is back at "Work/Plan.md" with its asset
    And a taken original path makes restore use "Plan-2.md"
    And a missing trash file is recreated

  @DEL-01 @test:VaultTrashBinTest.binWhileFolderUnavailable_isMovedOnceItIsAvailable
  Scenario: A bin made while the folder is unavailable is applied later
    Given the folder cannot be written
    When the user bins "Plan"
    Then the note is binned and the move is remembered
    And once the folder is available the file moves to ".trash/Plan.md"

  @DEL-02 @test:VaultTrashBinTest.permanentDelete_neverTouchesAFileNowAtTheOriginalPath @test:VaultMissingFileSafetyTest.userBinnedNote_permanentDeleteStillRemovesVaultFile @test:VaultTrashBinTest.permanentDeleteFromBin_removesTrashFileAndAssets @test:VaultMissingFileSafetyTest.noteBinnedByMissingFile_emptyingBinKeepsFileThatCameBack
  Scenario: Permanent delete removes only the note's own bytes
    Given the user binned "Plan" and later wrote a new "Plan.md" for another note
    When the user empties the bin
    Then ".trash/Plan.md" and its assets are removed
    And the new "Plan.md" is untouched
    But a note binned by a missing file never deletes a file that came back

  @DEL-03 @SC-06 @test:VaultMissingFileSafetyTest.missingOnce_notBinned_andCountResetsWhenFileReturns @test:VaultMissingFileSafetyTest.missingOnTwoScans_isBinned_withoutMarkingUserBin @test:SynchronizeNotesMissingFileTest.firstMiss_keepsNoteAndMapping_onlyCountsTheMiss
  Scenario: A missing file is not a deletion until it is missing twice
    Given "Plan" is synced on A
    When the desktop deletes "Plan.md"
    Then after A's first complete scan the note is still live and the miss is counted
    And after the second it is in A's bin, marked not user-binned
    And emptying that bin deletes no file
    And the note comes back if the file returns

  @DEL-04 @SC-08 @test:VaultMissingFileSafetyTest.threeOfFiveMissing_syncProceeds_butKeepsNotesWithoutCounting @test:VaultMissingFileSafetyTest.twoOfTenMissing_usesNormalTwoStrikes
  Scenario: Many files missing at once is treated as a glitch
    Given five synced notes
    When three files are missing from a complete scan
    Then the sync proceeds
    And nothing is binned or counted as missing
    And the user sees one message
    But two of ten missing files use the normal two-strike rule

  @DEL-05 @test:VaultMissingFileSafetyTest.missingOnTwoScans_isBinned_withoutMarkingUserBin @test:VaultFolderBlockerScenariosTest.externallyDeletedMappedFile_doesNotFailScan @test:VaultFolderBlockerScenariosTest.blocker_fileReturnsAfterBin_restoresViaRepository @test:SynchronizeNotesFileReturnTest.blocker5_binnedLocalAndReturnedRemote_pullsInsteadOfRemoteDelete
  Scenario: A delete made on another device bins the note here
    Given "Plan" is synced on A and B
    When B deletes "Plan.md"
    Then A bins the note after the second complete scan
    And the note is not marked user-binned
    And if the file reappears the note is restored

  @DEL-06 @SC-14 @test:VaultTrashBinTest.androidTrashFile_isNotImported_andATrashTitleShowsTheOriginalName @test:VaultTrashBinTest.importedTrashFile_movesToTheBinUnderItsOriginalTitle_andStaysThere @test:VaultTrashBinTest.trashFileThatCopiesAnExistingNote_isDropped
  Scenario: Android's own trash is not shown as notes
    Given the Files app trashed "Plan.md" as ".trashed-1700000000-Plan.md"
    When the app scans
    Then no note titled ".trashed-..." appears
    And a note imported from such a file before the rule moves to the bin under the title "Plan"
    And a trashed copy of an existing note is dropped

  @DEL-06 @test:VaultAndroidTrashRetirementTest.emptyingTheBin_removesARetiredTrashRow_andLeavesTheTrashedFileAlone @test:VaultAndroidTrashRetirementTest.deletingARetiredTrashRowFromTheBin_removesItWithoutTouchingTheFile @test:VaultAndroidTrashRetirementTest.aTrashedCopyOfALiveNote_landsInTheBin_insteadOfBeingHardDeleted @test:VaultAndroidTrashRetirementTest.restoringARetiredTrashNote_writesAFreshFile_andLeavesAndroidsTrashAlone
  Scenario: Emptying the bin fully cleans up a retired Android-trash row
    Given a note was retired because its file is under Android's trash
    When the user empties the bin
    Then the retired mapping row is removed and its file is not hard-deleted behind the bin
    # Gap: the row keeps no binnedVaultPath, so permanent delete returns early (AUD-9).

  @DEL-07 @test:HistoryNoteBinImportTest.aBinOfANoteThatNeverReachedTheDevice_importsItIntoTheBin
  Scenario: A bin received from another device is dated when it arrives
    Given phone A bins "Plan" before phone B ever saw its live file
    When phone B syncs and imports the note into its bin
    Then the imported note carries a deletion date

  @DEL-07 @gap
  Scenario: A user bin is dated and restoring clears the date
    Given the user bins "Plan"
    When the bin entry is created
    Then the note carries the time it was binned
    And after the user restores it, it has no deletion date
    # Gap: no automated test asserts the deletion date on a user bin or after a restore.

  @DEL-07 @gap
  Scenario: The retention period decides which binned notes the clean purges
    Given Delete notes in bin is After 7 days (SET-11)
    And a note was binned longer ago than that period
    And another was binned inside it
    When the periodic clean runs
    Then the older note is permanently gone
    And the newer note stays in the bin
    But with Never nothing is purged, and a binned note without a deletion date is never purged
    # Gap: no automated test runs the bin cleaner against the setting.

  @DEL-08 @gap
  Scenario: The bin clean runs about every five hours
    Given the app has started
    Then one periodic bin clean is scheduled with a five-hour interval
    And a later start keeps that schedule
    And the clean uses the retention value in force when it runs
    And it runs without network, charging or battery requirements
    # Gap: no automated test asserts the worker schedule or its constraints.

  @DEL-09 @test:HistoryRetentionPurgeTest.theRetentionCleaner_doesNotRecordAPurge
  Scenario: The automatic clean records no purge
    Given "Plan" is in phone A's bin
    When A's automatic clean permanently deletes it
    Then A's folder history gains no note.purge

  @DEL-09 @gap
  Scenario: Another device keeps its bin entry after an automatic purge
    Given phone A and phone B both list "Plan" in their bins
    When A's automatic clean purges it
    Then B still lists it in its bin
    And B's .trash copy is gone, so restoring there writes a fresh file
    # Gap: no test runs an automatic clean across two devices.

  @DEL-09 @test:HistoryRetentionPurgeTest.anExplicitPermanentDelete_stillRecordsAPurge @test:HistoryNoteBinImportTest.aPurgeOnTheOtherPhone_removesANoteImportedIntoTheBin
  Scenario: An explicit permanent delete travels to other devices
    Given "Plan" is in phone A's bin and in phone B's bin
    When the user permanently deletes it from A's bin
    Then A records one note.purge
    And B removes its bin entry when it applies the event
    And B deletes no file

  @DEL-10 @gap
  Scenario: Unused private media is removed after a purge
    Given phone A's private media folder holds a copy no note uses
    And another copy is still used by a binned note
    When a clean with a positive retention period runs
    Then the unused copy is deleted
    And the referenced copy stays
    But emptying the bin or deleting a note instantly runs the same cleanup
    # Gap: no automated test asserts MediaStorageManager.cleanUpStorage.

  @DEL-11 @test:VaultTrashBinTest.permanentDeleteFromBin_removesTrashFileAndAssets
  Scenario: Empty bin clears a user-binned note and its files
    Given the user binned "Plan" with an attachment
    When the user empties the bin
    Then the note is gone and its .trash file and owned assets are deleted

  @DEL-11 @test:VaultMissingFileSafetyTest.noteBinnedByMissingFile_emptyingBinKeepsFileThatCameBack @test:VaultTrashBinTest.missingFileBin_thenPermanentDelete_touchesNothingInTheVault @test:VaultAndroidTrashRetirementTest.emptyingTheBin_removesARetiredTrashRow_andLeavesTheTrashedFileAlone
  Scenario: Empty bin clears missing-file and Android-trash entries without deleting files
    Given the bin holds a note binned by a missing file and a retired Android-trash row
    When the user empties the bin
    Then both notes and their mappings are gone
    And no file in the folder is deleted

  @DEL-11 @gap
  Scenario: Empty bin removes private media nothing uses
    Given the app's private media folder holds a copy no note uses
    When the user empties the bin
    Then the unused copy is deleted
    # Gap: no test asserts the cleanup after Empty bin.
