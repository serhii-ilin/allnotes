# Full coverage review (2026-11-13)

A third pass over the code, aimed at a specification complete enough to re-implement the app on another platform:
the folder contract was written down byte by byte ([docs/spec/](../spec/)), proven with conformance fixtures
(`VaultContractFixturesTest`), every screen was drawn ([docs/ui/](../ui/README.md)), and every menu item, dialog,
preference, history op and front-matter key was traced to a requirement ([traceability.md](../traceability.md)).

Statuses are **not** changed by this review; the owner triages each finding (rule 3 in [docs/README.md](../README.md)),
as with the previous reviews. Each finding says how it was established:
- *confirmed by probe*: reproduced with a throwaway JVM test against the real classes;
- *confirmed in code*: the code path was read end to end;
- *plausible*: the code allows it, not reproduced.

## New findings

### FC-01
**Relative links to other notes and scheme-less web links are imported as attachments** (Medium, *confirmed by
probe*). `VaultAttachments.parseFromBody` treats every `[text](target)` whose target has no `://` as a vault file:
`[Plan](Plan.md)` in `Work/Note.md` becomes a GENERIC attachment `Work/Plan.md`, and `[site](example.com)` becomes
`Work/example.com`. The note shows attachment tiles for them and their paths enter the mapping's attachment set.
Affects FMT-05, MED-04. Owner decision: limit attachments to image links plus links into the note's own asset folder
(and other known media extensions), or keep and document.

### FC-02
**A note's asset folder can be a notebook folder** (Medium, *confirmed in code*). The asset folder of `Work.md` is
`Work/`, which is also the folder of notebook "Work"; likewise `Work/Plan.md` and a nested folder `Work/Plan/`.
Attachments of that note land among the notebook's notes, and a case-only title rename of the note
(`Work` → `work`) takes the branch in `VaultNoteRelocation.relocateOwnedAssets` that renames the **whole folder**,
moving every note of the notebook into a folder named `work`. Affects FMT-04, FMT-05, Article 2. Permanent delete is
safe (it deletes only linked files inside the folder and the folder only when empty).

### FC-03
**The default backup ("Include files") leaves out attachments stored in the notes folder** (High, *confirmed in
code*). `AttachmentHandler.IncludeFiles.handle` resolves the attachment path through the content resolver; a
`vaultrel:` path cannot be resolved, so the handler returns null and the attachment is dropped from the backup's note
and its bytes are not zipped. Since the folder migration nearly every attachment has a `vaultrel:` path. The note
text keeps the link, so a restore onto the same folder still finds the file, but a restore onto a new device or an
empty folder loses it. Affects BAK-01 (AC1 "without loss"), SET-13.

### FC-04
**A backup from a newer app version cannot be restored** (Low, *confirmed in code*). `Backup.fromString` uses the
default `Json` (unknown keys are an error); the `version` field is never checked and the migration handler is a no-op.
Adding any field to a serialized class makes older apps fail the restore with a generic error. Affects BAK-01.

### FC-05
**Two checklist parsers disagree** (Low, *confirmed in code*). Import (`VaultNoteDocument` private `mdToTaskList`)
appends a non-task line to the previous task with no separator and keeps image/link lines; the editor's conversion
(`Note.mdToTaskList`) joins with a space and drops link-only lines. A wrapped task written by another editor
(`- [ ] one` / `  continued`) is imported as `onecontinued`. Pinned in `parse.json` (`checklists`). Affects FMT-06,
EDT-07.

### FC-06
**The theme cache stores raw style resource ids** (Medium, *plausible*). `ThemeCache` keeps `R.style` integers in a
shared-preferences file that Auto Backup includes. Resource ids are assigned per build, so after an update (or a
restore from another version or onto an older Android without `values-v31`) the first launch can apply an unrelated
style or fail to find one before the cache is rewritten. Extends the unverified "startup crash after an Auto Backup
restore" item of the previous review. Affects SET-02, SET-03, NFR-01. Store enum names instead of resource ids.

### FC-07
**`allnotes-id` is not the first front-matter line when verbatim keys are kept** (Low, *confirmed by fixture*). When a
file wrote `tags` as a scalar/block or a date in ISO form, those lines are rendered ahead of `allnotes-id`
(`render.json`, third case). FMT-02 AC2 says the id is "rendered first in the block". Fix the AC or the renderer.

