# Write safety and recovery (WRS)

Every write to the folder survives a crash, and stale work never lands in the wrong folder. Use case: UC-14.
Scenario: SC-16. Gherkin: [write-safety.feature](../features/write-safety.feature).

### WRS-01 Writes are journaled and verified
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRecoverableWriteTest`, `SafVaultWriteStoreRecoveryTest`
A note write is staged, recorded in a journal with a backup of the previous bytes (in app-private storage, never in
the synced tree), published, and read back to verify it. A failed or truncated publish restores the previous bytes.

**Acceptance criteria**
- AC1: A successful write publishes the staged bytes, verifies them and leaves no journal entry.
- AC2: A failed publish restores the previous verified bytes and clears the journal.
- AC3: A truncated publish is detected and the backup is restored.
- AC4: A later write succeeds after an earlier publish failure.

**Gherkin:** [`write-safety.feature`](../features/write-safety.feature) — `@WRS-01`

### WRS-02 Interrupted writes are recovered or quarantined
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRecoveryServiceTest`, `VaultRecoveryPersistenceTest`, `VaultQuarantineScenariosTest`
Before the next scan or write, pending journals are completed or rolled back, verified by hash. A path that cannot be
settled is quarantined: it is not read, written, imported or binned, and the database keeps its copy of the note. A
journal from an earlier pick of the same folder is restored, and one from another folder is never applied.

**Acceptance criteria**
- AC1: A journal left by a crash is completed from staging or rolled back from backup on the next start.
- AC2: A path with no recoverable staging or backup is quarantined and the note keeps its database text.
- AC3: A quarantined path is not imported, written or binned, even across several scans.
- AC4: A journal from another folder is never applied; one from an earlier pick of the same folder is.

**Gherkin:** [`write-safety.feature`](../features/write-safety.feature) — `@WRS-02`

### WRS-03 Stale operations never reach a new folder
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultGenerationFenceTest`, `ProcessRemoteActionsVaultFenceTest`
Every queued write carries the folder and generation it was created for. A write whose folder changed is dropped.

**Acceptance criteria**
- AC1: A write captured at generation 1 is stale after the folder generation becomes 2.
- AC2: A stale fence aborts before either backend is written.
- AC3: A vault write without a fence is rejected.

**Gherkin:** [`write-safety.feature`](../features/write-safety.feature) — `@WRS-03`

### WRS-04 The user can clear stuck saves
**Status:** Implemented · **Scope:** Core · **Tests:** `SafVaultWriteStoreRecoveryTest.recoveryItemCount_and_clearAllRecoveryState`
Settings → **Clear unfinished saves** shows how many unfinished saves exist, asks for confirmation, discards them, and
leaves the result on the row.

**Acceptance criteria**
- AC1: The row shows the number of unfinished saves (staging entries and backups).
- AC2: After confirmation all of them are discarded and the count is zero.
- AC3: The result stays on the row afterwards.

**Screens:** DLG-17, SCR-09
**Gherkin:** [`write-safety.feature`](../features/write-safety.feature) — `@WRS-04`

### WRS-05 One bad file never stops sync
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteIdBackfillTest.unwritableFile_doesNotFailTheSync_orDropThePush`, `VaultFolderBlockerScenariosTest.blocker7_writeFailure_nextSyncStillWorks`
A failed write affects only that note. Other notes keep syncing, and the next sync retries it.

**Acceptance criteria**
- AC1: A write failure on one file leaves the sync result `Success`.
- AC2: The other notes' pushes still land.
- AC3: The next sync retries the failed file.

**Gherkin:** [`write-safety.feature`](../features/write-safety.feature) — `@WRS-05`
