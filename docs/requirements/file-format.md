# Note file format (FMT)

What a note looks like on disk, so other tools can read and write it. Use cases: UC-03, UC-04, UC-05, UC-08.
Gherkin: [file-format.feature](../features/file-format.feature).

### FMT-01 One note, one file
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultPathsTest`, `VaultFolderBlockerScenariosTest.blocker7_cjkLongTitle_savesAndSyncs`
A note is `<title>.md`, or `<title>.txt` when Markdown is off. The name is the sanitized title (illegal characters
replaced, at most 200 UTF-8 bytes) or `Untitled`. A taken name gets a `-2`, `-3`, … suffix. Paths of other notes count
as taken even if their file is momentarily missing. On case-insensitive volumes names are compared without case.

**Acceptance criteria**
- AC1: A note is written as one `<title>.md` (or `.txt` when Markdown is off).
- AC2: The name has no illegal characters and is at most 200 UTF-8 bytes; a CJK title saves and syncs.
- AC3: A taken name gets the next free `-2`, `-3`, … suffix.
- AC4: Paths of other notes count as taken even when their file is momentarily missing.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-01`

### FMT-02 Front matter holds the portable metadata
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteDocumentTest`, `VaultNoteIdKeyTest`
A YAML block at the top of the file. Keys the app owns: `allnotes-id`, `title` (only when it differs from the file
name), `pinned`, `archived`, `hidden`, `isList`, `markdown`, `color`, `notebook`, `tags`, `reminders`, `created`,
`modified`. Dates the app writes are epoch seconds. Reminder times are ISO-8601 UTC.

**Acceptance criteria**
- AC1: A note round-trips through a YAML block and a body.
- AC2: The identity key is `allnotes-id`, written once and rendered first in the block.
- AC3: The block carries pinned, archived, hidden, isList, markdown, color, notebook, tags, reminders, created and
  modified; `title` is written only when it differs from the file name.
- AC4: Dates are written as epoch seconds and reminder times as ISO-8601 UTC.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-02`

### FMT-03 Foreign front matter survives every save
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteDocumentTest.preservedForeignYaml_roundTrip`, `VaultFolderBlockerScenariosTest.blocker8_obsidianYamlRoundTrip_tagsScalarAndIsoDates`, `VaultForeignYamlMetadataTest`, `VaultNoteIdKeyTest.foreignId_isKeptVerbatim_andIsNotIdentity`
Keys the app does not own are kept verbatim. Obsidian forms (scalar `tags`, block lists, ISO dates, unparseable dates)
round-trip unchanged while the app's value is unchanged. A foreign change to such a key is pulled without a conflict.
A foreign `id:` is no longer taken over: the app's identity lives in `allnotes-id`.

**Acceptance criteria**
- AC1: Foreign keys such as `aliases` and `foo` are unchanged after a save.
- AC2: Scalar `tags`, block-list tags and ISO or unparseable dates round-trip while the app's value is unchanged.
- AC3: A foreign change to a foreign key is pulled without a conflict copy.
- AC4: A foreign `id:` is preserved verbatim and is never used as the note's identity.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-03`

### FMT-04 A notebook is a folder
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNotebookFolderTest`
A note in a notebook lives in the notebook's folder (a nested folder whose last segment names the notebook also
counts). Changing the notebook moves the file and its assets. Renaming a notebook moves its notes, and deleting one
moves them to the root. Emptied folders are pruned per FLD-11.

**Acceptance criteria**
- AC1: A note in notebook "Work" is written to `Work/<title>.md`; a note without a notebook stays at the root.
- AC2: Changing the notebook moves the file and its assets, and prunes the emptied folder.
- AC3: Renaming a notebook moves its notes and leaves no old notebook behind; deleting one moves notes to the root.
- AC4: A note already in a nested folder whose last segment matches the notebook stays where it is.
- AC5: A notebook's name is a valid folder name (FMT-11).

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-04`

