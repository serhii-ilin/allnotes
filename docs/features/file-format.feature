@file-format
Feature: Note file format
  A note stays a plain Markdown or text file that other tools can read and write. Front matter carries the portable
  metadata; keys the app does not own are preserved verbatim, and a rewrite happens only when the app has a reason.

  Background:
    Given the notes folder is "Notes"
    And other tools may edit the same files at any time

  @FMT-01 @test:VaultPathsTest.sanitizeFileName_stripsIllegalChars @test:VaultPathsTest.uniqueName_avoidsCollision @test:VaultFolderBlockerScenariosTest.blocker7_cjkLongTitle_savesAndSyncs
  Scenario Outline: A note is one file named after its title
    Given a note titled "<title>"
    When the note is exported
    Then its file ends with ".md" and contains none of ": / ? * < > |"
    And a long title is cut to at most 200 UTF-8 bytes
    And a taken name gets a "-2", "-3", ... suffix

    Examples:
      | title            |
      | Plan             |
      | vacation: photo? |
      | 会議のメモ       |

  @FMT-01 @test:VaultQuarantineScenariosTest.caseInsensitiveVault_newNoteAvoidsPathOfAnotherMappingWhoseFileIsGone @test:VaultPathsTest.normalize_rejectsParentTraversal
  Scenario: A mapped path whose file is momentarily gone still counts as taken
    Given note "Note" is mapped to "Note.md" and its file is missing from this scan
    When the user creates a second note titled "note" on a case-insensitive volume
    Then the new note gets a different file path
    And ".." can never escape the notes folder

  @FMT-02 @test:VaultNoteDocumentTest.roundTrip_frontMatterAndBody @test:VaultNoteDocumentTest.noteWithoutReminders_hashIsTheLegacyHash @test:VaultNoteIdKeyTest.parse_readsAllNotesId_andRenderWritesItFirst @test:VaultNoteDocumentTest.reminders_roundTrip_asIsoUtcList
  Scenario: Front matter holds the portable metadata
    Given a pinned note "Plan" with a notebook, tags and a reminder
    When the note is written
    Then the file starts with a YAML block
    And the block carries "allnotes-id", "pinned", "archived", "hidden", "isList", "markdown", "color", "notebook", "tags", "reminders", "created" and "modified"
    And "title" is written only when it differs from the file name
    And dates are epoch seconds and reminder times are ISO-8601 UTC

  @FMT-03 @SC-15 @test:VaultNoteDocumentTest.preservedForeignYaml_roundTrip @test:VaultNoteDocumentTest.mergePreserved_blockListTagsUnchanged_keepsWholeBlock @test:VaultNoteDocumentTest.unparseableCreated_isKeptVerbatimAcrossSave @test:VaultFolderBlockerScenariosTest.blocker8_obsidianYamlRoundTrip_tagsScalarAndIsoDates @test:VaultForeignYamlMetadataTest.externalAliases_triggersPull_notConflict @test:VaultNoteIdKeyTest.foreignId_isKeptVerbatim_andIsNotIdentity
  Scenario: Foreign front matter survives every save
    Given "Plan.md" is synced
    When Obsidian adds "aliases: [p]" and a numeric "id: 20240101120000"
    Then the app pulls the change without a conflict
    And both keys are still there after the next save
    And a block-list "tags" and an unparseable "created: sometime in June" round-trip unchanged
    And the numeric "id" is never used as the note's identity

  @FMT-04 @test:VaultNotebookFolderTest.changingNotebook_movesFileAndAssets_andRemovesEmptyFolder @test:VaultNotebookFolderTest.renamingNotebook_movesNotesToRenamedFolder_andNoOldNotebookAfterSync @test:VaultNotebookFolderTest.deletingNotebook_movesNotesToRoot_andNotebookIsNotRecreated @test:VaultNotebookFolderTest.noteInNestedFolderMatchingNotebook_staysInPlace @test:VaultNotebookFolderTest.noteWithoutNotebook_staysAtRoot
  Scenario: A notebook is a folder
    Given "Plan" is in the notebook "Work" and has the attachment "Work/Plan/photo.jpg"
    When the user moves it to the notebook "Home"
    Then the file is "Home/Plan.md" and the attachment is "Home/Plan/photo.jpg"
    And "Work/Plan.md" and "Work/Plan/photo.jpg" are gone
    And the emptied "Work" folder is pruned
    And a note without a notebook stays at the root
    But a folder that still holds other files stays

  @FMT-05 @test:VaultAttachmentsTest.parse_imageLink @test:VaultAttachmentsTest.relativeLinkFromNote_rootNote_keepsAssetDirectorySegment @test:VaultFolderHarnessScenariosTest.blocker1_renameNote_movesOwnedAttachments_andUpdatesPaths @test:VaultAttachmentMembershipTest.exportStripsRemovedLinkWithoutBodyRewrite @test:VaultAttachmentMembershipTest.exportSkipsFencedCodeBlock @test:VaultNoteRelocationOwnedAssetsTest.foreignPathInOldFolder_notListedAsOwned
  Scenario: Attachments live beside the note
    Given "Work/Plan.md" links "Plan/photo.jpg"
    When the user renames the note to "Ideas"
    Then the attachment moves to "Work/Ideas/photo.jpg"
    And the link in the body points at the new relative path
    But removing an attachment strips only its link
    And code blocks that show the same text are untouched
    And a file under another note's asset folder is never treated as owned

  @FMT-10 @test:AttachmentExtensionsTest @test:VaultMediaWriterExtensionTest @test:VaultAttachmentsTest.mergeAttachmentLinks_nonImageUsesFileLink
  Scenario: Vault attachments keep extensions and sensible markdown links
    Given "Work/Plan.md" has a new audio recording without an extension in its label
    When the note is exported to the vault
    Then the recording is stored as a file whose name ends in ".m4a"
    And the body uses a file link for the recording, not an image embed
  @FMT-10 @test:VaultAttachmentsTest.mergeAttachmentLinks_nonImageUsesFileLink
  Scenario: Image attachments still use image markdown
    Given "Work/Plan.md" gains the image "Plan/photo.jpg"
    When the body links are merged
    Then the body contains "![photo.jpg](Plan/photo.jpg)"

  @FMT-06 @gap
  Scenario: Task lists are Markdown checkboxes
    Given a note is a task list
    When it is written
    Then its items are "- [ ]" and "- [x]" lines
    And the front matter says "isList: true"
    # Gap: no automated test asserts the exported checkbox form or isList: true.

  @FMT-06 @test:VaultNoteDocumentTest.isList_falseFromYamlDoesNotForceChecklistUi
  Scenario: isList false in a foreign file does not create a task note
    Given a foreign file contains "isList: false" and a line "- [ ] not a task note"
    When the app imports it
    Then the note is not a task list
    And the line stays ordinary text

  @FMT-07 @test:VaultHousekeepingScanTest.syncConflictFiles_areNotIndexed @test:VaultHousekeepingScanTest.androidTrashFiles_areNotIndexed @test:VaultHousekeepingScanTest.dotTitleNote_isIndexed @test:VaultTrashBinTest.fileUnderTrash_isNeverImported
  Scenario Outline: Housekeeping files are never notes
    Given the folder contains "<path>"
    When the app scans
    Then the file is not imported as a note
    And it is never renamed or deleted by the app

    Examples:
      | path                                        |
      | .obsidian/workspace.json                    |
      | .trash/Plan.md                              |
      | Plan.sync-conflict-20240101-120000.md       |
      | .trashed-1700000000-Plan.md                 |

  @FMT-07 @test:VaultHousekeepingScanTest.dotTitleNote_isIndexed @test:VaultTrashBinTest.dotTitleNote_isKept
  Scenario: A title that starts with a dot is still a note
    Given the folder contains ".NET notes.md"
    When the app scans
    Then it imports the note ".NET notes"

  @FMT-08 @test:VaultFolderBlockerScenariosTest.blocker2_quoteInTitle_fiveSaves_zeroConflictFiles @test:VaultFolderBlockerScenariosTest.blocker2_leadingBlankLine_fiveSaves_zeroConflictFiles @test:VaultImportBaselineOrderingTest.importLifecycle_secondSyncClean_desktopEditPulls_noFalseConflict @test:VaultNoteDocumentTest.noteWithoutReminders_hashIsTheLegacyHash
  Scenario: Change detection ignores formatting the app does not control
    Given a note whose title contains a quote
    When the app saves it five times
    Then no conflict copy appears
    And a leading blank line, quoting or the "allnotes-id" line does not make the file look changed
    And a note written before reminders existed keeps its old hash

  @FMT-09 @test:VaultNoteIdScenariosTest.newFile_gainsAnId_andASecondSyncDoesNotRewriteIt
  Scenario: A file from another tool gains the key the app writes today
    Given the desktop creates "Groceries.md" with only "# Groceries" and a list
    When the app syncs
    Then the file is imported as a note
    And its front matter contains a "allnotes-id"
    And a second sync does not rewrite the file

  @FMT-09 @SC-17 @test:VaultNoteDateBackfillScenariosTest.aFileFromAnotherToolReceivesTheFullRequiredAttributeSet @test:VaultNoteDateBackfillScenariosTest.twoPhonesWithTheSameFileAndMtimeWriteIdenticalBytes @test:VaultNoteDateBackfillScenariosTest.anObsidianFileGainsOnlyTheIdAndModified @test:VaultNoteDateBackfillScenariosTest.anUnparseableCreatedIsKeptUnchanged @test:VaultNoteDateBackfillScenariosTest.anInAppNoteIsNeverRewrittenByTheBackfill
  Scenario: A file from another tool receives the full required attribute set
    Given the desktop creates "Groceries.md" with no front matter
    When the next sync imports it
    Then the file gains "allnotes-id", "created" and "modified" in one write
    And "created" and "modified" are the file's own modification time in epoch seconds
    And every device that imports it writes the same bytes
    And a second sync neither rewrites the file nor pushes the note
    # Gap: today only "allnotes-id" is written. The required set awaits the owner's confirmation.

  @FMT-09 @test:VaultNoteDateBackfillNoShiftTest.externalEditAfterImport_doesNotMoveCreationDate_onTheNextSync @test:VaultNoteDateBackfillNoShiftTest.externalEditAfterImport_withALocalEdit_isPushed_notConflicted @test:VaultNoteDateBackfillFreshNoteTest.aPullInTheSameSync_isWhatTheBackfillWrites_andNothingFlipsBack
  Scenario: Completing dates never moves a note's dates
    Given "Plan.md" was imported earlier and later rewritten by another editor without "created" or "modified"
    When the app syncs
    Then the file gets the note's own dates, the creation date does not jump to the file's new time
    And no conflict copy appears, even with an unsynced local edit

  @FMT-11 @SC-24 @test:VaultPathsNameValidationTest.validateSegmentName_illegalChars_isIllegalChar @test:VaultPathsNameValidationTest.sanitizeFileName_illegalChars_areReplaced
  Scenario: Illegal characters never reach a note or folder name
    Given a note titled "vacation: photo?"
    When it is exported as a file
    Then the validator reports "IllegalChar" for the raw title
    And the file name contains none of "\ / : * ? \" < > |" or control characters

  @FMT-11 @test:VaultPathsNameValidationTest.validateSegmentName_leadingDot_isLeadingDot @test:VaultPathsNameValidationTest.sanitizeFileName_leadingDot_isReplaced
  Scenario Outline: A note or notebook folder never starts with a dot
    Given the name "<name>"
    When it is used as a file or folder segment
    Then the validator reports "LeadingDot"
    And the sanitized segment starts with "_" instead of "."

    Examples:
      | name               |
      | .trash             |
      | .allnotes-staging  |
      | .                  |

  @FMT-11 @test:VaultPathsNameValidationTest.validateSegmentName_trailingDotOrSpace_isTrailingDotOrSpace @test:VaultPathsNameValidationTest.validateSegmentName_reservedNames_isReservedName @test:VaultPathsNameValidationTest.sanitizeFileName_trimsTrailingDotAndSpace @test:VaultPathsNameValidationTest.sanitizeFileName_reservedStem_getsUnderscore
  Scenario Outline: Trailing dots, spaces and Windows reserved names are not used
    Given the name "<raw>"
    When it becomes a file or folder segment
    Then the validator reports "<problem>"
    And the sanitized name is "<sanitized>"

    Examples:
      | raw     | problem             | sanitized |
      | Work.   | TrailingDotOrSpace  | Work      |
      | Work .  | TrailingDotOrSpace  | Work      |
      | CON.md  | ReservedName        | CON_.md   |
      | nul     | ReservedName        | nul_      |

  @FMT-11 @test:VaultPathsNameValidationTest.validateSegmentName_longName_isTooLong @test:VaultPathsNameValidationTest.sanitizeFileName_capsAt200Utf8Bytes
  Scenario: A segment never exceeds 200 UTF-8 bytes
    Given a title of 300 UTF-8 bytes, including multi-byte characters
    When it becomes a file name
    Then the validator reports "TooLong" for the raw title
    And the file name is at most 200 UTF-8 bytes with no character split

  @FMT-11 @test:VaultPathsNameValidationTest.collisionKey_ignoresCase @test:VaultPathsNameValidationTest.collisionKey_normalizesToNfc
  Scenario: Names that differ only by case or Unicode normalization are one folder
    Given the folders "Work" and "work"
    And "Café" written once as NFC and once as NFD
    When the app compares them for "the same folder"
    Then each pair has the same collision key

  @FMT-11 @test:VaultFolderBlockerScenariosTest.blocker7_cjkLongTitle_savesAndSyncs @test:VaultPathsNameValidationTest.titleToNotePath_sanitizesOnlyTheFileName
  Scenario: A note title stays free-form; only its file name is sanitized
    Given a note whose CJK title is longer than 200 UTF-8 bytes
    When the note is saved
    Then the note keeps its title verbatim
    And only the file name is cut to 200 bytes

  @FMT-11 @test:VaultPathsNameValidationTest.sanitizeFileName_alwaysProducesAValidSegment
  Scenario: Every sanitized name is a valid segment
    Given a wide set of inputs, including unicode, emoji and 300-byte strings
    When each input is sanitized
    Then validateSegmentName reports no problem for the result

  @FMT-11 @SC-24 @test:NotebookDialogViewModelTest.aNameStartingWithADot_isRejected @test:NotebookDialogViewModelTest.aNameWithAPathSeparator_isRejected @test:NotebookDialogViewModelTest.aWindowsReservedName_isRejected @test:NotebookDialogViewModelTest.aBlankName_isRejected @test:NotebookDialogViewModelTest.aNameWithATrailingSpace_isRejectedAsTyped
  Scenario Outline: A notebook name is rejected at input, never altered
    Given the user types the notebook name "<name>"
    When the user confirms the notebook dialog
    Then the validator reports "<problem>"
    And the dialog stays open with that problem shown inline
    And no notebook is created or renamed

    Examples:
      | name   | problem            |
      | .trash | LeadingDot         |
      | a/b    | IllegalChar        |
      | CON    | ReservedName       |
      |        | Blank              |
      | Work   | TrailingDotOrSpace |

  @FMT-11 @SC-24 @test:NotebookDialogViewModelTest.aNameThatOnlyDiffersByCaseFromAnExistingOne_isADuplicate @test:NotebookDialogViewModelTest.renamingANotebookToItsOwnName_isValid @test:NotebookDialogViewModelTest.aNewUniqueValidName_isValid
  Scenario: A notebook that would share a folder is refused as a duplicate
    Given the notebook "work" exists
    When the user types "Work"
    Then the name is a Duplicate by collision key
    And renaming that notebook to its own name is valid
    And a new unique valid name is valid

  @FMT-11 @SC-24 @test:NotebookRepositoryNameGuardTest.insert_withAnInvalidName_throws @test:NotebookRepositoryNameGuardTest.update_toAnInvalidName_throwsAndKeepsTheOldName @test:NotebookRepositoryNameGuardTest.insertAndUpdate_withValidNames_pass
  Scenario: A name that cannot be a folder is never stored
    Given a writer bypasses the notebook dialog
    When it inserts or renames a notebook to an invalid name
    Then the repository throws and the old row is unchanged
    And a valid name is saved

  @FMT-11 @SC-24 @test:VaultNotebookFolderTest.aNoteInAPlainFolder_takesTheFolderNameAsItsNotebook_andStaysPut @test:VaultNotebookFolderTest.anInvalidNotebookName_neverBecomesAFolder
  Scenario: A notebook name is exactly its folder name
    Given a note file in the folder "Work"
    When the next sync imports it
    Then the notebook is named "Work" and the file stays at "Work/<title>.md"
    And a name that is not a valid segment never becomes a folder

  @FMT-11 @SC-25 @test:VaultNotebookFolderTest.aNoteInADotFolder_createsNoNotebook_andKeepsItsFile @test:VaultNotebookFolderTest.anInvalidFrontMatterNotebook_createsNoNotebook_andTheNoteHasNone @test:HistoryCatalogApplierSafetyTest.aNotebookCreateWithAnInvalidName_isIgnored @test:HistoryCatalogApplierSafetyTest.aNotebookRenameToAnInvalidName_isIgnored
  Scenario: A name from outside never creates an invalid notebook
    Given a note file appears in ".hidden/" or its front matter names "a/b"
    And another device's log records a notebook create or rename to an invalid name
    When the app syncs
    Then no notebook is created for that name
    And the note keeps its file where it is and has no notebook
    And the sync does not fail

  @FMT-11 @SC-26 @test:BackupRestoreVaultTest.restore_sanitizesAnInvalidNotebookName_withACollisionSuffix_andNotesFollow
  Scenario: A backup with an invalid notebook name restores safely
    Given an old backup contains the notebook "a/b"
    And the folder "a_b" already exists here
    When the backup is restored
    Then the notebook becomes "a_b-2" by collision key
    And its notes follow it into that folder

  @FMT-11 @SC-27 @test:NotebookNameMigrationTest.invalidNames_areRenamedSafely_andTheirNotesFollow @test:NotebookNameMigrationTest.aValidSetOfNames_isLeftUntouched_andTheRunIsMarkedDone
  Scenario: Existing invalid notebooks are renamed once
    Given the database still holds the notebooks ".hidden" and "Work "
    And the notebook "work" already exists
    When the app starts after the upgrade
    Then ".hidden" becomes "_hidden" and "Work " becomes "Work-2"
    And their notes move with them and the rename is recorded in the history
    And a second run changes nothing
