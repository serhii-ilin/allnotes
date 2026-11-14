@notes-folder
Feature: Notes folder
  AllNotes keeps notes in one folder the user chose through the system picker. The folder is the source of truth;
  the database is an index. Choosing, re-picking or switching the folder never loses a note, never deletes a file
  that another note owns, and never writes into a folder the user has not confirmed.

  Background:
    Given the app has notes stored in its own database
    And a notes folder may or may not be configured

  @FLD-01 @test:PreferenceRepositoryLegacyKeysTest.legacySyncKeys_doNotBreakDefaults
  Scenario: An install from the old app starts in folder mode
    Given the old app left "NEXTCLOUD" and "WIFI" sync settings on the device
    When the new app reads its preferences
    Then it uses the default folder-mode preferences
    And no server is contacted

  @FLD-02 @test:SynchronizeNotesTest.vault_does_not_export_when_cutoff_unset
  Scenario: The app works before a folder is chosen
    Given a note "Draft" exists only in the app database
    And no notes folder has been configured
    When a sync runs
    Then "Draft" is still an ordinary note
    And nothing is written to any folder

  @FLD-03 @test:VaultSyncOrchestrationTest.legacyNoteBeforeCutoff_exportedWhenFolderConfigured @test:VaultFolderBlockerScenariosTest.blocker3_existingFolder_noDuplicates_noSuffixFiles
  Scenario: The first configuration exports existing notes once
    Given a note "Plan" exists only in the app database
    And the user picks the folder "Notes"
    When the app syncs
    Then "Notes/Plan.md" exists and holds the note
    And a file in "Notes" whose title and body already match is linked instead of duplicated
    And syncing again creates no second file

  @FLD-04 @test:BackendProviderTest.unconfirmedFolder_hasNoBackend_untilConfirmed @test:VaultUpgradeSafetyTest.unconfirmedFolder_syncChangesNothing @test:ActivityViewModelUpgradePromptTest.prompt_staysAvailableToASecondCollectorUntilResolved
  Scenario: An upgraded install confirms its folder before using it
    Given the update carries over a folder but the user has not confirmed it
    When a sync runs and a note is saved
    Then no file in the folder is read or written
    And the app keeps asking until the user answers
    And the prompt survives the activity being recreated

  @FLD-05 @test:VaultUpgradeSafetyTest.legacyMapping_bodyMatchWithDifferentTitle_relinksWithoutDuplicates @test:VaultUpgradeSafetyTest.legacyMapping_bodyMatchesFileOfAnotherNote_doesNotShareThatFile @test:VaultUpgradeSafetyTest.legacyMapping_unmatched_exportsOnlyOnNextSync
  Scenario: Legacy mappings are relinked without duplicates
    Given a note has an old "content://" mapping and a file in the folder matches its body
    When the folder is confirmed
    Then the note is linked to that one file
    And a file already mapped to another note is never reused
    And a note that matches no file is exported on the following sync

  @FLD-06 @test:SafVaultWriteStoreRecoveryTest.sameTreeUnderDifferentUriEncodings_sharesOneScope @test:SettingsViewModelFolderSwapTest.sameTree_differentUriString_noPrompt_noWipe_savesNewString
  Scenario: Re-picking the same folder changes nothing
    Given "Plan" is mapped in the folder "Notes"
    When the user picks the same folder encoded as "primary:Notes" instead of "primary%3ANotes"
    Then no question is shown
    And every mapping is kept
    And the stored folder string is updated

  @FLD-07 @test:SettingsViewModelFolderSwapTest.differentTree_withVault_emitsPrompt_andChangesNothingUntilAnswered @test:SettingsViewModelFolderSwapTest.prompt_staysPendingForARecreatedView_untilAnswered @test:SettingsViewModelFolderSwapTest.cancel_changesNothing_andDoesNotSync
  Scenario: Picking a different folder asks what to do
    Given "Plan" is mapped in the current folder
    When the user picks a different folder
    Then the app offers Move my notes here, Open this folder as-is and Cancel
    And nothing changes until the user answers
    And Cancel leaves every note and file where it was

  @FLD-08 @SC-12 @SC-13 @test:VaultFolderMoveKeepsOldFilesTest.move_intoFolderThatContainsOldOne_keepsFilesAndTheNote @test:VaultFolderMoveKeepsOldFilesTest.move_emitsOldFilesKeptMessage_andNoRemovalFailure @test:VaultFolderSwapScenariosTest.move_intoFolderWithMatchingFile_linksInsteadOfDuplicating
  Scenario: Move my notes here copies and never deletes
    Given notes live in the folder "Documents/Notes"
    When the user picks "Documents" and chooses Move my notes here
    Then every note has a readable file in the new folder
    And "Notes/Plan.md" and "Notes/Plan/photo.jpg" are untouched
    And the user is told that the previous folder still has the old files and may delete them
    And no note is binned

  @FLD-09 @test:VaultFolderSwapScenariosTest.openAsIs_onlyNewFolderNotesRemain_oldFolderFilesUnchanged @test:SettingsViewModelFolderSwapTest.openAsIs_deletesVaultMappedNotes_keepsUnmapped_leavesVaultFilesAlone_andSyncs
  Scenario: Open this folder as-is swaps the note set
    Given the old folder holds "Active.md" and the new folder holds "B.md"
    When the user chooses Open this folder as-is
    Then the app shows "B.md" and the new folder's notes
    And the old folder's files are left exactly as they were
    And notes that never had a file are kept and exported

  @FLD-10 @test:CloudFolderWarningTest.otherProvider_warnsTheFirstTime @test:CloudFolderWarningTest.sameTreeAgain_doesNotWarn_evenWithDifferentEncoding @test:CloudFolderWarningTest.deviceStorage_neverWarns
  Scenario: Folders provided by another app are flagged once
    Given the user picks a folder served by a cloud provider
    When the app configures it
    Then the user is warned once that listings may be unreliable
    And the same folder picked again does not warn
    And a folder on device storage never warns

  @FLD-11 @test:VaultFolderPruningTest.pruningOn_emptyFolderIsRemoved @test:VaultFolderPruningTest.pruningOn_firstListingEmptyButSecondShowsAFile_folderIsKept @test:VaultFolderPruningTest.pruningOff_folderStaysAfterItsLastNoteMovesToTheBin
  Scenario: Empty folders are pruned only when listings are trusted
    Given "Work/a.md" was the last note in the "Work" folder
    When the note moves to the bin and two listings agree that "Work" is empty
    Then the empty "Work" folder is removed on device storage
    But a folder that still shows a file on its second listing is kept
    And pruning is off, the folder stays
