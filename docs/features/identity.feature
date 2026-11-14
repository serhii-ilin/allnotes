@identity
Feature: Note identity, renames and moves
  A note's identity lives in its own file, so a rename or a move made on any device or by any tool keeps one note.
  Files are matched by path first, then by the "allnotes-id" in their front matter, then by a unique body. A shared
  id never hides a file: an extra copy becomes its own note with its own id.

  Background:
    Given phone A, phone B and a desktop share the folder "Notes" through Syncthing

  @IDN-01 @test:VaultNoteIdKeyTest.parse_readsAllNotesId_andRenderWritesItFirst @test:VaultNoteDocumentTest.noteId_roundTripsAndIsInsertedOnce @test:VaultNoteIdKeyTest.foreignId_isKeptVerbatim_andIsNotIdentity
  Scenario: Every note file carries a stable id
    Given a note "Plan" is exported
    Then "Plan.md" contains one "allnotes-id" line
    And a foreign "id:" key is left exactly as it was
    And later saves never change the "allnotes-id"

  @IDN-02 @test:VaultNoteIdentityTest.sameIdAtNewPath_isTheSameNote @test:VaultNoteIdentityTest.uniqueBody_retargetsANoteThatHasNoId @test:VaultNoteIdentityTest.twoFilesWithTheSameBody_areNotMatched @test:VaultNoteIdentityTest.blankBody_isNotMatched
  Scenario: Files are matched to notes by path, then id, then unique body
    Given "Plan.md" is mapped to note "Plan" with id "u1"
    When the scan sees "Folder/New.md" with id "u1" and "Plan.md" is gone
    Then the note follows "Folder/New.md"
    And a note without an id follows a renamed file only when its non-blank body is unique
    And two candidate files with the same body change nothing

  @IDN-03 @SC-02 @test:VaultRemoteRenameNoConflictTest.remoteTitleRename_pullsWithoutConflictCopy @test:VaultNoteIdScenariosTest.renamedFile_keepsTheNoteAndFollowsTheNotebook
  Scenario: A title rename made on another device keeps one note
    Given "Plan.md" is synced on A and B with title "Plan"
    When A renames it to "Ideas", so "Ideas.md" replaces "Plan.md"
    Then B shows one note titled "Ideas" at "Ideas.md"
    And there is no conflict copy, no toast and nothing in the bin
    And the mapping, title and baseline all follow the new path

  @IDN-03 @SC-03 @test:VaultRemoteRenameNoConflictTest.remoteNotebookMove_pullsWithoutConflictCopy @test:VaultNoteIdScenariosTest.renamedFile_keepsTheNoteAndFollowsTheNotebook
  Scenario: A notebook move made on another device keeps one note
    Given "Work/Plan.md" is synced on A and B
    When A moves it to "Job/Plan.md"
    Then B shows the same note in notebook "Job"
    And B never moves the file back
    And one note remains

  @IDN-03 @test:VaultNoteIdScenariosTest.renamedFile_withLocalEdit_isPushedAtTheNewPath
  Scenario: A local edit and a move on another device meet
    Given the user edited "Plan" on B
    And A moved its file to "Job/Plan.md"
    When B syncs
    Then B pushes its edit at "Job/Plan.md"
    And there is still one note and no conflict

  @IDN-04 @SC-11 @test:VaultNoteIdBackfillTest.twoPhones_backfillTheSameBytes @test:VaultNoteIdScenariosTest.newFile_gainsAnId_andASecondSyncDoesNotRewriteIt
  Scenario: Files without an id get one, safely
    Given A and B share a folder of 100 notes without ids
    When both install the update and sync before Syncthing runs
    Then both write the same id into every file
    And there are no ".sync-conflict-" files and no duplicate notes

  @IDN-04 @test:VaultNoteIdBackfillTest.unwritableFile_doesNotFailTheSync_orDropThePush @test:VaultNoteIdBackfillTest.externalEditDuringBackfill_isNotOverwritten_andGetsAnIdNextSync @test:VaultNoteIdBackfillTest.filesWithIds_addNoReadsOutsideTheScan
  Scenario Outline: Backfill never harms a file
    Given a mapped file without a "allnotes-id"
    When the next sync tries to add the id
    Then <outcome>

    Examples:
      | outcome                                                                   |
      | a read-only file is skipped and the sync still succeeds with its pushes   |
      | an edit that lands between read and write is kept and gets an id next time |
      | files that already have ids cause no extra read outside the scan          |

  @IDN-05 @SC-05 @test:VaultDuplicateIdScenariosTest.resurrectedOldPathAndEditedNewPath_bothTextsSurvive_withDistinctIds @test:VaultRemoteRenameNoConflictTest.remoteRenameWithLocalEdit_keepsBothSides @test:VaultNoteIdentityTest.twoFilesWithOneId_keepTheMappedFileAndLeaveTheOtherForImport
  Scenario: A renamed file and a resurrected old file both survive
    Given "Plan.md" is synced
    When A renames it to "Ideas" and, before receiving that, B edits "Plan.md"
    And Syncthing keeps both files, both carrying the same id
    Then each device keeps one note and surfaces the other as a conflict copy
    And no text is lost

  @IDN-05 @SC-18 @test:VaultDuplicateIdScenariosTest.copiedFile_isImportedAsItsOwnNote_withAPathDerivedId @test:VaultNoteIdScenariosTest.twoCopiesOfTheSameId_becomeTwoNotesWithDistinctIds
  Scenario: A copied file becomes its own note
    Given "Plan.md" is synced
    When the user duplicates it as "Plan copy.md" in a file manager
    Then both files appear as notes
    And the copy gets its own id derived from its path and creation date
    And a third sync rewrites neither file

  @IDN-05 @test:VaultDuplicateIdScenariosTest.goneMappedPath_twoFilesWithItsId_newestKeepsTheNote_otherIsImported
  Scenario: The mapped path is gone and two files carry its id
    Given the mapped file of "Plan" was deleted
    And "Older.md" and "Newest.md" both carry its id
    When the app scans
    Then the newest file keeps the note
    And the other file is imported as a second note with a different id

  @IDN-06 @test:VaultCaseMismatchScenariosTest.externalCaseOnlyRename_keepsNoteLiveAndMapped @test:VaultFolderBlockerScenariosTest.blocker6_caseOnlyTitleRename_noDuplicateFile
  Scenario: A case-only rename keeps the note
    Given "Note.md" is mapped on a case-insensitive volume
    When another tool renames it to "note.md"
    Then the app reports it under the mapped spelling "Note.md"
    And the note stays live with one mapping and its attachments

  @IDN-05 @test:VaultSharedIdOwnerMtimeTest @test:VaultNoteIdOwnerOrderTest.oppositeMappingOrders_keepTheSameFile_andReIdentifyTheSameOtherFile
  Scenario: Every phone agrees which file is the original
    Given "Note.md" and a copy "Note (copy).md" with the same front matter, the copy newer
    When each phone syncs, whatever it had mapped before
    Then every phone keeps the id on "Note.md" and gives the copy its own id
