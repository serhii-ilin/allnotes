# Folder history (HST)

Facts that note files can't express (who binned or restored a note, empty notebooks, unused tags) are recorded as
per-device event logs inside the notes folder, so every device and the user can see them. Release A records and shows
them. Release B uses them in sync. Use case: UC-16. Gherkin: [`history.feature`](../features/history.feature).

Design rule: **each device writes only its own files**, so the sync tool can never produce a conflict between devices.
Devices from before this feature are not supported. Later versions and other clients are expected and handled
gracefully.

### HST-01 Each device keeps its own log in the folder
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `FolderHistoryScenariosTest.aUserBin_isRecordedInTheDevicesOwnLogOnly`, `HistoryWriterTest.theReadme_isWrittenOnceWithIdenticalBytes`, `VaultHistoryStorageBackendTest`
The folder holds `.allnotes/devices/<deviceId>/events-YYYY-MM.jsonl` (one JSON event per line, append-only) and
`.allnotes/devices/<deviceId>/device.json` (manifest). A device writes only under its own `<deviceId>`, and never
rewrites, compacts or deletes another device's files. `.allnotes/README.md` explains the folder. It is written only if
missing, with the same bytes on every device.
**Acceptance criteria**
- AC1: After a user bin on device A, A's own monthly file has a new line, and no file outside `.allnotes/devices/<A>/`
  changed except the note's file.
- AC2: No code path writes a file under another device's folder.
- AC3: The README is created once and never rewritten when it already exists.
- AC4: The note scan never imports anything under `.allnotes/`.
**Gherkin:** [`history.feature`](../features/history.feature) — `@HST-01`

### HST-02 Device identity
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `DeviceIdentityTest`, `HistoryWriterTest.aClonedInstall_switchesToANewId`, `BackupRulesTest`
Each install has a random `deviceId` **per notes folder**, kept in app-private, backup-excluded storage; switching
back to a folder resumes its id and gap-free `seq`, and actions recorded against one folder never reach another
folder's log. It is shown as phone model plus the
first six characters of the id (`Pixel 9 · a1b2c3`). If a device finds lines under its own id that it never wrote (a
cloned install), it switches to a new id and logs `device.reid`.
**Acceptance criteria**
- AC1: Two installs never share an id unless one was cloned; a clone switches id on its next sync.
- AC2: The label is `<model> · <first 6 chars of id>`.
- AC3: The id survives app restarts and is not part of Android backup or device transfer.
- AC4: Two clones that write before seeing each other are detected from the conflicting copy of their log.
**Gherkin:** `@HST-02`

### HST-03 Events are identifiable, idempotent and causally ordered
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `HistoryReaderTest`, `MultiDeviceHistoryTest.threePhones_convergeWhateverTheDeliveryOrder`
Each event has `v`, `eid` (`<deviceId>:<seq>`), `dev`, `seq` (gap-free per device), `lc` (Lamport clock: one more than
the highest `lc` seen in any log), `t` (wall time, display only) and `op`, plus op fields (`id`, `path`, `to`, `name`).
Merged order is `(lc, dev, seq)`. Wall time never decides order.
**Acceptance criteria**
- AC1: Reading the same event twice (for example a Syncthing `.sync-conflict` copy of a log) yields it once.
- AC2: A device whose clock is hours off does not change the merged order.
- AC3: Every device that reads the same set of log files computes the same merged order.
**Gherkin:** `@HST-03`

### HST-04 Events recorded in v1
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `FolderHistoryScenariosTest`, `HistoryWriterTest.recordedEvents_carryOnlyTheDocumentedFields_noNoteText`
`note.bin` (user bins only), `note.restore`, `note.purge` (the user permanently deleting a user-binned note; the bin-retention
cleaner's deletions are this device's own setting and record nothing), `note.move` (a rename or move this device made),
`notebook.create|rename|delete`, `tag.create|rename|delete`, `device.reid`. Note events carry the note's
`allnotes-id` and paths, never note text. A note without a `allnotes-id` (not written to the folder yet) records
nothing.
**Acceptance criteria**
- AC1: Each listed user action produces exactly one event of that op, with the documented fields.
- AC2: No event contains note text.
- AC3: A bin caused by a missing file (not by the user) records nothing, and neither does its later purge.
- AC4: Nothing is recorded while no notes folder is configured.
**Gherkin:** `@HST-04`

