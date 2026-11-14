# Requirements

One file per feature area. Each requirement has a stable ID, a status, its acceptance criteria, its Gherkin pointer,
its tests and, when one applies, the open finding that affects it. The rules for keeping this current are in
[docs/README.md](../README.md).

| Area | File | Prefix |
|---|---|---|
| Notes folder: choosing, confirming, switching | [notes-folder.md](notes-folder.md) | FLD |
| Note file format, notebooks, attachments | [file-format.md](file-format.md) | FMT |
| Note identity, renames and moves | [identity.md](identity.md) | IDN |
| Scanning, change detection, push and pull | [sync.md](sync.md) | SYN |
| Bin, deletion and missing files | [deletion-and-bin.md](deletion-and-bin.md) | DEL |
| Conflicts | [conflicts.md](conflicts.md) | CON |
| Reminders | [reminders.md](reminders.md) | REM |
| Write safety and recovery | [write-safety.md](write-safety.md) | WRS |
| Backup and restore | [backup.md](backup.md) | BAK |
| Drawer and Settings UI | [ui.md](ui.md) | UI |
| Note editor | [editor.md](editor.md) | EDT |
| Notes lists, sorting, selection, search | [notes-list.md](notes-list.md) | NTL |
| Notebooks and tags management | [organization.md](organization.md) | ORG |
| Attachments, recording, media viewer | [media.md](media.md) | MED |
| Settings screen | [settings.md](settings.md) | SET |
| Background work: schedule and triggers | [background.md](background.md) | BGD |
| Non-functional: platform, privacy, accessibility, localization, performance | [quality.md](quality.md) | NFR |
| Android integration: share-in, shortcuts, widget, network | [integration.md](integration.md) | INT |
| Folder history (per-device event logs in `.allnotes/`) | [history.md](history.md) | HST |

## Conventions

- **IDs** are `<PREFIX>-<NN>` and are never reused. A requirement that is dropped stays in its file with status
  `Retired` and a reason.
- **Status** is one of:
  - `Proposed`: agreed, not built.
  - `Implemented`: built, tested and committed.
  - `Uncommitted`: built and tested in the working tree, not committed yet.
  - `Partial`: built, but a known finding breaks part of it.
  - `Defect`: built, and the main behavior does not hold.
  - `Retired`: dropped (with the reason).
- **Tests** name the test class, and the test method when one test proves it. Test classes live under `app/src/test`.
- **Scope** is `Core` (must behave identically on every implementation that opens the folder) or `Platform` (same
  intent, platform mechanism); see [platform/android.md](../platform/android.md). It sits on the status line.
- **Screens** (`SCR-xx`, `DLG-xx`) point at the wireframes in [docs/ui/](../ui/README.md) for requirements that own a
  screen or dialog.
- **Acceptance criteria** (`AC1`, `AC2`, …) sit right after a requirement's prose: 2–5 observable, testable
  statements derived from the prose and from what its tests assert. Pointer requirements ("See SYN-08") have none.
- **Gherkin** for every feature area lives in [docs/features/](../features/). Every non-pointer requirement is covered
  by at least one Scenario tagged with its ID; every Scenario names each proving test with
  `@test:<Class>.<method>` or is marked `@gap`.
