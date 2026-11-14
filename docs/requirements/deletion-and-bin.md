# Bin, deletion and missing files (DEL)

What happens to files when notes are binned or deleted, and when files disappear. Use case: UC-07.
Scenarios: SC-06, SC-07, SC-08, SC-10, SC-14. Gherkin: [deletion-and-bin.feature](../features/deletion-and-bin.feature).

### DEL-01 Binning moves the file into `.trash/`
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultTrashBinTest`
Binning a note moves its file to `.trash/<original path>`, with a suffix if that name is taken. Assets stay in place.
Restoring moves the file back, or to a free sibling name if the path is now taken. If the bin file is gone, restore
writes a new file. A bin made while the folder is unavailable is applied once it is available.

**Acceptance criteria**
- AC1: Binning `Work/Plan.md` creates `.trash/Work/Plan.md` and leaves `Work/Plan/pic.png` in place.
- AC2: A taken trash name gets a suffix instead of overwriting.
- AC3: Restoring puts the file back with its assets; a taken original path uses a free sibling; a missing trash file is
  recreated.
- AC4: A bin made with the folder unavailable stays pending and is applied on the first sync that can write.

**Screens:** SCR-04
**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-01`

### DEL-02 Permanent delete removes only the note's own bytes
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultTrashBinTest.permanentDelete_neverTouchesAFileNowAtTheOriginalPath`, `VaultMissingFileSafetyTest.userBinnedNote_permanentDeleteStillRemovesVaultFile`, `VaultMissingFileSafetyTest.noteBinnedByMissingFile_emptyingBinKeepsFileThatCameBack`
Emptying the bin, or deleting a binned note, removes its `.trash/` file and its assets only when the user binned it. A
file that now sits at the note's original path and belongs to another note is never touched.

**Acceptance criteria**
- AC1: Emptying the bin removes the `.trash/` file and the note's owned assets.
- AC2: A new file at the note's original path is left untouched.
- AC3: A note binned only because its file went missing never deletes a file that has since come back.

**Screens:** SCR-04
**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-02`

### DEL-03 A missing file is not a deletion until it is missing twice
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultMissingFileSafetyTest`, `SynchronizeNotesMissingFileTest`
A mapped file absent from one complete scan only increments a counter, which resets when the file returns. Absent from
two consecutive complete scans, the note moves to the bin marked "not user-binned". Emptying that bin never deletes
any file, and the note comes back if the file returns.

**Acceptance criteria**
- AC1: One complete scan without the file keeps the note and mapping and sets the miss count to 1.
- AC2: The count resets when the file comes back.
- AC3: Two consecutive complete scans without the file bin the note without marking it user-binned.
- AC4: Emptying the bin for such a note deletes no file, and a returning file restores the note.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-03`

### DEL-04 Many files missing at once is treated as a glitch
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultMissingFileSafetyTest.threeOfFiveMissing_syncProceeds_butKeepsNotesWithoutCounting`
When at least 3 mapped files and more than half of all mapped files are missing, the sync proceeds but nothing is
binned or counted, and the user sees one message.

**Acceptance criteria**
- AC1: Three of five files missing produces `Success`, bins nothing and counts nothing.
- AC2: The other notes still sync normally.
- AC3: Two of ten missing files use the normal two-strike rule instead.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-04`

### DEL-05 Deletes made on another device bin the note here
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultMissingFileSafetyTest.missingOnTwoScans_isBinned_withoutMarkingUserBin`, `VaultFolderBlockerScenariosTest.externallyDeletedMappedFile_doesNotFailScan`, `SynchronizeNotesFileReturnTest.blocker5_binnedLocalAndReturnedRemote_pullsInsteadOfRemoteDelete`
A file deleted elsewhere (including moved into `.trash/` by another device) bins the note here after DEL-03. A file
that reappears restores it.

**Acceptance criteria**
- AC1: A file deleted on another device bins the note here after two complete scans, without marking it user-binned.
- AC2: The scan does not fail because a mapped file was deleted externally.
- AC3: If the file reappears, the note is restored rather than deleted remotely.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-05`

### DEL-06 Android's own trash is not shown as notes
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultTrashBinTest`, `VaultAndroidTrashRetirementTest`
Files Android renamed to `.trashed-<n>-<name>` are not imported. Notes that were imported from such files before this
rule move to the bin under their original title, and are never shown with the `.trashed-` prefix.

**Acceptance criteria**
- AC1: A `.trashed-<n>-<name>` file is not imported as a note and is never renamed or deleted.
- AC2: A note already imported from such a file moves to the bin under its original title and stays there.
- AC3: A trashed file that copies an existing note is dropped without deleting the live note.
- AC4: Emptying the bin fully cleans up a retired Android-trash row.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-06`

