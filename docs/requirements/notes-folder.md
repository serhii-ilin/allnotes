# Notes folder (FLD)

Choosing, confirming and switching the folder that holds the notes. Use cases: UC-01, UC-02, UC-12.
Gherkin: [notes-folder.feature](../features/notes-folder.feature).

### FLD-01 One folder is the only storage mode
**Status:** Implemented · **Scope:** Core · **Tests:** `PreferenceRepositoryLegacyKeysTest`
Notes are stored in one user-chosen Storage Access Framework (SAF) folder. Nextcloud and other server sync modes are
removed. Old `DISABLED`/`NEXTCLOUD` settings migrate to folder mode, and the server is never contacted.

**Acceptance criteria**
- AC1: An install carrying `cloud_service=NEXTCLOUD` and `sync_mode=WIFI` reads the default folder-mode preferences
  without an error.
- AC2: No server mode can be selected and no server is contacted.

**Screens:** SCR-09
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-01`

### FLD-02 The app works before a folder is chosen
**Status:** Implemented · **Scope:** Core · **Tests:** `SynchronizeNotesTest` (`vault does not export when cutoff unset`)
Without a folder, notes live in the app database and everything works offline. The onboarding dialog and the drawer
ask the user to choose a folder.

**Acceptance criteria**
- AC1: A note created with no folder configured is saved and shown as an ordinary note.
- AC2: A sync with no confirmed folder writes nothing into any folder.

**Screens:** DLG-01, SCR-13
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-02`

### FLD-03 First configuration exports existing notes once
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultSyncOrchestrationTest.legacyNoteBeforeCutoff_exportedWhenFolderConfigured`, `VaultFolderBlockerScenariosTest.blocker3_existingFolder_noDuplicates_noSuffixFiles`
After a folder is chosen, every note without a file is written into it. A note whose title and body already match a
file in the folder is linked to that file, not duplicated. Running the export again creates nothing new.

**Acceptance criteria**
- AC1: After the first folder pick, every note without a file has one `<title>.md` in the folder.
- AC2: A note whose title and body match an existing file is linked to it, not duplicated.
- AC3: A second sync adds no suffix files and no second note.

**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-03`

### FLD-04 An upgraded install confirms its folder before using it
**Status:** Implemented · **Scope:** Core · **Tests:** `BackendProviderTest.unconfirmedFolder_hasNoBackend_untilConfirmed`, `VaultUpgradeSafetyTest.unconfirmedFolder_syncChangesNothing`, `ActivityViewModelUpgradePromptTest`
A folder carried over from the old app is neither read nor written, by sync or by note saves, until the user confirms
it. The confirmation prompt survives activity recreation.

**Acceptance criteria**
- AC1: With a folder but no confirmation, the backend is absent and sync reads and writes nothing.
- AC2: A note save still works and creates no mapping in that folder.
- AC3: After confirmation the next sync exports and imports as usual.
- AC4: The prompt stays available to a recreated activity until the user answers.

**Screens:** DLG-02
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-04`

### FLD-05 Legacy mappings are relinked without duplicates
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultUpgradeSafetyTest`, `VaultFolderBlockerScenariosTest.blocker4_7_legacyContentUriRow_threeSyncs_oneMappingNoJunk`
Old `content://` mappings are relinked to files by content (title and body first, then body). A file already mapped to
another note is never reused. A mapping that matches no file is dropped, and its note is exported on the following
sync.

**Acceptance criteria**
- AC1: A legacy mapping whose body matches a file in the folder is relinked to that one file.
- AC2: A file that matches a different note's body is never shared; the legacy note gets its own file.
- AC3: A mapping that matches no file is dropped and the note is exported on the next sync.

**Screens:** DLG-02
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-05`

### FLD-06 Re-picking the same folder changes nothing
**Status:** Implemented · **Scope:** Core · **Tests:** `SafVaultWriteStoreRecoveryTest.sameTreeUnderDifferentUriEncodings_sharesOneScope`, `SettingsViewModelFolderSwapTest`
Choosing the current folder again, even through a differently encoded URI, keeps all notes and mappings.

**Acceptance criteria**
- AC1: Two URI encodings of the same tree resolve to one vault scope.
- AC2: Re-picking that tree shows no swap prompt and keeps every mapping.
- AC3: The newly picked URI string is stored.

**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-06`

### FLD-07 Picking a different folder asks what to do
**Status:** Implemented · **Scope:** Core · **Tests:** `SettingsViewModelFolderSwapTest`, `VaultFolderSwapScenariosTest`
The user chooses **Move my notes here**, **Open this folder as-is** or **Cancel**. The question survives rotation until
it is answered.

**Acceptance criteria**
- AC1: Picking a different folder emits a prompt and changes no mapping and no preference until answered.
- AC2: The prompt is still there for an activity recreated by rotation.
- AC3: **Cancel** leaves every note, mapping and file unchanged and syncs nothing.

**Screens:** DLG-03
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-07`

### FLD-08 "Move my notes here" copies, and never deletes in the old folder
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderMoveKeepsOldFilesTest`, `VaultFolderSwapScenariosTest.move_intoFolderWithMatchingFile_linksInsteadOfDuplicating`
The notes are written into the new folder: a note whose file already exists there is linked, every other note gets a
file. Nothing in the old folder is deleted, even when the new folder contains the old one or both resolve to the same
directory; a file another note owns is never touched. Afterwards the user sees "Your notes were copied to the new
folder. The previous folder still has the old files; you can delete them yourself." (`vault_move_old_files_kept`).

**Acceptance criteria**
- AC1: Every note has a readable file in the new folder after the move.
- AC2: A note matching a file in the new folder is linked to it instead of duplicated.
- AC3: No file and no asset in the old folder is deleted or modified, including when the folders overlap.
- AC4: The user is told that the old files were kept and can be deleted by hand.

**Screens:** DLG-03
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-08`

### FLD-09 "Open this folder as-is" swaps the note set
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderSwapScenariosTest.openAsIs_onlyNewFolderNotesRemain_oldFolderFilesUnchanged`
The notes backed by the old folder are removed from the app and their files are left untouched. The new folder's notes
are imported. Notes that never had a file are kept and exported.

**Acceptance criteria**
- AC1: After the swap only the new folder's notes are shown; notes backed by the old folder are gone from the app.
- AC2: Every file in the old folder keeps its bytes.
- AC3: Notes that never had a file are kept and exported into the new folder.

**Screens:** DLG-03
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-09`

### FLD-10 Warn about folders provided by another app
**Status:** Implemented · **Scope:** Core · **Tests:** `CloudFolderWarningTest`
When the folder is not on device storage (a cloud drive's provider, for example), the user is warned once per folder
that listings may be unreliable.

**Acceptance criteria**
- AC1: A cloud-provider folder warns the first time it is configured.
- AC2: The same tree, even differently encoded, does not warn again; another cloud tree does.
- AC3: A folder on device storage never warns.

**Screens:** DLG-04
**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-10`

### FLD-11 Folders are pruned only when listings are trusted
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderPruningTest`
A folder emptied by a move or bin is removed only on device storage, and only after two consecutive empty listings. A
failed listing never counts as empty.

**Acceptance criteria**
- AC1: With pruning on, a folder whose last note left is removed after two empty listings.
- AC2: A folder whose second listing still shows a file is kept.
- AC3: With pruning off, the folder stays.

**Gherkin:** [`notes-folder.feature`](../features/notes-folder.feature) — `@FLD-11`
