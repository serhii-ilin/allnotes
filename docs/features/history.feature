@history
Feature: Folder history
  Facts that note files cannot express are recorded as per-device event logs under .allnotes/ in the notes folder.
  Each device writes only its own files, so the sync tool never creates a conflict between devices. Phones A, B and C
  share one folder through Syncthing.

  Background:
    Given phones A and B use the same notes folder
    And each has written its manifest under ".allnotes/devices/<its id>/device.json"

  # ---------- Release A: recording and showing ----------

  @HST-01 @HST-04 @SC-20 @test:FolderHistoryScenariosTest.aUserBin_isRecordedInTheDevicesOwnLogOnly @test:HistoryWriterTest.aUserBin_isRecordedInTheDevicesOwnLogOnly @test:HistoryWriterTest.recordedEvents_carryOnlyTheDocumentedFields_noNoteText
  Scenario: A user bin is recorded in the device's own log only
    Given note "Plan" with allnotes-id "3f2a" at "Plan.md" on phone A
    When the user bins "Plan" on phone A and A syncs
    Then A's file ".allnotes/devices/<A>/events-2026-11.jsonl" ends with a "note.bin" event for id "3f2a" from "Plan.md" to ".trash/Plan.md"
    And the event contains no note text
    And no file under another device's folder changed

  @HST-04 @test:FolderHistoryScenariosTest.aBinCausedByAMissingFile_recordsNothing
  Scenario: A bin caused by a missing file records nothing
    Given note "Plan" whose file disappeared from two complete scans on phone A
    When A bins it because the file is missing
    Then A's log has no new "note.bin" event

  @HST-01 @test:HistoryWriterTest.theReadme_isWrittenOnceWithIdenticalBytes
  Scenario: The README is written once with identical bytes
    Given ".allnotes/README.md" is missing
    When phones A and B each record an event before receiving each other's files
    Then both write byte-identical README files
    And a later sync on either phone does not rewrite it

  @HST-01 @test:VaultHistoryStorageBackendTest.theNoteScan_ignoresTheHistoryFolder
  Scenario: The note scan ignores the history folder
    Given ".allnotes/devices/<A>/events-2026-11.jsonl" exists
    When phone B scans the folder
    Then no note is imported from ".allnotes/"

  @HST-02 @test:HistoryWriterTest.aClonedInstall_switchesToANewId
  Scenario: A cloned install switches to a new id
    Given phone C was restored from a copy of phone A's app data
    And A's log contains seq 1..10 under A's id
    When C syncs and finds lines under its own id that it never wrote
    Then C generates a new device id
    And records "device.reid" naming the old id in its new folder

  @HST-03 @SC-21 @test:HistoryReaderTest.aSyncthingConflictCopyOfALog_isReadOnce
  Scenario: A Syncthing conflict copy of a log is read once
    Given A's log and "events-2026-11.sync-conflict-20261110-120000-ABCDEF.jsonl" both contain event "<A>:7"
    When phone B reads the history
    Then event "<A>:7" appears once

  @HST-03 @test:HistoryReaderTest.wallClockSkew_doesNotReorderHistory
  Scenario: Wall-clock skew does not reorder history
    Given phone B's clock is 5 hours behind
    And A records "note.bin" for "3f2a" and then B records "note.restore" for "3f2a" after receiving A's log
    When any phone reads the history
    Then the restore is ordered after the bin

  @HST-05 @test:FolderHistoryScenariosTest.binningWhileTheFolderIsUnavailable_recordsTheEventLater @test:HistoryWriterTest.aFlushThatFailsOnce_keepsTheOutboxAndRetries
  Scenario: Binning while the folder is unavailable records the event later
    Given the notes folder is unavailable on phone A
    When the user bins "Plan"
    Then the bin succeeds and the event waits in A's outbox
    And when the folder is available again the next sync writes the event to A's log and clears the outbox

  @HST-06 @test:VaultFolderHistoryTest.readingToleratesNewerVersionsAndDamage @test:HistoryReaderTest.extraField_isShownNormally @test:HistoryReaderTest.unknownOp_isShownButNeverApplied @test:HistoryReaderTest.malformedLine_isSkippedAndCounted @test:HistoryReaderTest.newerVersionEventWithoutManifest_isDisplayOnly
  Scenario Outline: Reading tolerates newer versions and damage
    Given phone C's log contains <line>
    When phone A reads the history
    Then reading succeeds
    And <outcome>

    Examples:
      | line                                         | outcome                                                    |
      | an event with an extra field "color"         | the event is shown normally                                |
      | an event with op "note.pin"                  | it is shown as "From a newer version" and never applied    |
      | a line that is not valid JSON                | it is skipped and counted                                  |
      | a "v": 2 event while C has no device.json    | it is display-only                                         |

  @HST-07 @test:VaultFolderHistoryTest.theFolderHistoryScreen_listsMergedEvents @test:VaultFolderHistoryTest.aMissingHistoryFolder_showsAnEmptyState @test:MultiDeviceHistoryTest.aBinOnAPhone_appearsInTheOtherPhonesFolderHistory
  Scenario: The Folder history screen lists merged events
    Given phone A binned "Plan" and phone B created notebook "Trips"
    When the user opens Settings, Notes folder, Folder history on phone A
    Then both events are listed newest first with their device labels like "Pixel 9 · a1b2c3"

  @HST-08 @test:FolderHistoryScenariosTest.deletingTheHistoryFolder_changesNoNote
  Scenario: Deleting the history folder changes no note
    Given notes, notebooks and tags synced on phones A and B
    When ".allnotes/" is deleted and both phones sync
    Then no note, notebook or tag changed on either phone
    And the next recorded action recreates the device's folder and manifest

  @HST-03 @test:MultiDeviceHistoryTest.threePhones_convergeWhateverTheDeliveryOrder @test:HistoryReaderTest.everyReaderComputesTheSameOrder
  Scenario: Three phones compute the same merged history
    Given phones A, B and C each record actions
    When Syncthing delivers their logs to each other in shuffled order
    Then all three read the same events in the same order

  # ---------- Release B: using the history in sync ----------

  @HST-09 @SC-22 @test:HistoryNoteApplierScenariosTest.aBinOnOnePhone_binsTheNoteOnTheOtherWithoutWaitingTwoScans @test:HistoryNoteApplierScenariosTest.aBinOnOnePhone_binsTheOnlyNoteWithoutWaitingTwoScans @test:HistoryNoteApplierScenariosTest.syncingAgainAfterApplyingABin_changesNothingTheSecondTime
  Scenario: A bin on one phone bins the note on the other without waiting two scans
    Given note "Plan" is live on phones A and B
    When the user bins "Plan" on A and Syncthing delivers A's log and the moved file to B
    Then after B's next sync "Plan" is in B's bin as a user bin
    And B moved or deleted no file

  @HST-09 @test:HistoryNoteApplierScenariosTest.aPurgeOnOnePhone_clearsTheBinOnTheOtherWithoutDeletingFiles
  Scenario: A purge on one phone clears the bin on the other without deleting files
    Given "Plan" is in the bin on phones A and B
    When the user empties the bin on A
    Then after B's next sync "Plan" is gone from B's bin
    And applying the event deleted no file on B

  @HST-09 @HST-11 @test:HistoryNoteApplierScenariosTest.aBinDoesNotOverrideAnUnsyncedEdit @test:MultiDeviceConvergenceTest.aBinOnAWhileBEditsTheSameNote_keepsTheEditAndConverges
  Scenario: A bin does not override an unsynced edit
    Given phone B has an unsynced edit of "Plan"
    When B receives A's "note.bin" for "Plan" and syncs
    Then "Plan" stays live on B with the edit
    And B's Folder history lists a mismatch for that event

  @HST-10 @test:HistoryCatalogApplyScenariosTest.anEmptyNotebookTravels @test:HistoryCatalogApplyScenariosTest.anEmptyTagTravels
  Scenario: An empty notebook travels
    When the user creates empty notebook "Trips" on phone A
    Then after phone B syncs, B has notebook "Trips"

  @HST-10 @test:HistoryCatalogApplyScenariosTest.aNotebookDeleteDoesNotRemoveANotebookStillInUse @test:HistoryCatalogApplyScenariosTest.aTagDeleteDoesNotRemoveATagStillInUse
  Scenario: A notebook delete does not remove a notebook still in use
    Given phone B has a note in notebook "Work" that A does not know yet
    When A deletes notebook "Work" and B syncs
    Then B keeps notebook "Work" while its note uses it
    And once the note leaves "Work", the next sync deletes it

  @HST-11 @test:HistoryNoteApplierScenariosTest.withFolderHistoryForSyncOff_bIgnoresTheBinUntilTheTwoScanRuleBinsIt @test:HistoryCatalogApplyScenariosTest.switchedOff_appliesNothing
  Scenario: The feature can be switched off per device
    Given "Use folder history for sync" is off on phone B
    When B receives A's "note.bin" for "Plan" and syncs
    Then "Plan" stays live until the two-scan rule bins it
    And events are still recorded and shown

  @HST-12 @SC-23 @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder_withAClockFiveHoursBehind @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder_afterADuplicatedLogArrives @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder_withANewerFormatDevice @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder_afterDeletingTheHistoryFolderMidRun @test:MultiDeviceConvergenceTest.threePhones_convergeWhateverTheDeliveryOrder_withAClonedDevice
  Scenario Outline: Three phones converge whatever the delivery order
    Given phones A, B and C start from the same folder
    When they perform random note, bin, restore, purge, notebook and tag actions with seed <seed>
    And Syncthing delivers files between them in shuffled order, with duplicates and conflict copies
    Then all three phones end with the same notes, notebooks and tags
    And no note text was lost and no file was deleted by applying events

    Examples:
      | seed |
      | 1    |
      | 2    |
      | 3    |

  @HST-09 @test:HistoryNoteApplierSafetyTest.aBinOfTheOldPath_keepsANoteRenamedHereMeanwhile
  Scenario: A bin never bins a note renamed here meanwhile
    Given phone B renamed "Plan" to "Ideas" before A's bin of "Plan.md" arrived
    When B syncs
    Then "Ideas" stays live and B lists "Renamed or moved here; kept"

  @HST-09 @test:HistoryNoteApplierScenariosTest.theLogArrivesBeforeTheMovedFile_thenBinsWhenTheFileMoveArrives @test:HistoryNoteApplierSafetyTest.anEmptyScan_explainedOnlyInPart_isStillAGlitch
  Scenario: A bin waits for the moved file, and an unexplained empty scan stays a glitch
    Given A's log reached phone B but "Plan.md" is still in B's folder
    When B syncs
    Then "Plan" stays live until the file leaves
    And an empty scan that the history explains only in part changes no note

  @HST-09 @test:HistoryNoteBinImportTest.aBinOfANoteThatNeverReachedTheDevice_importsItIntoTheBin @test:HistoryNoteBinImportTest.aRestoreOnTheOtherPhone_restoresANoteImportedIntoTheBin @test:HistoryNoteBinImportTest.aPurgeOnTheOtherPhone_removesANoteImportedIntoTheBin @test:HistoryNoteBinImportTest.aBinWithoutTheTrashedFile_isNotImported
  Scenario: A note binned before it reached a phone appears in that phone's bin
    Given phone A created and binned "Draft" before phone B ever received it
    When B receives A's log and ".trash/Draft.md" and syncs
    Then "Draft" is in B's bin and can be restored or purged like any user bin

  @HST-10 @HST-12 @test:HistoryCatalogConvergenceTest.concurrentNotebookRenames_convergeToTheSameName @test:HistoryCatalogConvergenceTest.concurrentTagRenames_convergeToTheSameName @test:HistoryCatalogApplierSafetyTest.aUsedNotebookRename_whoseLogArrivesFirst_waitsForTheFiles_andNothingConflicts @test:HistoryCatalogApplierSafetyTest.aUsedTagRename_whoseLogArrivesFirst_waitsForTheFiles_andNothingConflicts
  Scenario: Renames converge, even concurrent ones, without pushing notes
    Given phone A renames notebook "N" to "X" and phone B renames "N" to "Y" before seeing A's log
    When all logs and files are delivered and both phones sync
    Then both phones end with the same notebooks, the later rename in merged order having won
    And no phone pushed its notes because of a rename, and no conflict copy appeared

  @HST-04 @test:HistoryRetentionPurgeTest
  Scenario: The bin's retention cleaner purges only on this device
    Given phone A's bin keeps notes for 7 days and phone B's for 30
    When A's retention cleaner permanently deletes a 10-day-old binned note
    Then A records no "note.purge" event
    And the note stays in B's bin until B's own retention or B's user removes it

  @HST-09 @HST-12 @test:HistoryOwnActionsBeforeApplyTest.aNewEmptyNotebookOnThisDevice_survivesAnOlderRemoteDelete @test:HistoryOwnActionsBeforeApplyTest.aLocalBinWithAFailedMove_isNotUndoneByAnOlderRemoteRestore @test:HistoryQuarantinedFileTest.aQuarantinedMappedFile_countsAsPresent_soTheBinWaits @test:HistoryEmptyScanExplainedTest.anEmptyScanExplainedOnlyInPart_changesNothingAndFails @test:HistoryEmptyScanExplainedTest.anEmptyScanExplainedForEveryNote_appliesTheBinsAndSucceeds @test:HistoryNoteBinImportBeforeMoveTest.aBinArrivingBeforeTheMovedFile_waitsWhileTheLiveFileIsStillScanned
  Scenario: What this device just did, and what it cannot see, is never overridden
    Given the user just created notebook "X" or binned a note on this phone
    And an older event from another phone says the opposite
    When this phone syncs
    Then the user's action stands
    And a quarantined file, a partial empty scan or a not-yet-moved file never leads to a bin

  @HST-13 @test:EachPhoneRemovesItsOwnOldHistoryTest.threeMonths_deletesJuly_andKeepsAugustAndNovember @test:EachPhoneRemovesItsOwnOldHistoryTest.forever_keepsEveryFile @test:EachPhoneRemovesItsOwnOldHistoryTest.anotherDevicesOldLog_isNeverDeleted @test:EachPhoneRemovesItsOwnOldHistoryTest.aConflictCopyOfAnOldOwnMonth_isDeletedToo @test:EachPhoneRemovesItsOwnOldHistoryTest.aFailedDelete_isRetriedOnTheNextFlush_andNeverFailsTheFlush @test:EachPhoneRemovesItsOwnOldHistoryTest.afterPruning_folderHistoryShowsWhatRemains_andBothPhonesStillConverge
  Scenario Outline: Each phone removes its own old history after the kept period
    Given today is 2026-11-11 and phone A keeps folder history for <kept>
    And A's folder holds "events-2026-07.jsonl", "events-2026-08.jsonl" and "events-2026-11.jsonl"
    And phone B's folder holds "events-2026-01.jsonl"
    When A syncs
    Then A's remaining files are <remaining>
    And B's "events-2026-01.jsonl" is untouched

    Examples:
      | kept     | remaining                                                       |
      | 3 months | "events-2026-08.jsonl", "events-2026-11.jsonl"                  |
      | Forever  | "events-2026-07.jsonl", "events-2026-08.jsonl", "events-2026-11.jsonl" |

  @HST-13 @test:HistoryPruneReferenceTimeTest.aClockAheadOnThisDevice_isOverruledByTheOtherDevices @test:HistoryPrunerClockSkewTest.aClockFiveYearsAhead_doesNotDeleteEveryOwnLog @test:HistoryPrunerClockSkewTest.theMonthHoldingThisDevicesNewestOwnEvent_isNeverDeleted
  Scenario: A phone with a wrong clock does not wipe its own history
    Given phone A's clock is five years ahead and phone B's logs are current
    When A syncs with "Keep folder history" at 3 months
    Then A deletes nothing that phone B's clock would keep
    And the month of A's newest own event is never deleted
