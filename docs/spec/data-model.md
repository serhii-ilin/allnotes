# Data model (platform-neutral)

What an AllNotes implementation keeps, and where each value lives. Article 1 of the
[constitution](../CONSTITUTION.md) decides the split: everything that matters to the user is **portable** (it is in
the notes folder and every device reads it back), or it is explicitly **device-local**. The index (on Android a Room
database) is a cache that can be rebuilt from the folder.

Legend: **P** = portable (in the note file, see [vault-format.md](vault-format.md)); **D** = device-local;
**I** = index only (rebuilt from the folder).

## Entities

### Note
| Field | Type | Where | File key / rule |
|---|---|---|---|
| id | integer | I | Local row number; never leaves the device. |
| identity | string (UUID) | P | `allnotes-id` (IDN-01). Kept in mapping state on the device. |
| title | string | P | File name, or `title` when it differs (vault-format §2.1, §2.7). |
| content | string | P | Body of a text note. |
| isList | bool | P | `isList`; body holds the tasks. |
| taskList | list of Task | P | Body of a checklist note. |
| isMarkdownEnabled | bool (default true) | P | `markdown`, and the extension `.md` / `.txt`. |
| isPinned | bool | P | `pinned`. |
| isArchived | bool | P | `archived`. |
| isHidden | bool | P | `hidden`. |
| color | enum NoteColor | P | `color`. |
| notebookId | Notebook? | P | Parent folder, and `notebook`. |
| tags | set of Tag | P | `tags`. |
| reminders | list of Reminder | P | `reminders` (future reminders only). |
| attachments | list of Attachment | P | Links in the body + files in the asset folder. |
| creationDate | epoch seconds | P | `created`. |
| modifiedDate | epoch seconds | P | `modified`. |
| isDeleted | bool | P | File under `.trash/` (user bin) or missing twice (missing-file bin, D). |
| deletionDate | epoch seconds? | D | When it entered the bin on this device; drives the auto-purge (DEL-07). |
| isCompactPreview | bool | D | Card shows only the title (NTL-02). |
| screenAlwaysOn | bool | D | Keep the screen on in the editor (EDT-09). |
| isLocalOnly | bool | — | Legacy flag, cleared on every sync; no behavior. |

NoteColor values: `Default Red Orange Yellow Green Teal Cyan Blue Purple Pink Brown Gray`.

### Task (checklist item)
| Field | Type | Where | Rule |
|---|---|---|---|
| id | integer | I | Line index on import; position in the editor. |
| content | string | P | Text after `- [ ] ` / `- [x] `. |
| isDone | bool | P | `x` or `X` = done. |

### Attachment
| Field | Type | Where | Rule |
|---|---|---|---|
| type | enum `IMAGE AUDIO VIDEO GENERIC` | P | From the file extension (vault-format §6.4). |
| path | string | P/D | `vaultrel:<relative path>` once copied into the folder; an app-private URI until then (D). |
| description | string | P | Link text. Edits are lost on a later import (finding in the 2026-11-12 review). |
| fileName | string | — | Used only inside a backup zip. |

### Notebook
| Field | Type | Where | Rule |
|---|---|---|---|
| id | integer | I | Local row number. |
| name | string | P | A folder name (valid segment, FMT-11); empty notebooks travel through history (HST-10). |

### Tag
| Field | Type | Where | Rule |
|---|---|---|---|
| id | integer | I | Local row number. |
| name | string | P | Compared exactly (case-sensitive), not trimmed (ORG finding). |

### Reminder
| Field | Type | Where | Rule |
|---|---|---|---|
| id | integer | I | Local row; used as the alarm id. |
| name | string | P | Reminder text. |
| date | epoch seconds | P | `at` in ISO-8601 UTC. |
| noteId | Note | I | Owner. |

### Mapping state
One per note that has a file in the current folder. Device-local, never synced; it records the last point where note
and file agreed. Fields are listed in [algorithms.md](algorithms.md). The Android build stores it as JSON in
`cloud_ids.extras` together with the note's relative path (`storageUri`) and `isDeletedLocally`.

### Folder history
Per-device logs in the folder (P, but optional — HST-08). Device-local companions: the history device id and last
written `seq` per folder, the outbox of events not yet written, the last retention day, and the "Use folder history
for sync" switch.

## Device-local settings catalogue (SET)
Settings never travel with the folder (SET-01). Keys are the Android DataStore keys; a port may name them differently
but MUST offer the same values and defaults.