### FMT-05 Attachments live beside the note
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultAttachmentsTest`, `VaultAttachmentMembershipTest`, `VaultNoteRelocationOwnedAssetsTest`, `VaultFolderHarnessScenariosTest.blocker1_renameNote_movesOwnedAttachments_andUpdatesPaths`
Attachment files are copied into a folder named after the note (`Work/Plan/photo.jpg` for `Work/Plan.md`) and linked
with relative Markdown links. They move with the note. A removed attachment's link is removed without rewriting the
surrounding text, and code blocks are never touched.

**Acceptance criteria**
- AC1: An attachment is stored under `<note folder>/<note title>/<name>` and linked with a relative Markdown link.
- AC2: Renaming or moving the note moves its owned attachments and rewrites their relative links.
- AC3: Removing an attachment strips only its link; the surrounding prose and code blocks are untouched.
- AC4: A path that belongs to another note or folder is never treated as owned.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-05`

### FMT-06 Task lists are Markdown checkboxes
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteDocumentTest.isList_falseFromYamlDoesNotForceChecklistUi`
A checklist note writes `- [ ]` / `- [x]` lines and sets `isList: true`.

**Acceptance criteria**
- AC1: A checklist note writes `- [ ]` and `- [x]` lines and `isList: true`.
- AC2: `isList: false` in a foreign file does not turn checkbox-looking text into a task note.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-06`

### FMT-07 Housekeeping files are never notes
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultHousekeepingScanTest`, `VaultTrashBinTest.androidTrashFile_isNotImported_andATrashTitleShowsTheOriginalName`
Only `.md` and `.txt` files are notes. The scan skips dot-folders at any depth (`.obsidian/`, `.trash/`,
`.allnotes-staging/`), Syncthing `*.sync-conflict-*` files, and Android `.trashed-<n>-<name>` files. A note whose
*title* starts with a dot is still a note.

**Acceptance criteria**
- AC1: Dot-folders at any depth are skipped and do not make an otherwise empty folder fail its scan.
- AC2: `*.sync-conflict-*` and `.trashed-<n>-<name>` files are never indexed, renamed or deleted.
- AC3: A title that starts with a dot, such as `.NET notes.md`, is still imported as a note.
- AC4: Only `.md` and `.txt` files are notes; other files are skipped.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-07`

### FMT-08 Hashes ignore formatting the app does not control
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultFolderBlockerScenariosTest.blocker2_*`, `VaultImportBaselineOrderingTest`
Change detection compares a canonical rendering of the note, not raw bytes. Quoting, a leading blank line, trailing
whitespace and the `allnotes-id` line do not make a note look changed.

**Acceptance criteria**
- AC1: Repeated saves that only requote the title or add a leading blank line produce no conflict copy.
- AC2: A second sync of an unchanged imported file pushes nothing and creates no conflict.
- AC3: A note written before reminders existed keeps its old hash after the upgrade.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-08`

### FMT-09 Files from other tools are completed on first sync
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteDateBackfillScenariosTest`, `VaultNoteDateBackfillNoShiftTest`, `VaultNoteDateBackfillFreshNoteTest`, `VaultNoteDocumentMissingDatesTest`, `VaultNoteDocumentEmptyFrontMatterTest`, `VaultNoteIdScenariosTest.newFile_gainsAnId_andASecondSyncDoesNotRewriteIt`
An external editor may create a Markdown file with no front matter at all, or with front matter that has none of the
app's keys. The first sync that sees such a file imports it as a note and writes the attributes the app requires into
the file, so every device identifies and dates it the same way. The note's text and any foreign keys stay unchanged.
The write happens once: a second sync does not touch the file.
Required set (confirmed by the owner): `allnotes-id`, `created` and `modified`. A missing `created` or `modified` is
filled from the note's own dates when the app already has the note (they equal the file's modification time for a note
imported in the same sync, and they must not jump to the file's current time after an external edit), and from the
file's own modification time (epoch seconds, which the sync tool preserves) when it has none, so every device writes
the same bytes. A note with unsynced local changes gets no dates from this step; its push writes them. All missing keys are added in one write; keys that are present, including dates in a format the app
cannot parse, are never changed. The id is derived as before (IDN-04: the file's own `created` before this write, or
the path). The note's dates in the app match what was written, so the write does not make the note look edited.
Non-UTF-8 files and files starting with a byte-order mark are not rewritten.

**Acceptance criteria**
- AC1: A Markdown file with no front matter is imported as a note.
- AC2: Its front matter gains `allnotes-id`, `created` and `modified` (the file's modification time in epoch seconds)
  in one write; a second sync does not rewrite the file.
- AC3: Two devices that receive the same file (same bytes, same modification time) write identical bytes.
- AC4: The note's text, any foreign keys and any existing `created`/`modified` value (even one the app cannot parse)
  are unchanged.
- AC5: After the write the note is not considered locally edited, and nothing is pushed back.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-09`