### HST-05 Recording never blocks or loses a user action
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `FolderHistoryScenariosTest.binningWhileTheFolderIsUnavailable_recordsTheEventLater`, `HistoryWriterTest`, `BackupRulesTest`
Events go to an app-private outbox at the moment of the action and are flushed into the device's own log under the
vault lock, by sync and after each file write. The outbox is cleared only after the log write is verified. A failed
flush is retried on the next sync and never fails the user action or the sync.
**Acceptance criteria**
- AC1: Binning a note while the folder is unavailable succeeds; the event reaches the log once the folder is back.
- AC2: A flush that fails leaves the outbox intact and the sync result unchanged.
- AC3: The outbox is excluded from Android backup and device transfer.
- AC4: An action recorded while a flush is running is kept and written by the next flush.
- AC5: A log file that exists but momentarily fails to read is never overwritten.
**Gherkin:** `@HST-05`

### HST-06 Manifest and forward compatibility
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `HistoryReaderTest`, `HistoryWriterTest.theManifest_isRewrittenOnlyWhenChanged_andLastSeenDayAtMostDaily`, `VaultFolderHistoryTest.readingToleratesNewerVersionsAndDamage`
`device.json` holds `format`, `deviceId`, `name`, `appVersion`, `writes` (ops it records), `partialApplyOk`, and
`lastSeenDay`. The owner rewrites it only when a field changes, and `lastSeenDay` at most daily.
Reading rules:
- Unknown fields are ignored. A malformed line is skipped and counted.
- An unknown `op` is shown but never applied.
- Events with a newer `v` are applied only when that device's manifest sets `partialApplyOk`. Otherwise they are
  display-only.
- A device with a missing or unreadable manifest is display-only.
**Acceptance criteria**
- AC1: A log with an unknown field, an unknown op and a malformed line is read without error; counts are reported.
- AC2: A `v: 2` device with `partialApplyOk: false` affects nothing; with `true`, its known ops apply (Release B).
- AC3: A device folder without `device.json` is display-only.
**Gherkin:** `@HST-06`

### HST-07 Folder history screen
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `VaultFolderHistoryTest`, `FolderHistoryViewModelTest`, `FolderHistoryFormatterTest`, `MultiDeviceHistoryTest.aBinOnAPhone_appearsInTheOtherPhonesFolderHistory`
Settings → Notes folder → **Folder history** lists merged events, newest first: time, device label, action, and note
title (or path when the note is unknown here). Events from a newer version are marked as such. Skipped (malformed)
lines are counted. Release B adds the list of mismatches (HST-11).
**Acceptance criteria**
- AC1: The screen shows events from every device in merged order, newest first.
- AC2: An unknown op shows as "From a newer version".
- AC3: An empty or missing `.allnotes/` shows an empty state, not an error.
**Screens:** SCR-10
**Gherkin:** `@HST-07`

### HST-08 The logs are optional
**Status:** Implemented (Release A) · **Scope:** Core · **Tests:** `FolderHistoryScenariosTest.deletingTheHistoryFolder_changesNoNote`
Deleting `.allnotes/`, or one device's folder, never changes or loses a note. Sync then behaves as without the
feature, and each device starts a fresh log.
**Acceptance criteria**
- AC1: With `.allnotes/` deleted mid-use, a sync changes no note, notebook or tag.
- AC2: The next recorded action recreates the device's folder and manifest.
**Gherkin:** `@HST-08`

### HST-09 Bin, restore and purge travel through the logs
**Status:** Implemented (Release B) · **Scope:** Core
For each note id, the latest of `note.bin` / `note.restore` / `note.purge` from another device is applied here:
- **bin:** a live note is binned as a user bin, without touching files (the other device already moved the file to
  `.trash/`).
- **restore:** a binned note is restored. The file returns through the sync tool.
- **purge:** a binned note's database row and mapping are dropped. **Files are never deleted by applying an event.**