- **Findings** link to an audit report entry, for example [AUD-3](../audits/2026-11-10-note-identity-changes.md#aud-3).
- Requirements say *what* must hold, not how it is coded. Implementation notes go in
  [folder-vault-sync.md](../folder-vault-sync.md).

## Changelog

| Date | IDs | Change |
|---|---|---|
| 2026-11-10 | all | Initial capture of the folder-vault requirements from the committed migration (`cb10b9b`) and the uncommitted note-identity work. Status set from the [2026-11-10 audit](../audits/2026-11-10-note-identity-changes.md). |
| 2026-11-10 | IDN-01…06, REM-03, REM-04, SYN-08, FLD-08, DEL-06, UI-01…03 | New requirements raised in the Cursor session: stable note id in front matter, empty reminder list syncs, alarms after observer sync, folder move removes old files, simpler drawer and Settings, Android `.trashed-` files. |
| 2026-11-10 | FMT-09 | New (owner): Markdown files created by external editors, with no front matter or no id, get the app's required attributes on first sync. Status `Partial`: only `id` is written today. Required set pending confirmation. |
| 2026-11-10 | all | New rule (owner): every requirement has acceptance criteria, every feature area has a Gherkin file in [`docs/features/`](../features/), every Scenario names its proving tests or `@gap`. `scenarios.md` is now an index; the Gherkin is the source. |
| 2026-11-10 | FLD-08 | Redefined by owner decision: "Move my notes here" copies the notes into the new folder and never deletes anything in the old folder; the user is told the old files remain. Status `Implemented`. |
| 2026-11-10 | FMT-02, FMT-03, IDN-01, IDN-05, CON-02 | Owner decision: the identity key is `allnotes-id`; a foreign `id:` is preserved verbatim. A file whose id another note owns is imported as its own note and re-identified, so a shared id never hides a file; conflict copies get their own id and are listed in Settings. Status `Implemented`. |
| 2026-11-10 | FMT-07, IDN-02, SYN-03, SYN-08, WRS-04, REM-03, REM-04, UI-01, FLD-08 | Status updates after the AUD-1…AUD-8 fixes were committed. SYN-04 stays `Partial` (`refreshVaultAttachmentIndex` read), DEL-06 for AUD-9, UI-02 for AUD-10. |
| 2026-11-10 | CON-03, SYN-01 | [AUD-11](../audits/2026-11-10-note-identity-changes.md#aud-11) recorded: a just-exported note looks locally edited until the creating device syncs, so an edit or rename elsewhere can produce a conflict copy. CON-03 and SYN-01 are `Partial`. |
| 2026-11-10 | IDN-02, IDN-05, FMT-09 | Review round: backfilled ids depend only on the file (`created` or path); non-UTF-8 and BOM files are never rewritten; [AUD-12](../audits/2026-11-10-note-identity-changes.md#aud-12) and [AUD-13](../audits/2026-11-10-note-identity-changes.md#aud-13) recorded (Low). |
| 2026-11-10 | HST-01…HST-13 | New area (owner): folder history as per-device, append-only event logs under `.allnotes/`, written only by their own device and forward compatible with later versions. Release A records and shows; Release B applies bins, restores, purges, notebooks and tags. Settings do not travel. All `Proposed`. |
| 2026-11-10 | HST-01…HST-08 | Release A implemented: each device records user bins, restores, purges, moves and notebook/tag changes to its own log under `.allnotes/`; Settings → Folder history shows the merged logs. Sync decisions are unchanged. |
| 2026-11-10 | HST-02, HST-04, HST-05 | Release A hardened after review: per-folder device identity and outbox, purge recorded only for user bins, no lost or truncating flushes, simultaneous clones detected. |
| 2026-11-10 | HST-09, HST-10 | Release B review: a recorded bin never bins a note whose own file is still here (renamed meanwhile); an empty scan counts as explained only if history accounts for every file; a used notebook or tag is renamed only after its notes' files bring the new name, and an unused old name folds into an existing new one (AC2 changed from "mismatch", for convergence). |
| 2026-11-10 | HST-09…HST-12 | Release B implemented: other devices' bins, restores and purges apply here; notebooks and tags converge through a deterministic fold of all events (rows never renamed in place); a note binned before it reached a device is imported into its bin; three simulated phones converge over 20 random delivery orders. |
| 2026-11-10 | HST-04, HST-09, HST-12 | Release B review: retention purges stay on the device; an empty scan is judged before anything changes; quarantined files count as present; own pending actions reach the log before applying; a not-yet-moved file delays bin imports. |
| 2026-11-11 | FMT-09 | Owner decision: files from other tools also get `created` and `modified`, filled from the file's modification time when missing, in the same single write as `allnotes-id`. Status `Proposed`. |
| 2026-11-11 | HST-13 | Owner decision: each device removes its own history month files after a device-local period ("Keep folder history", default 3 months); replaces the deferred compaction idea. `Proposed`. |
| 2026-11-11 | FMT-09 | Implemented: `allnotes-id`, `created` and `modified` (file modification time) added in one write to files from other tools. |
| 2026-11-11 | HST-13, CON-03, SYN-01, DEL-06, IDN-05, UI-02 | HST-13 implemented; AUD-9, AUD-10, AUD-11, AUD-12 fixed (Android-trash bin cleanup, single folder message, hash-only clean sync without the export stamp, device-independent shared-id owner). |
| 2026-11-11 | FMT-09, IDN-05, HST-13 | Review round 4: dates are completed from the note's own dates; the shared-id owner is chosen from file facts only; retention does not trust this device's clock, keeps the newest own month and runs once a day. |
| 2026-11-11 | HST-13 | Known limit recorded (a lone device with a wrong clock); AUD-14 (Low) backlog recorded. |
| 2026-11-11 | UI-05 | Drawer header layout: opaque vault band, separated rows, edge-to-edge status bar inset (fixes overlap with main toolbar). |
| 2026-11-11 | FMT-10 | Attachment vault basenames get MIME-appropriate extensions; non-image links in markdown; forward-only (no rename of legacy files). |
| 2026-11-11 | FMT-10 | Low-hanging fixes: GENERIC staging `.bin`, PDF/text in attach picker, generic attachments open externally when internal viewer cannot handle them. |
| 2026-11-11 | EDT-01 | Editor preview reflects typed title and body before Room debounce; title uses same save pipeline as content. |
| 2026-11-11 | EDT-01 | Fix first-load race: do not overwrite typed title/body when Room first emits; `Partial` until UI verified on device. |
| 2026-11-12 | FMT-11, FMT-04 | New (owner): names that become note files or notebook folders must be valid on every file system (no illegal characters, leading dot, trailing dot/space or Windows reserved name; at most 200 UTF-8 bytes; case- and Unicode-insensitive folder matching), while note titles stay free-form. Validator and hardened file-name sanitizer are wave 1 (`Partial`, AC1–AC6); notebook input rejection is wave 2 (AC7). FMT-04 gains AC5. |
| 2026-11-12 | FMT-11, FMT-04 | Wave 2 implemented: the notebook dialog rejects an invalid or duplicate name inline and never trims or renames it; `NotebookRepository` refuses invalid names; a folder, front-matter or history name that is not valid creates no notebook; backup restore sanitizes legacy names with a collision suffix; a one-time startup migration renames existing invalid notebooks. FMT-11 is `Implemented`. |
| 2026-11-12 | NTL-01…14, EDT-02…14, ORG-01…13, MED-01…12, SET-01…14, INT-01…14 | Documentation review: behavior that already existed but was unspecified is now recorded from the code, status `Implemented`. Scenarios without an automated test are tagged `@gap` (155 of them). Suspicious behavior found along the way is listed in [the review](../audits/2026-11-12-undocumented-features-review.md); statuses are not changed until the owner triages it. |
| 2026-11-12 | REM-06…14, DEL-07…11, BGD-01…10, NFR-01…17, SET-14, FMT-10 | Second documentation review: reminders as a user feature, the bin auto-purge, the background schedule and non-functional facts recorded from the code (`Implemented`; 57 scenarios `@gap`). Four `Proposed` placeholders (NFR-06, NFR-08, NFR-10, NFR-14) wait for the owner to set targets. Hygiene: the FMT-10 image scenario is tagged, SET-14 is a pointer. New findings are in [the review](../audits/2026-11-12-undocumented-features-review.md#second-review). |
| 2026-11-12 | UC-03…UC-06, UC-17…UC-26 | Use cases added for the areas documented in the two reviews (search and sort, selection, notebooks and tags, editor, media, share-in and widget, Settings, bin purge, background work, platform); each links its requirement IDs. |
| 2026-11-13 | BAK-03…BAK-05, NTL-10, NTL-15, BGD-09, BGD-10, all | Full coverage review ([audit](../audits/2026-11-13-full-coverage-review.md)): folder contract written byte for byte in [spec/](../spec/) with conformance fixtures (`VaultContractFixturesTest`); screen catalogue and wireframes in [ui/](../ui/README.md); every requirement gains `Scope` and, where it owns one, `Screens`; generated [traceability.md](../traceability.md) and `scripts/check-docs.py`. New: BAK-03 (`Partial`, FC-03), BAK-04, BAK-05 and NTL-15 (the long-press action sheet). NTL-10 corrected to the code: a long press opens the sheet, *Select more…* starts selection. BGD-09/BGD-10 made explicit pointers. Findings FC-01…FC-13 await triage. |