| Setting | Key | Values (default **bold**) | Requirement |
|---|---|---|---|
| Layout mode | `layout_mode` | **GRID**, LIST | SET-04 |
| Theme mode | `theme_mode` | **SYSTEM**, DARK, LIGHT | SET-02 |
| Dark theme | `dark_theme_mode` | **STANDARD**, BLACK | SET-02 |
| Colour scheme | `color_scheme` | **BLUE**, TRUE_RED (Red), TRUE_YELLOW (Yellow), GREEN, PINK, YELLOW (shown as Orange), RED (shown as Purple), SYSTEM (dynamic colour, newer OS only) | SET-03 |
| Sort notes | `sort_method` | TITLE_ASC, TITLE_DESC, CREATION_ASC, CREATION_DESC, MODIFIED_ASC, **MODIFIED_DESC** | SET-05, NTL-05 |
| Sort tags | `sort_tags_method` | **TITLE_ASC**, TITLE_DESC, CREATION_ASC, CREATION_DESC | SET-05 |
| Sort notebooks in the drawer | `sort_navdrawer_notebooks_method` | **TITLE_ASC**, TITLE_DESC, CREATION_ASC, CREATION_DESC | SET-05 |
| Backup strategy | `backup_strategy` | **INCLUDE_FILES**, KEEP_INFO, KEEP_NOTHING | SET-13 |
| Delete notes in bin after | `note_deletion_time` | **WEEK** (7 d), TWO_WEEKS, MONTH (30 d), NEVER, INSTANTLY | SET-11, DEL-07 |
| Keep folder history | `history_retention` | ONE_MONTH, **THREE_MONTHS**, SIX_MONTHS, ONE_YEAR, FOREVER | SET-12, HST-13 |
| Date format | `date_format` | **MMMM d yyyy**, d MMMM yyyy, MM/d/yyyy, d/MM/yyyy, yyyy-MM-dd | SET-07 |
| Time format | `time_format` | **HH:mm**, hh:mm a | SET-07 |
| Open media in | `open_media` | **INTERNAL**, EXTERNAL | SET-09 |
| Show date created/modified | `show_date` | **YES**, NO | SET-07, NTL-03 |
| Editor font size | `editor_font_size` | **DEFAULT**, 10, 15, 20, 25, 30, 35, 40, 45, 50 | SET-06 |
| Edit/View button position | `show_fab_change_mode` | **FAB**, TOPBAR | SET-08 |
| Notes without a notebook in the drawer | `group_notes_without_notebook` | YES, **NO** | SET-10 |
| Move checked items to the bottom | `move_checked_items` | **YES**, NO | SET-06 |
| Default editor mode | `default_editor_mode` | **VIEW**, EDIT | SET-06 |
| Storage | `cloud_service` | **FILE_STORAGE** (legacy DISABLED/NEXTCLOUD read as FILE_STORAGE) | FLD |

The two date and time format rows list display patterns; the stored values are the enum names
(`MMMM_d_yyyy`, `d_MMMM_yyyy`, `MM_d_yyyy`, `d_MM_yyyy`, `yyyy_MM_dd`, `HH_mm`, `hh_mm`).

Device-local state that is not a setting (kept in encrypted storage on Android, backup-excluded):

| Value | Purpose | Requirement |
|---|---|---|
| Folder location | The confirmed notes folder (`STORAGE_LOCATION`). | FLD-01 |
| Folder configured at | Epoch seconds of the last folder choice; notes created before it are exported once. | FLD-03 |
| Cloud-folder warning shown for | The folder the warning was last shown for. | FLD-10 |
| Folder generation | Increases on every folder change; stale work for an old folder is dropped. | FLD-06 |
| History device id, last seq, last prune day, apply switch | Per folder. | HST-02, HST-11, HST-13 |
| Notebook names migrated | One-time FMT-11 migration done. | FMT-11 |
| Last shown What's new id | What's new shows once per update. | INT-09 |

## Backup zip (BAK)
A backup is a zip with `backup.json` and, for the "include files" strategy, a `media/` folder with the attachment files.
`backup.json` is the serialized set of notes (with tasks, attachments and flags), notebooks, reminders, tags,
note-tag joins and mapping rows, plus a `version` number. Restore merges into the current data (BAK-03…BAK-05) and
then syncs. The zip format is device-to-device only; it is not part of the folder contract.
