# Undocumented features review (2026-11-12)

The code was read area by area and the behavior it has today was written into the spec: `NTL`, `EDT-02…14`, `ORG`,
`MED`, `SET`, `INT`. The documentation records what the app does, not what it should do. Anything below that looks
unintended was documented as it is and is listed here for the owner to triage. No requirement status was changed to
`Partial` or `Defect` yet; do that for each finding the owner accepts as a defect, and link it here.

Found by reading the code, not by running the app. Items marked *unverified* were reported by the reviewing agent and
not re-checked by the orchestrator. Findings the orchestrator confirmed in the code are marked *confirmed*.

## Needs an owner decision
- **Offline claim vs network use (INT-14).** `INTERNET` is declared and a note containing `![x](https://…)` is loaded
  through Coil, which fetches it when the note is previewed. [CONSTITUTION](../CONSTITUTION.md) Article 5.1 says the app
  works fully offline.
- **Highlight is `==text==` (EDT).** Rendered by the app, not by most other Markdown tools (Article 3, interoperability).
- **Hidden pinned notes appear on the home-screen widget (INT)** — *confirmed*: `NotesWidgetViewsFactory` filters by
  `isPinned` only, while the lists also filter `isHidden`.
- **Auto Backup scope (INT).** The Room database and private media copies remain eligible for Google backup.

## Likely defects
- **Checklist → text → checklist loses check marks (EDT)** — *confirmed*: `Note.stringToTaskList()` makes every line
  unchecked and the default `taskListToString()` writes no marker.
- **Removing an audio, video or generic attachment keeps its link in the body (MED-10)**; the attachment can return on
  the next import (`VaultAttachmentMembershipTest.export_doesNotStripNonImageFileLinks`).
- **Edited attachment descriptions can be lost after a sync (MED).**
- **Tag rename does not update note front matter until each note is next pushed (ORG)**; delete does queue the notes.
- **Table insert is off by one (EDT):** "2 rows" yields a header row, a separator and one row; "0 rows" no separator.
- **Image insert writes the literal alt text `alt text` (EDT)** and puts the description in the title.
- **Unarchive from the editor shows the "Archived note" message (EDT).**
- **Sharing a note whose attachment file is missing can put null in the stream list (EDT).**
- **Media service problems (MED, INT):** `RecorderService` never calls `startForeground`; `POST_NOTIFICATIONS` is only
  requested for reminders, so the playback notification can be invisible on Android 13+; `startForegroundService` is
  called before the URI resolves; a failed share copy is dropped silently.
- **Photo capture leaves a temp file and a duplicate; a cancelled recording leaves a partial `.m4a`** until the cleanup runs.
- **External media with no handler crashes on tap (SET, EDT):** `startActivity(ACTION_VIEW)` has no
  `ActivityNotFoundException` guard. *unverified*
- **Possible startup crash after an Auto Backup restore onto API < 31 (SET):** a stored `SYSTEM` colour scheme whose
  style exists only in `values-v31` is applied without an `isSupported()` check. *unverified*
- **Settings picker maps the tapped index into the unfiltered enum list (SET)**, correct only while unsupported options
  are last. *unverified*
- **Shortcuts (INT):** no pre-API-25 variant while `minSdk` is 24; `targetPackage` is hard-coded to `io.github.allnotes`.
- **Locales (INT):** `ar`, `fa` and `ro` translations are missing from `locales_config.xml`.

## Inconsistent or surprising behavior
- Tags compare names exactly, so `Work` and `work` are two tags, while notebooks treat them as duplicates; tag names are
  not trimmed or validated (ORG).
- Tapping a tag fills the search box, so it matches substrings in titles, bodies and notebook names, not just that tag
  (ORG).
- Deleting notebooks or tags has no confirmation and no undo (ORG).
- The "no notebook" drawer entry is hidden by default (ORG).
- "Show date created/modified" only affects the open note's date line; the note cards show no date (NTL, SET).
- Multi-select Pin/Unpin toggles each note, so a mixed selection is inverted note by note (NTL).
- Compact/full preview changes do not update the modified date (NTL).
- "Show hidden notes" is not persisted and cannot be toggled from Search (NTL).
- Archive and Bin inherit the sort and layout but offer no control to change them (NTL).
- Saved title and body are trimmed on every save, dropping Markdown's trailing-double-space hard break (EDT).
- Default editor mode and font size apply only when the editor first loads (SET).
- Widget refreshes only every 30 minutes, on app start and on pin/unpin (INT).
- "Only backup description and local path" keeps attachment paths that point into app-private storage not included in the
  zip (SET).
