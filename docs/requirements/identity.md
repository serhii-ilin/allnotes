# Note identity, renames and moves (IDN)

How a file is tied to a note when it can be renamed or moved on any device. Use cases: UC-09, UC-10.
Scenarios: SC-02, SC-03, SC-05, SC-11. Gherkin: [identity.feature](../features/identity.feature).

### IDN-01 Every note file carries a stable id
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteIdKeyTest`, `VaultNoteDocumentTest.noteId_roundTripsAndIsInsertedOnce`, `VaultNoteIdScenariosTest`
The file's front matter holds a unique id under the namespaced key `allnotes-id`, generated once and never changed by
the app afterwards. Room keeps a copy on the note's mapping only as an index. The file is the truth. A foreign `id:` key
is a different key: it is preserved verbatim and never used as identity.

**Acceptance criteria**
- AC1: An exported note's front matter contains exactly one `allnotes-id` line, and later saves keep the same value.
- AC2: A file without front matter gains a block containing `allnotes-id` when the id is first written.
- AC3: A foreign `id:` key stays byte-for-byte and does not become the note's identity, even when `allnotes-id` is
  added next to it.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-01`

### IDN-02 Files are matched to notes by path, then id, then unique body
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteIdentityTest`
On each scan a mapped note keeps its file when that file is still at its path. Otherwise the note follows the file
that carries its id. If several files carry it, the newest is kept and the others are handled per IDN-05. A note
without a recorded id may follow a file without an id only when exactly one such file has exactly its (non-blank)
body. Ambiguous matches change nothing.

**Acceptance criteria**
- AC1: A mapped note keeps the file at its recorded path when that path is still in the scan.
- AC2: If the path is gone, the note follows the file carrying its `allnotes-id`.
- AC3: A note without an id follows a renamed file only when exactly one id-less file has exactly its non-blank body.
- AC4: Two files with the same body, or a blank body, match nothing.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-02`

### IDN-03 A rename or move made elsewhere keeps the note
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRemoteRenameNoConflictTest` (3 tests), `VaultNoteIdScenariosTest.renamedFile_keepsTheNoteAndFollowsTheNotebook`, `VaultNoteIdScenariosTest.renamedFile_withLocalEdit_isPushedAtTheNewPath`
When another device or tool renames or moves a note's file, the note stays one note. Its notebook follows the new
folder. Its title follows the new file name when the old title was the old file name; a title edited in the app wins.
No duplicate note, conflict copy, bin entry or move-back happens. The local side is hashed at the path the baseline
was taken at, so the front-matter change caused by the path change is a pull, not a conflict.

**Acceptance criteria**
- AC1: A title rename elsewhere leaves one note at the new path with the new title and no conflict copy.
- AC2: A notebook move elsewhere updates the note's notebook and never moves the file back.
- AC3: A local edit made before the move is pushed at the new path, with one note and no conflict.
- AC4: The mapping, baseline and title all follow the new path after two syncs.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-03`

### IDN-04 Files without an id get one, safely
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteIdBackfillTest` (4 tests), `VaultNoteIdScenariosTest.newFile_gainsAnId_andASecondSyncDoesNotRewriteIt`
The next sync inserts a `allnotes-id` line into any mapped file that lacks one, changing nothing else. Notes about to
be pushed get theirs in that write. This must also hold:
- a file that cannot be written is skipped and logged, and the sync still succeeds with its other pushes;
- devices that add ids independently write the same bytes, so the sync tool sees no conflict;
- an external edit that lands between the read and the write is never overwritten (the compare-and-swap fails and the
  next sync gives the file an id);
- the backfill adds no extra full read of the folder per sync; it uses the scan result already in memory.

Files created by other tools are covered by FMT-09.

**Acceptance criteria**
- AC1: A mapped file without `allnotes-id` gains one on the next sync, and a second sync does not rewrite it.
- AC2: A read-only file is skipped; the sync result stays `Success` and its other pushes land.
- AC3: Two devices that backfill the same files independently write identical bytes.
- AC4: An edit that lands between read and write survives and gets an id on the next sync.
- AC5: The backfill reads no mapped file outside the scan.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-04`

### IDN-05 A shared id never hides a file, and both survive
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNoteIdScenariosTest.twoCopiesOfTheSameId_becomeTwoNotesWithDistinctIds`, `VaultDuplicateIdScenariosTest`, `VaultNoteIdOwnerOrderTest`, `VaultSharedIdOwnerMtimeTest`
When two files carry the same `allnotes-id`, the mapped file keeps the note. Any other file is imported as its own
note and re-identified with `VaultNoteIds.forFile(path, created)`, so no text is lost and nothing is silently skipped.
A file the user copied while the original is still in place becomes a new note with its own id. Which file keeps the id is decided the same way on every device, from facts in the files only: the smallest
front-matter `created`, then the oldest file modification time (a copy is normally newer), then the path. A conflict copy written by the app
carries its own id (CON-02).

**Acceptance criteria**
- AC1: Copying `Plan.md` to `Plan copy.md` yields two notes, two distinct ids, and no rewritten file on a third sync.
- AC2: When two files carry one id and the mapped path still exists, the mapped file keeps the note and the other is
  imported as a new note.
- AC3: When the mapped path is gone and two files carry its id, the newest keeps the note and the other is imported.
- AC4: Both texts survive a rename on one side and an edit on the other.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-05`

### IDN-06 Case-only renames keep the note
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultCaseMismatchScenariosTest`, `VaultFolderBlockerScenariosTest.blocker6_caseOnlyTitleRename_noDuplicateFile`
On case-insensitive volumes a file renamed only in case is reported under the mapped spelling and stays mapped.
Attachments and links survive.

**Acceptance criteria**
- AC1: A file listed under a different case stays mapped under the recorded spelling.
- AC2: The note stays live with one mapping and no duplicate file.
- AC3: A case-only title rename keeps the note's attachment bytes and links.

**Gherkin:** [`identity.feature`](../features/identity.feature) — `@IDN-06`
