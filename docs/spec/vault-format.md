# Notes folder format (normative)

This is the contract between every AllNotes implementation that opens the same notes folder. A second
implementation (another platform, a web client, a desktop tool) that follows this document produces the same bytes as
the Android app, so the sync tool sees no conflicts and every device agrees on note identity.

- The requirements say *what* must hold ([FMT](../requirements/file-format.md), [IDN](../requirements/identity.md),
  [HST](../requirements/history.md)). This file says *exactly how*, at byte level.
- Executable examples are in [fixtures/](fixtures/README.md); the Android build proves each one with
  `VaultContractFixturesTest`. When this document and a fixture disagree, the fixture (and the test) wins and this
  document is fixed.
- Procedures that decide what a sync does (hashes, identity matching, conflict and bin rules) are in
  [algorithms.md](algorithms.md).
- "MUST" marks what a second implementation has to match byte for byte; everything else is description.

## 1. Folder layout

```text
<notes folder>/
  Shopping.md                         a note without a notebook (file at the root)
  Shopping/                           the asset folder of Shopping.md (its attachments)
    photo.jpg
  Work/                               a notebook: a first-level or deeper folder
    Meeting.md
    Meeting/recording.m4a
    Meeting (conflict 1731500000).md  a conflict copy (CON-02)
  Ideas.txt                           a plain-text note (markdown off)
  .trash/                             the bin (DEL-01)
    Work/Old plan.md                  a binned note, at its original relative path
  .allnotes/                          folder history (HST); see §8
    README.md
    devices/<deviceId>/device.json
    devices/<deviceId>/events-2026-11.jsonl
  .obsidian/                          another tool's housekeeping: never read, never written
  Note.sync-conflict-20240101-x.md    a Syncthing conflict file: never read, never written
  .trashed-1731500000-Note.md         an Android Files-app bin file: never imported
```

### 1.1 What a scan reads (FMT-07, CON-04, HST-01)
A scan walks the folder recursively. It MUST:
- skip a directory whose name starts with `.` (this covers `.trash`, `.allnotes`, `.allnotes-staging`, `.obsidian`,
  `.git`, …) at any depth;
- index a file only when its name ends with `.md` or `.txt` (case-insensitive), **and** the name does not start with
  `.allnotes`, does not match `^\.trashed-\d+-` (case-insensitive), and does not contain `.sync-conflict-`
  (case-insensitive);