The event is skipped, and a mismatch recorded, when the local note has unsynced changes, when the note's own file is
still here (renamed or moved here meanwhile), or when the event contradicts the files. A bin waits silently while the
moved file has not arrived yet. A note binned on another device before it ever reached this one is imported straight
into this device's bin from its `.trash/` file. An empty scan counts as explained only if history accounts for every
mapped file, decided before anything is changed. Quarantined files count as present. This device's own pending
actions are written to its log before anything is applied, and a local bin whose file move has not completed yet is
never undone by an older event. Files that disappear with no event still follow the two-scan rule (DEL-03).
**Acceptance criteria**
- AC1: A bin on A bins the note on B at B's next sync, with no two-scan wait, and B's bin entry is a user bin.
- AC2: A purge on A removes B's bin entry without deleting any file.
- AC3: A bin on A while B has an unsynced edit of that note keeps the note live on B and records a mismatch.
- AC4: Applying the same events twice changes nothing the second time.
**Gherkin:** `@HST-09`

### HST-10 Empty notebooks and tags travel through the logs
**Status:** Implemented (Release B) · **Scope:** Core
Every device folds ALL notebook and tag events (its own included) in merged order into the same target set of names:
create → present, delete → absent, rename a → b → a absent and b present, unless a was already gone (a lost race),
in which case b is absent too. Each device then moves its rows toward that target by name: it creates missing names,
and deletes names that should be gone once nothing here uses them. Rows are never renamed in place, so a rename never
makes a device push its notes: their files bring the new notebook or tag name.
**Acceptance criteria**
- AC1: An empty notebook created on A appears on B.
- AC2: A rename on A renames on B; if B already has the new name, B's unused old one is folded into it, so both
  devices end with one.
- AC4: When the rename's log reaches B before the notes' files, B writes no note file and waits; after the files
  arrive both devices have only the new name and no conflict copy.
- AC3: A delete on A of a notebook B still uses leaves B's notebook in place until its last note leaves it.
- AC5: Concurrent renames of one name on two devices end with the same names on every device.
**Gherkin:** `@HST-10`

### HST-11 Mismatches are visible and the feature can be switched off
**Status:** Implemented (Release B) · **Scope:** Core
Every skipped application is kept on this device only and listed on the Folder history screen. A device-local setting
**Use folder history for sync** (on by default) turns Release B's behavior off. Recording (Release A) continues either
way.
**Acceptance criteria**
- AC1: Each skipped event appears once in the mismatch list with its reason.
- AC2: With the setting off, sync decisions are identical to Release A.
**Screens:** SCR-10
**Gherkin:** `@HST-11`

### HST-12 Devices converge
**Status:** Implemented (Release B) · **Scope:** Core
Any number of devices that receive the same files, in any order and with duplicates, end with the same notes,
notebooks and tags. No note text is lost, and no file is deleted by applying events.
**Acceptance criteria**
- AC1: Three simulated devices over 20 random delivery orders end identical.
- AC2: The same holds with a clock-skewed device, a conflict-copied log, a newer-format device and a cloned device.
**Gherkin:** `@HST-12`

### HST-13 Old history is removed after a set time
**Status:** Implemented · **Scope:** Core · **Tests:** `EachPhoneRemovesItsOwnOldHistoryTest`, `HistoryPrunerClockSkewTest`, `HistoryPruneReferenceTimeTest`, `HistoryPruneOncePerDayTest`
Each device deletes its OWN month files (`events-YYYY-MM.jsonl`, and Syncthing conflict copies of them) once the whole
month ended longer ago than the device's setting **Keep folder history**: 1 month, 3 months (default), 6 months,
1 year, or Forever. It never deletes another device's files; the sync tool carries each device's deletions to the
others. The setting is device-local, like every app setting. Pruning runs after this device's own log is flushed, at most once a
UTC day, and never fails a sync. It does not trust this device's clock: the period is measured from the newest event
another device wrote (this device's own only when it is alone), plus a month of slack, and the month holding this
device's newest own event is never deleted. Known limit: a device alone in the folder has no other clock to compare
with, so if its clock jumps years ahead it may delete its own older months early (history only, never notes; HST-08).
A shortened period takes effect at the next day's prune. A device that was offline longer than the kept period simply misses those events and relies on the
file-based rules (HST-08).
**Acceptance criteria**
- AC1: With 3 months kept, on 2026-11-11 this device's July file is deleted and its August file is kept.
- AC2: Another device's old files are never deleted by this device.
- AC3: Forever keeps every file.
- AC4: A failed delete is retried on a later sync and never fails the sync.
- AC5: After pruning, the Folder history screen shows only what remains, and sync results on all devices still converge.
**Gherkin:** [`history.feature`](../features/history.feature) — `@HST-13`