- Dead code: `org.qosp.notes.NEW_NOTE` intent handling, `MediaStorageManager.deleteAllMedia()`, the widget's unreachable
  "Untitled" fallback.

## Coverage
155 Gherkin scenarios in the six new feature files are tagged `@gap` (no automated test): NTL 32, EDT 41, ORG 14,
MED 16, SET 25, INT 27. Per the repo rules a `@gap` scenario is a tracked defect in coverage.

## Second review
A consistency check of the spec (every requirement has a scenario, every tag names a real test) and a code-versus-spec
comparison added REM-06…14, DEL-07…11, BGD-01…10 and NFR-01…17. New findings, by reading the code only:

### Needs an owner decision
- **Targets that do not exist yet** (`Proposed`, each with one AC "The owner sets a measurable target"): note
  encryption (NFR-06), accessibility (NFR-08), localization (NFR-10), performance (NFR-14).
- **Archiving does not cancel alarms (REM-13):** an archived note still notifies.
- **Missed reminders are dropped silently (REM-12):** a reminder that came due while the device was off is deleted at
  boot with no notification.
- **Automatic purge removes the shared `.trash` file (DEL-09):** another device keeps a bin entry whose file is gone, and
  restoring it there recreates the note.

### Likely defects
- **No `contentDescription` anywhere (NFR-07):** zero occurrences in layouts and code, so every icon-only control is
  unlabelled for TalkBack.
- **Stale default time for a new reminder (REM-07):** the view model is activity-scoped and the date is set once, so a
  dialog opened long after start can show a past time.
- **Denying the notification permission dismisses the whole reminder dialog (REM-14)**, even to edit or delete.
- **Restore does not re-arm alarms (REM-13):** only the next sync does.
- **Disabling the Reminders channel makes the reminder fire silently and be deleted** (the check is app-level only).
- **Retention also purges missing-file and Android-trash bins (DEL-03, DEL-07)**, so "comes back if the file returns"
  holds only until the retention expires; switching to *Instantly* leaves older entries unpurged; with *Never* or
  *Instantly* the automatic clean skips media cleanup.
- **`BackupService` is started with `startForegroundService` but never calls `startForeground` (BGD-09)**, as the recorder. *Not reproduced on 2026-11-13: `BackupService` does call `startForeground`; see the [full coverage review](2026-11-13-full-coverage-review.md#re-check-of-the-2026-11-12-unverified-items).*
- **Settings in an unencrypted DataStore that Auto Backup may upload (NFR-05);** if the encrypted prefs file cannot be
  opened it is recreated, silently resetting its values.
- **Localization (NFR-09):** 21 `values-*` folders, 19 in `localeFilters`, 18 in `locales_config.xml`; `ar`, `fa`, `ro` and
  `sv` fall through.
- `VaultForegroundRescan.resetThrottle()` has no caller, so the 2-minute throttle survives a folder switch (BGD-05); a
  failed `registerObserver` is not retried until the folder changes (BGD-04).

### Spec/code mismatch
- **SYN-02 and `folder-vault-sync.md` say a rescan runs "while a note list is visible".** The code only syncs on
  `MainActivity.onResume`, on pull-to-refresh and from the periodic job; there is no polling timer. BGD-05 documents the
  real triggers; SYN-02 still needs the owner's call (fix the text or add the timer).

### Checked and not a defect
- The reviewing agent suspected a startup crash because the manifest removes `WorkManagerInitializer` while `App` is not
  a `Configuration.Provider`. `workManagerFactory()` in `App.onCreate` is Koin's own WorkManager setup and the app has
  shipped this way, so no requirement records it as a defect. *Not verified on a device.*
- `NFR-17` and the `SC-*` and "See X" pointers carry no scenario by design.