- treat a failed or partial listing as *no result* (never as an empty folder) — see
  [algorithms.md §3](algorithms.md#3-scan-guards-uncertainty-means-keep).

Everything else in the folder (images, PDFs, other tools' files) is not a note. Files under a note's asset folder are
attachments only when the note's body links to them (§6).

### 1.2 Relative paths
Every path in this document, in the history logs and in app state is a **normalized relative path**: forward slashes,
no leading slash, no empty or `.` segments, and never a `..` segment (a path with `..` is rejected, not resolved).
Comparison is by exact string, except where §2.4 says otherwise.

## 2. Names

### 2.1 Note file name (FMT-01, FMT-11)
`fileName = sanitize(titleOrUntitled + ext)` where
- `titleOrUntitled` is the note title, or `Untitled` when the title is blank;
- `ext` is `.md` when the note has Markdown enabled and `.txt` otherwise.

The note's folder is its notebook folder (§2.3), or the root without a notebook. When that path is taken by another
note, the first free `<stem>-2<ext>`, `<stem>-3<ext>`, … is used ("unique sibling"). A case-only rename of the same
note is a rename in place.

### 2.2 `sanitize(name)` (FMT-11) — MUST match
Applied to a whole file name including its extension:
1. Normalize to Unicode NFC.
2. Replace every character in `\ / : * ? " < > |` and every control character U+0000–U+001F with `_`, then trim
   leading and trailing whitespace.
3. If blank, use the fallback (`file` unless a caller gives another).
4. If it starts with `.`, replace that first character with `_`.
5. Trim trailing spaces and dots; if that leaves nothing, use `file`.
6. If the stem (text before the first `.`) is a Windows reserved name — `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`,
   `LPT1`–`LPT9`, compared case-insensitively — insert `_` right after the stem (`con.md` → `con_.md`).
7. If the UTF-8 encoding exceeds **200 bytes**, truncate on code-point boundaries, keeping the extension (the text
   from the last `.`, when that dot is not the first character) whole; then trim trailing spaces and dots again
   (`file` if empty).
8. If truncation re-created a reserved stem, replace the stem's last character with `_` (a one-character stem gets a
   leading `_` instead).

### 2.3 Notebook folder (FMT-04, FMT-11)
- A note's notebook is the **last segment** of its parent folder (`A/B/Note.md` → notebook `B`); a root file has no
  notebook unless front matter says so (§4.3).
- A notebook name MUST be a valid segment (§2.4) to become a folder. The app refuses invalid names when the user types
  them; a name from outside (front matter, history, backup) that is invalid creates no notebook and no folder.
- When the app moves a note into a notebook, the target folder is the notebook name at the root — except when the
  note's current parent folder already ends in a segment equal (case-insensitively) to the notebook name, which keeps
  nested folders as they are.

### 2.4 Valid segment and collisions (FMT-11)
A segment is valid when it is not blank, has no character from §2.2 step 2, does not start with `.`, does not end
with `.` or space, has no reserved stem (§2.2 step 6) and is at most 200 UTF-8 bytes. The problems are reported in
that order. Two folder names are "the same folder" when `lowercase_root(NFC(name))` is equal.

### 2.5 Conflict copy name (CON-02)
`<parent>/<sanitize("<base> (conflict <epochSeconds>)<ext>")>` where `base` is the note's file name without `.md` or
`.txt`, `ext` is `.txt` for a `.txt` note and `.md` otherwise, and `epochSeconds` is the other version's file
modification time. A taken name gets the unique-sibling suffix (§2.1). A file is recognized as a conflict copy when its
name matches ` \(conflict \d+\)\.(md|txt)$` (case-insensitive).

### 2.6 Bin path (DEL-01)
Binning moves a note's file to `.trash/<its relative path>`, using the unique-sibling suffix when that is taken.
Restoring moves it back to its original path, or to a unique sibling if that is now taken. The note's attachments stay
where they are while it is binned.

### 2.7 Title shown for a file
The title is the front-matter `title` when present and not blank; otherwise the file name without `.md`/`.txt`, with a
leading `.trashed-<digits>-` removed.

## 3. Encoding
- Note files are UTF-8. Lines end with `\n` when the app writes a whole file.
- When the app edits only one line of an existing file (§5), it keeps the file's own line endings: `\r\n` if the file
  contains any `\r\n`, otherwise `\n`.
- A file that starts with a byte-order mark, or whose text is not valid UTF-8, is read but never rewritten by the id
  and date backfill (FMT-09, IDN-02).

## 4. Front matter

### 4.1 Recognizing the block
A file has front matter when it matches `\A---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)$`: group 1 is the YAML,
group 2 the body. Otherwise the whole file is the body. A block with nothing in it (`---\n---\n`) is front matter with
no keys.

### 4.2 Keys the app owns (FMT-02)

| Key | Type as written | Meaning | Read rules |
|---|---|---|---|
| `allnotes-id` | `"<uuid>"` | Note identity (IDN-01) | Quoted or bare scalar; blank = none. |
| `title` | `"<text>"` | Title, only when it differs from the file's base name | Quoted (double or single) or bare scalar. |
| `pinned` | `true`/`false` | Pinned | Exactly `true` or `false` (case-sensitive); anything else = absent ([FC-09](../audits/2026-11-13-full-coverage-review.md#fc-09)). |
| `archived` | `true`/`false` | Archived | As `pinned`. |
| `hidden` | `true`/`false` | Hidden | As `pinned`. |
| `isList` | `true`/`false` | Checklist note | As `pinned`. |
| `markdown` | `true`/`false` | Markdown rendering on | As `pinned`; absent = `true`. |
| `color` | `"<Name>"` | One of `Default Red Orange Yellow Green Teal Cyan Blue Purple Pink Brown Gray` | Exact, case-sensitive; unknown = absent. |
| `notebook` | `"<name>"` | Notebook name (§4.3) | Quoted or bare scalar. |
| `tags` | `["a", "b"]` or `[]` | Tag names, sorted | Inline list, block list (`- a`) or a single scalar. |
| `reminders` | block list or `[]` | Reminders (§4.4) | See §4.4. |
| `created` | epoch seconds | Creation time | Epoch seconds or a date (§4.5). |
| `modified` | epoch seconds | Last modification | As `created`. |

Every other root key is **foreign** and is kept verbatim, with its indented or `-` continuation lines (FMT-03).

When the app reads a key as absent, it keeps the value it already has for that note, or the default for a new note:
`false` for the booleans, `true` for `markdown`, `Default` for `color`, the file's modification time for dates.

Device-local note fields — compact preview, keep screen on — are **never** written to the file (see
[data-model.md](data-model.md)).

### 4.3 Notebook key
The app writes `notebook: "<name>"` for a note in a notebook and `notebook: ""` for a note without one. The folder
decides the notebook on import; the key is how another device learns about a notebook name it cannot see as a folder
(for example a note kept at the root).

### 4.4 Reminders (REM-01, REM-03)
Written form, sorted by time then name, expired reminders left out:
```yaml
reminders:
  - name: "Call Anna"
    at: 2026-11-14T09:30:00Z
```
- `at` is the ISO-8601 UTC instant with seconds (`YYYY-MM-DDTHH:MM:SSZ`; fractional seconds never occur because
  reminder times are whole seconds).
- `reminders: []` is written when the file already had a `reminders` key and the last reminder is gone, so other
  devices clear theirs. A note that never had reminders writes no key.
- Reading: items start with `-`; `name:` and `at:` (or `date:`) can come in any order; an item without a readable
  date is dropped. An inline value other than `[]` (for example `reminders: foo`) is treated as absent.

### 4.5 Dates the app reads
`created`, `modified` and reminder `at`/`date` accept, after removing surrounding double quotes: epoch seconds; an
ISO instant (`2024-06-15T12:00:00Z`); an offset date-time (`2024-06-15T12:00:00+02:00`); a local date-time, taken as
UTC (`2024-06-15T12:00:00`, or with a space: `2024-06-15 12:00`, seconds optional); a date, taken as UTC midnight
(`2024-06-15`). A value in any non-epoch form, or one that cannot be read, is kept verbatim while the app's value is
unchanged (§4.7).

### 4.6 Scalar quoting — MUST match
- Written strings are double-quoted, escaping only `\` → `\\` and `"` → `\"`. Nothing else is escaped.
  Known defect: a line break in a title is written raw and corrupts the block —
  [FC-08](../audits/2026-11-13-full-coverage-review.md#fc-08).
- Read: a double-quoted scalar unescapes `\\`, `\"`, `\n`, `\t` and `\x` → `x`; a single-quoted scalar unescapes
  `''` → `'`; a bare scalar is used as is (trimmed).
- Inline tag lists: items are separated by commas outside double quotes; a quoted item honours `\` escapes; a bare
  item is trimmed and stripped of stray `"`.

### 4.7 Writing the block — MUST match
Note: `allnotes-id` is the first line only when no verbatim known key is kept
([FC-07](../audits/2026-11-13-full-coverage-review.md#fc-07)).

Given the note and the file's previous text (if any), the app renders:

```text
---
<verbatim known keys>        known keys whose original formatting is kept (see below), in file order
allnotes-id: "<id>"          when the note has an id
title: "<title>"             only when the title differs from the file's base name
pinned: <bool>
archived: <bool>
hidden: <bool>
isList: <bool>
markdown: <bool>
color: "<Name>"
notebook: "<name>"           when the note has a notebook value (always, for a note the app writes)
tags: [...]                  "tags: []" when empty; items quoted and sorted by plain string order
reminders: ...               per §4.4, only when there is something to write
created: <epoch seconds>
modified: <epoch seconds>
<foreign keys>               verbatim, in their original order, trailing whitespace trimmed
---
<body, trailing whitespace trimmed>
```
followed by a single `\n`.

*Verbatim known keys.* When the previous file wrote `tags` as a scalar or a block list, or `created`/`modified` in a
non-epoch or unreadable form, that key's original lines are kept as they were, ahead of the generated keys, **as long
as the app's value has not changed** (tags compared as sorted lists; a date the app could not read is always kept). A
key kept this way is not generated again. Once the app changes the value, the verbatim lines are dropped and the
generated form is written.

*Order change.* Foreign keys move below the app's keys on the first save. Blank lines inside the YAML are kept with
the foreign part.

### 4.8 Body
- Text note: the note text.
- Checklist note (`isList: true`): one line per task, `- [x] <text>` when done and `- [ ] <text>` otherwise, joined
  with `\n` (FMT-06).
- Attachment links that the note owns but the body lacks are appended as new paragraphs (§6.3).
- The app trims trailing whitespace of the body before writing; the editor also trims leading and trailing whitespace
  of title and body on save (EDT finding: drops Markdown hard breaks).

Reading a checklist body: each line matching `^\s*[-+*] *\[([ xX])](.*)$` is a task (`x`/`X` = done; text = group 2,
trimmed). A line that does not match is appended directly (no separator) to the previous task's text; it is dropped
when no task precedes it. See [the audit](../audits/2026-11-13-full-coverage-review.md#fc-05) for the inconsistency
with the editor's own conversion.

## 5. Single-line edits (FMT-09, IDN-04)
Two operations change a file without re-rendering it, so nothing else moves:

**Set id** (`replaceNoteId`): the line `allnotes-id: "<id>"` (escaped per §4.6) replaces the existing `allnotes-id:`
line inside the front matter; if there is none it is inserted as the first line of the block; for an empty block it is
inserted after the opening `---`; a file with no front matter gets `---<nl>allnotes-id: "<id>"<nl>---<nl>` prepended.
`<nl>` is the file's own line ending (§3). The body is never searched.

**Add missing dates** (`withMissingDates`): for each of `created` then `modified` that has no root key at all (a key
with an empty, quoted or unreadable value counts as present), the line `created: <n>` / `modified: <n>` is added at the
end of the YAML block (or as a new block, as above). Existing keys are never changed.

## 6. Attachments (FMT-05, FMT-10, MED)

### 6.1 Where they live
A note's own attachments are stored in its **asset folder**: the note's path without `.md`/`.txt`
(`Work/Meeting.md` → `Work/Meeting/`). The file name is `sanitize(<original name with a suitable extension>)`, made
unique within the folder with `-2`, `-3`… before the extension. A file with identical bytes already in the folder is
reused instead of copied again.

### 6.2 Extensions (FMT-10)
A name keeps its extension when it is one of `jpg jpeg png gif webp heic heif bmp svg mp4 mkv webm mov 3gp mp3 m4a wav
ogg aac flac pdf zip txt md json xml bin`. Otherwise an extension is chosen from the MIME type (for example
`image/jpeg` → `.jpg`, `audio/mp4` → `.m4a`, `application/octet-stream` → `.bin`, `image/<x>` → `.<x>`), and from the
attachment type when there is no usable MIME type: image `.jpg`, video `.mp4`, audio `.m4a`, other `.bin`.

### 6.3 Links
- Image: `![<description or "image">](<target>)`; any other type: `[<description or file name>](<target>)`.
- `target` is the path relative to the note's folder (`Meeting/photo.jpg` for `Work/Meeting.md`). Each segment is
  percent-encoded (form encoding with `+` turned into `%20`); when the unencoded path contains a space or `%`, the
  target is wrapped in `<…>`.
- New links are appended to the body, each separated from the text before it by a blank line.

### 6.4 Which links are attachments (read)
Every Markdown image link `![..](t)` and every link `[..](t)` whose target `t` (after stripping `<…>` and a trailing
`"title"`) is not an anchor (`#…`), not `mailto:`, has no `://` and is not absolute, resolves relative to the note's
folder (with `%HH` decoding and `..` allowed only while it stays inside the folder) to a **vault attachment**. Its type
comes from the extension: image (`jpg jpeg png gif webp heic heif`), video (`mp4 mkv webm mov 3gp`), audio (`mp3 m4a
wav ogg aac flac`), otherwise generic (so `bmp` and `svg`, accepted on attach, read back as generic:
[FC-10](../audits/2026-11-13-full-coverage-review.md#fc-10)).

> Known defect: this also turns a link to another note (`[Plan](Plan.md)`) or a scheme-less web link
> (`[site](example.com)`) into a generic attachment — [FC-01](../audits/2026-11-13-full-coverage-review.md#fc-01).

Links inside fenced code blocks and inline code spans are not removed when an attachment is removed; only image links
are removed from the body when the user removes an image (MED-10 finding for other types).

### 6.5 Moving a note
A rename or notebook move moves the note's owned attachments into the new asset folder and rewrites their links.

## 7. Write safety (WRS)
Note files are never written in place without a verified copy:
1. the new text is written to an app-private staging area and read back (hash and length must match);
2. a durable journal records the staged write;
3. the text is published to the note's path and read back; on mismatch the previous bytes are restored.
An empty text is never written. A journal left by a crash is completed or quarantined on the next start. Staging and
journals are **not** in the notes folder. Other implementations may use any mechanism that gives the same guarantees.

## 8. Folder history (HST)

### 8.1 Files
- `.allnotes/README.md` — fixed text, written once when missing and never rewritten.
- `.allnotes/devices/<deviceId>/device.json` — the device's manifest, written only by that device.
- `.allnotes/devices/<deviceId>/events-YYYY-MM.jsonl` — that device's events for one UTC month, one JSON object per
  line, oldest first, each line ending in `\n`. A Syncthing copy named `events-YYYY-MM.sync-conflict-….jsonl` is read
  too.

`deviceId` is a random UUID (lowercase, hyphenated) created per install **and per notes folder**. A device writes only
under its own `deviceId`.

### 8.2 Manifest (HST-06)
```json
{"format":1,"deviceId":"…","name":"Pixel 9 · a1b2c3","appVersion":"…","writes":["note.bin",…],"partialApplyOk":true,"lastSeenDay":"2026-11-13"}
```
Written as one line plus `\n`; rewritten only when a field changes, and `lastSeenDay` (UTC) at most once a day. A
manifest whose `deviceId` differs from its folder name is ignored.

### 8.3 Event line (HST-03, HST-04)
Fields in this order; absent optional fields are omitted (never `null`):

| Field | Type | Meaning |
|---|---|---|
| `v` | int | Line format version (1). |
| `eid` | string | `<deviceId>:<seq>`; unique across all copies. |
| `dev` | string | Writing device's id. |
| `seq` | int | Gap-free per device, starting at 1. |
| `lc` | int | Lamport clock: `max(lc seen in any log) + 1` at write time. |
| `t` | int | Wall-clock time in **epoch milliseconds**, display only. Chooses the month file (UTC). |
| `op` | string | See below. |
| `id` | string | The note's `allnotes-id` (note ops only). |
| `path` | string | Relative path the op is about. |
| `to` | string | Destination path, or the new name for renames. |
| `name` | string | Notebook/tag name, or the old device id for `device.reid`. |

| `op` | Fields | Recorded when |
|---|---|---|
| `note.bin` | `id`, `path` (original), `to` (`.trash/…`) | The user bins a note and its file is moved. |
| `note.restore` | `id`, `path` | The user restores a note. |
| `note.purge` | `id`, `path` | The user permanently deletes a note they binned (not the automatic retention purge). |
| `note.move` | `id`, `path` (old), `to` (new) | The app renamed or moved a note's file. |
| `notebook.create` / `tag.create` | `name` | Created by the user. |
| `notebook.rename` / `tag.rename` | `name` (old), `to` (new) | Renamed by the user. |
| `notebook.delete` / `tag.delete` | `name` | Deleted by the user. |
| `device.reid` | `name` (old device id) | This install switched to a new id after detecting a clone. |

Note ops without an `id` are not recorded.

### 8.4 Reading (HST-03, HST-06)
- Read all `events-*.jsonl` under every device folder, in sorted path order; skip blank lines; a line that does not
  parse, or lacks `eid` or `dev`, is skipped and counted.
- Duplicate `eid`: the first wins; a duplicate with different content is clone evidence (the writing device rotates
  its id).
- Merged order is `(lc, dev, seq)` ascending — never `t`.
- An event is *applicable* only when its device has a valid manifest, its `op` is known, and `v ≤ 1` or the manifest
  says `partialApplyOk: true`. Others are shown, never applied.
- Unknown JSON fields are ignored. `lc` values above `2^62` never seed the local clock.

How events are applied is in [algorithms.md §6](algorithms.md#6-folder-history-apply-and-fold).

### 8.5 Retention (HST-13)
Each device deletes only its **own** month files older than its "Keep folder history" setting, at most once per UTC
day, and always keeps its newest month file.