### FMT-10 Attachment files keep type-appropriate extensions
**Status:** Implemented · **Scope:** Core · **Tests:** `AttachmentExtensionsTest`, `VaultMediaWriterExtensionTest`,
`VaultAttachmentsTest.mergeAttachmentLinks_nonImageUsesFileLink`, `MediaStorageManagerPersistUnitTest`
When the app copies an attachment into the vault, the stored basename includes an extension that matches the MIME type
and attachment kind (for example `.m4a` for in-app recordings). Images are linked as `![](path)` in the note body;
audio, video and other files use `[description](path)`.

**Acceptance criteria**
- AC1: A vault attachment basename always includes a recognized file extension when MIME or type is known.
- AC2: In-app audio recordings are stored with an `.m4a` (or equivalent AAC-in-MP4) extension, not a bare label.
- AC3: New exports append image markdown for images and file markdown for audio, video and generic attachments.
- AC4: Existing vault files without an extension are not renamed automatically (forward-only).
- AC5: Non-media attachments saved without a display name use a generic staging extension (for example `.bin`), not `.jpg`.
- AC6: The attach picker includes PDF and plain text alongside image, video and audio.

**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-10`

### FMT-11 Names are valid on every file system
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultPathsNameValidationTest` (AC1–AC6), `VaultFolderBlockerScenariosTest.blocker7_cjkLongTitle_savesAndSyncs` (AC6), `NotebookDialogViewModelTest`, `NotebookRepositoryNameGuardTest`, `VaultNotebookFolderTest`, `HistoryCatalogApplierSafetyTest`, `BackupRestoreVaultTest`, `NotebookNameMigrationTest` (AC7)
A note file or notebook folder name may leave this device for a case-insensitive, Unicode-normalizing or Windows
volume. One validator, `VaultPaths.validateSegmentName`, states what a segment may be: non-blank, free of
`\ / : * ? " < > |` and control characters, no leading dot, no trailing dot or space, not a Windows reserved name
(`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, case-insensitive, before the first dot), and at most 200
UTF-8 bytes. `VaultPaths.collisionKey` (NFC + lowercase) is how two names are compared for "the same folder". A note's
title stays free-form; only the file name derived from it is sanitized, and the result always satisfies the validator.
A notebook name, by contrast, is rejected at input rather than altered, so the notebook name and its folder name agree.

**Acceptance criteria**
- AC1: Illegal characters (`\ / : * ? " < > |` and control characters) never reach a note file or notebook folder name.
- AC2: A leading dot is never used for a note file or a notebook folder (`.`, `..`, `.trash`, `.allnotes-staging`).
- AC3: A segment never ends with a dot or a space and never uses a Windows reserved name (`CON.md`, `con`, `NUL`, `COM1`).
- AC4: A segment is at most 200 UTF-8 bytes, cut without splitting a character.
- AC5: Names that differ only by case or Unicode normalization have the same collision key, so they are the same folder.
- AC6: A note title stays free-form; only the file name is sanitized.
- AC7: A notebook name is rejected at input, never altered, and always equals its folder name; names arriving from a folder, front matter, history or a backup never create an invalid notebook or fail a sync. Existing invalid notebooks are renamed once, collision-safely.

**Screens:** DLG-05
**Gherkin:** [`file-format.feature`](../features/file-format.feature) — `@FMT-11`
