# Conflicts (CON)

What happens when the app and another device or tool changed the same note. Use case: UC-11. Scenarios: SC-02, SC-04,
SC-05. Gherkin: [conflicts.feature](../features/conflicts.feature).

### CON-01 A true conflict keeps both versions
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultSyncOrchestrationTest.trueConflict_recordsOncePerRemoteHash`, `VaultFolderBlockerScenariosTest.blocker2_externalEditThenAppEdit_oneConflictCopy`, `StorageBackendPrePushConflictTest`
When both the note and its file changed since the baseline, the app keeps its own version in the note. It saves the
file's version as `<name> (conflict <epoch>).md` next to it and tells the user once per remote version. The same
applies when a push finds the file changed underneath it.

**Acceptance criteria**
- AC1: A true conflict leaves the app's text in the note and writes the file's text to `<name> (conflict <epoch>).md`.
- AC2: The same remote version is recorded and announced once, not on every sync.
- AC3: A push that finds a changed file applies the same conflict rule instead of overwriting it.

**Gherkin:** [`conflicts.feature`](../features/conflicts.feature) — `@CON-01`

### CON-02 Conflict copies are visible in the app
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultDuplicateIdScenariosTest.conflictCopy_carriesItsOwnId_andIsListedInSettings`, `SettingsViewModelConflictsTest`
Settings → **Sync conflicts** lists every conflict copy and opens it in the editor, so the user can merge and delete
it. Each copy is written with its own id and is imported as its own note, so a shared id never hides it.

**Acceptance criteria**
- AC1: A conflict copy is created with an id distinct from the note it came from.
- AC2: Settings → Sync conflicts lists every mapped conflict-named file with its title and path.
- AC3: A binned conflict copy is not listed.
- AC4: Choosing a listed copy opens it in the editor.

**Screens:** DLG-16, SCR-09
**Gherkin:** [`conflicts.feature`](../features/conflicts.feature) — `@CON-02`

### CON-03 No false conflicts
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderBlockerScenariosTest.blocker2_*`, `VaultForeignYamlMetadataTest`, `VaultAttachmentDirtyRefreshTest`, `VaultRemoteRenameNoConflictTest`, `VaultExportStampScenariosTest`
None of these is a conflict: repeated saves, formatting the app normalizes, foreign YAML edits, attachment-only
changes, the app adding a `allnotes-id`, or a rename or move made elsewhere.

**Acceptance criteria**
- AC1: Five saves of a note whose title contains a quote produce zero conflict copies.
- AC2: A foreign-YAML edit, an attachment-only change and the app adding its id are pulls or pushes, not conflicts.
- AC3: A rename or move made elsewhere, including a title rename that changes `modified`, is a pull.
- AC4: A note exported moments ago can be edited or renamed elsewhere without a conflict copy.

**Gherkin:** [`conflicts.feature`](../features/conflicts.feature) — `@CON-03`

### CON-04 The sync tool's conflict files are left alone
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultHousekeepingScanTest.syncConflictFiles_areNotIndexed`
Syncthing `*.sync-conflict-*` files are not imported, renamed or deleted. Resolving them is up to the user.

**Acceptance criteria**
- AC1: A `*.sync-conflict-*` file is not imported as a note.
- AC2: The app never renames or deletes it.

**Gherkin:** [`conflicts.feature`](../features/conflicts.feature) — `@CON-04`