### FC-08
**A title containing a line break corrupts the front matter** (High, *confirmed by probe*). The writer escapes only
`\` and `"`. A title read from `title: "a\nb"` (another tool), or set from a shared `EXTRA_TITLE` with a newline, is
written with a raw newline inside the quotes; the line-based reader then reads the title as `"a` and keeps `b"` as a
foreign key, and each later save carries the damage. Affects FMT-02, FMT-03, INT-01. Escape `\n`, `\r`, `\t` (the
reader already unescapes them) and strip line breaks from titles.

### FC-09
**Front-matter booleans are case-sensitive** (Low, *confirmed in code*). Only `true`/`false` are read; `True`, `yes`
or `on` written by another tool count as absent, so the app keeps its previous value and overwrites the file's value
on the next push. Documented in [vault-format §4.2](../spec/vault-format.md#42-keys-the-app-owns-fmt-02). Owner
decision: accept YAML 1.1 forms or keep strict.

### FC-10
**The built-in image/video path does not cover all types the reader recognises** (Low, *confirmed in code*).
`AttachmentExtensions` keeps `bmp` and `svg` as image extensions, but `VaultAttachments.guessType` reads them back as
GENERIC, so an attached SVG or BMP turns into a generic file after the next import. Affects FMT-10, MED-05.

### FC-11
**Gherkin and requirement hygiene found by the new checker** (Low, *confirmed by `scripts/check-docs.py`*). BGD-09 and
BGD-10 had no status, no acceptance criteria and no scenario while not phrased as pointers; fixed in this change by
making them explicit pointers. The checker now runs in seconds and should be part of every documentation change.

### FC-12
**"Use folder history for sync" has no control** (Medium, *confirmed in code*). HST-11 defines a device-local switch
that turns history application off; `HistoryApplySettings.setEnabled` exists but nothing calls it, and neither Settings
nor the Folder history screen shows it. A user cannot fall back to Release A behavior. Affects HST-11 (AC2).

### FC-13
**NTL-10 described long press wrongly; the action sheet was undocumented** (Low, *confirmed in code*). On every note
list a long press opens a single-note action sheet (`AbstractNotesFragment.showMenuForNote`) and selection starts from
its *Select more…* item; NTL-10 said a long press enters selection mode. NTL-10 was corrected in this change and the
sheet recorded as NTL-15, since both describe the existing code.

## Re-check of the 2026-11-12 *unverified* items
| Item | Result |
|---|---|
| External media with no handler crashes on tap (SET, EDT) | **Confirmed in code**: `EditorFragment` opens `ACTION_VIEW` without an `ActivityNotFoundException` guard; `AboutFragment` likewise for links. |
| Settings picker maps the tapped index into the unfiltered enum list (SET) | **Confirmed in code** (`showPreferenceDialog`: items are filtered, `enumValues[which]` and `selectedIndex` are not). Harmless today because the only unsupported value (`ColorScheme.SYSTEM`) is last. |
| Possible startup crash after an Auto Backup restore onto API < 31 (SET) | **Plausible**, widened to FC-06. |
| `BackupService` never calls `startForeground` (BGD-09) | **Not reproduced**: `BackupService` calls `startForeground` for backup and restore. The 2026-11-12 entry should be closed. |
| `RecorderService` never calls `startForeground` (MED) | **Confirmed**, but it is started with `startService` + bind (not `startForegroundService`), so it does not crash; the system may stop a recording once the app leaves the foreground. |

## Coverage
- Requirements: 199 before this review; new requirements added by it are listed in the
  [changelog](../requirements/README.md#changelog).
- Gherkin: `@gap` counts per area are printed by `python3 scripts/check-docs.py`. Highest-risk untested scenarios, by
  data-loss potential: the bin auto-purge (DEL-07…DEL-10), reminders across devices and reboots (REM-09…REM-13), share-in
  of media (INT-02), attachment removal (MED-10), checklist conversion (EDT-07).
- The folder contract now has conformance fixtures: ids, names, rendering, single-line edits, parsing, canonical hashes
  and history encoding/reading (`VaultContractFixturesTest`, 7 tests).
