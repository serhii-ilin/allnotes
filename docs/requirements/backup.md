# Backup and restore (BAK)

Use case: UC-15. Gherkin: [backup.feature](../features/backup.feature).

### BAK-01 Zip backup and restore
**Status:** Implemented · **Scope:** Core · **Tests:** `BackupTest`, `BackupRestoreVaultTest`
Notes, notebooks, tags, reminders and attachments can be exported to a zip file and restored later.

**Acceptance criteria**
- AC1: A backup serializes notes, notebooks, tags, reminders and attachments without loss.
- AC2: Restoring the backup brings those notes and their metadata back.

**Gherkin:** [`backup.feature`](../features/backup.feature) — `@BAK-01`

### BAK-02 A restore does not fight the folder
**Status:** Implemented · **Scope:** Core · **Tests:** `BackupRestoreVaultTest.restoreWithOldVaultMappings_syncTwice_noDuplicatesNoBinned`
Restoring a backup drops the vault mappings stored in it, which point at an old folder state, and then syncs once. The
result has no duplicate notes or files and nothing binned.

**Acceptance criteria**
- AC1: A restore drops the vault mappings stored in the backup.
- AC2: One sync writes one file per note into the current folder.
- AC3: There are no duplicate notes, duplicate suffix files or binned notes, and no legacy-provider mappings.

**Gherkin:** [`backup.feature`](../features/backup.feature) — `@BAK-02`

### BAK-03 What a backup contains
**Status:** Partial ([FC-03](../audits/2026-11-13-full-coverage-review.md#fc-03)) · **Scope:** Core · **Tests:** none yet (@gap)
Settings → Backup notes writes every note; Export in the editor (EDT-11) or on a selection (NTL-11) writes only those
notes. The user picks the destination in the system file picker; the suggested name is `allnotes_backup_<epoch>.zip`.
The zip holds `backup.json` — a `version` number and the notes (with tasks, flags, colour, dates and attachments),
their notebooks, tags, note–tag links, reminders and folder mappings — and, for the "Include files" strategy, a
`media/` folder. The backup strategy (SET-13) decides the attachments: *Include files* copies each attachment file into
`media/` (a repeated name gets a `<n>_` prefix) and records the file name; *Only backup description and local path*
keeps the attachment entries as they are; *Don't backup attachments* drops them.

**Acceptance criteria**
- AC1: A full backup contains `backup.json` with every note, notebook, tag, note–tag link and reminder.
- AC2: With *Include files*, every attachment's bytes are in `media/` and its entry names that file.
- AC3: With *Only backup description and local path*, attachment entries keep their path and description and no
  `media/` folder is written.
- AC4: With *Don't backup attachments*, notes are written without attachments.
- AC5: A selection or single-note export contains only those notes and the notebooks, tags and reminders they use.

**Screens:** SCR-09
**Gherkin:** [`backup.feature`](../features/backup.feature) — `@BAK-03`

### BAK-04 How a restore merges
**Status:** Implemented · **Scope:** Core · **Tests:** `BackupRestoreVaultTest` (AC2 invalid names, AC5 one sync); rest @gap
Settings → Restore notes opens a zip. Every note in it is added as a new note; nothing already in the app is replaced.
A notebook joins an existing notebook with the same folder name (compared without case and Unicode differences),
otherwise it is created; an old name that is not a valid folder name is made valid and suffixed on a collision
(FMT-11). A tag joins an existing tag with exactly the same name, otherwise it is created. Note–tag links and
reminders follow their notes; expired reminders are skipped and future ones are scheduled. Files from `media/` are
copied into private storage under a unique name and attached. The folder mappings in the backup are ignored and one
sync runs afterwards (BAK-02).

**Acceptance criteria**
- AC1: Restoring adds every note of the backup as a new note and changes no existing note.
- AC2: A notebook whose name matches an existing one (case and Unicode-insensitive) is reused; an invalid name is
  sanitized with a collision suffix.
- AC3: A tag with exactly the same name is reused; otherwise a tag is created.
- AC4: Future reminders are restored and scheduled; expired ones are not restored.
- AC5: One sync runs after the restore.

**Screens:** SCR-09
**Gherkin:** [`backup.feature`](../features/backup.feature) — `@BAK-04`

### BAK-05 Backup and restore show progress and a result
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Backup and restore run in a foreground service with a notification on the Backups channel: "Backing up your notes…"
with a progress bar, then "Backup complete!" or "Backup failed"; "Restoring your notes…", then "Restore completed" or
"Restore failed". The result notification can be dismissed.

**Acceptance criteria**
- AC1: While a backup runs, a notification shows "Backing up your notes…" and its progress.
- AC2: A finished backup shows "Backup complete!"; a failed one "Backup failed".
- AC3: A restore shows "Restoring your notes…" and ends with "Restore completed" or "Restore failed".

**Screens:** SCR-15
**Gherkin:** [`backup.feature`](../features/backup.feature) — `@BAK-05`