### DEL-07 Bins are dated and the clean purges old ones
**Status:** Implemented · **Scope:** Core · **Tests:** `HistoryNoteBinImportTest.aBinOfANoteThatNeverReachedTheDevice_importsItIntoTheBin`
Each bin entry carries the time it was binned: a user bin, a missing-file bin and a bin received from another device all
set it, and restoring clears it. The periodic bin clean permanently deletes a binned note once its deletion date is
older than the retention period and leaves a newer one; the period and its options/default are SET-11. With Never the
clean purges nothing, and a binned note with no deletion date is never purged; a note already in the bin when Instantly
is chosen is left there by the clean.

**Acceptance criteria**
- AC1: A bin received from another device, a user bin and a missing-file bin all carry a deletion date.
- AC2: Restoring a note clears its deletion date.
- AC3: The clean purges a note whose deletion date is older than the chosen period and keeps one inside it.
- AC4: With Never nothing is purged automatically, and a binned note with no deletion date is never purged.
- AC5: With Instantly a note already in the bin is left there by the clean.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-07`

### DEL-08 The bin clean runs about every five hours
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
At app start the app enqueues one periodic bin clean named `BIN_CLEAN` with a five-hour interval and keeps an existing
schedule on later starts. The system may delay a run. A run uses the retention value in force at that run, and the work
has no network, charging or battery constraint.

**Acceptance criteria**
- AC1: App start schedules a bin clean with a five-hour interval.
- AC2: A later start keeps the existing schedule instead of adding a second clean.
- AC3: A run uses the retention value in force at the time it runs.
- AC4: The clean runs without network, charging or battery requirements.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-08`

### DEL-09 An automatic purge stays on this device
**Status:** Implemented · **Scope:** Core · **Tests:** `HistoryRetentionPurgeTest.theRetentionCleaner_doesNotRecordAPurge`, `HistoryRetentionPurgeTest.anExplicitPermanentDelete_stillRecordsAPurge`, `HistoryNoteBinImportTest.aPurgeOnTheOtherPhone_removesANoteImportedIntoTheBin`
A purge by the automatic clean records no `note.purge`, so another device keeps its bin entry until its own retention
expires or the user empties its bin (HST-04). Permanently deleting a user-binned note (Empty bin or that note's own
delete) records a purge, which drops the entry on other devices without deleting any file there (HST-09). On this device
a purge removes the note's own `.trash/` file and owned assets when the user binned it (DEL-01, DEL-02).

**Acceptance criteria**
- AC1: An automatic purge adds no purge event to the folder history.
- AC2: Another device keeps its bin entry after this device's automatic purge.
- AC3: Permanently deleting a user-binned note records a purge, which removes other devices' entries without deleting
  files there.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-09`

### DEL-10 A clean deletes private media that no note uses
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
A clean with a positive retention period deletes files in the app's private media folder that no remaining note
references. A file referenced by any note, including a binned one, stays. Empty bin and an instant delete run the same
cleanup. This covers private media only; vault assets follow DEL-02. With Never or Instantly the automatic clean does no
media cleanup.

**Acceptance criteria**
- AC1: A private media file referenced by no note is deleted by the clean.
- AC2: A private media file referenced by any note, including a binned one, stays.
- AC3: Empty bin and an instant delete run the same cleanup.
- AC4: With Never or Instantly the automatic clean performs no cleanup.

**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-10`

### DEL-11 Empty bin clears every entry
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultTrashBinTest.permanentDeleteFromBin_removesTrashFileAndAssets`, `VaultMissingFileSafetyTest.noteBinnedByMissingFile_emptyingBinKeepsFileThatCameBack`, `VaultTrashBinTest.missingFileBin_thenPermanentDelete_touchesNothingInTheVault`, `VaultAndroidTrashRetirementTest.emptyingTheBin_removesARetiredTrashRow_andLeavesTheTrashedFileAlone`
**Empty bin** permanently deletes every note in the bin, not only the selected ones. A note the user binned loses its
`.trash/` file and owned assets (DEL-02); a note binned by a missing file or by Android's trash loses only its local row
and mapping, and its file stays (DEL-03, DEL-06). The action then deletes private media that no note uses (DEL-10).

**Acceptance criteria**
- AC1: Empty bin clears every note in the bin, whoever binned it.
- AC2: A user-binned note's `.trash/` file and owned assets are deleted.
- AC3: A missing-file or Android-trash bin loses its row and mapping but no file.
- AC4: Private media that no note uses is deleted after the bin is cleared.

**Screens:** DLG-15, SCR-04
**Gherkin:** [`deletion-and-bin.feature`](../features/deletion-and-bin.feature) — `@DEL-11`
