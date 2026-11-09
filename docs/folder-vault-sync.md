# Folder vault sync (path-backed source of truth)

**Branch:** `cursor/folder-vault-sync-04d8`  
**Status:** In progress — see checklist below.

## Goals

- Selected SAF tree is the **source of truth** for note bodies, attachment bytes, and portable metadata.
- **Room** is a rebuildable index/cache for vault-backed notes only.
- **Identity** = normalized relative path from vault root (e.g. `Work/Meeting.md`). No UUIDs in files or IDs.
- **Fresh vault only:** enabling/changing the vault folder does not import or export legacy private notes; existing `isLocalOnly` / unmapped notes stay untouched.
- **Nextcloud** path unchanged (`ISyncBackend` + `SynchronizeNotes` mapping/title logic).

## Non-goals (this phase)

- Heuristic identity across external renames (old path vanishes, new path = new note).
- Full Obsidian plugin syntax, wikilinks, or bidirectional notebook folder layout on create.
- Migrating legacy private media into the vault.

## Architecture

```
┌─────────────────┐     pull/push      ┌──────────────────────────┐
│ NoteRepository  │◄────────────────►│ StorageBackend (vault)   │
│ + IdMapping     │   SyncNote.idStr │  SAF DocumentFile tree   │
│   storageUri=   │   = relative path│  .md + sibling assets    │
│   relative path │                  └──────────────────────────┘
└────────┬────────┘
         │ index
         ▼
   Room (notes, attachments JSON cache)
```

| Component | Responsibility |
|-----------|----------------|
| `VaultPaths` | Normalize paths, reject `..`, slash rules, safe file names |
| `VaultNoteDocument` | YAML front matter + body; parse/render portable metadata |
| `VaultAttachments` | Relative links, asset subfolder `{basename}/`, copy in/out |
| `VaultContentHash` | SHA-256 baseline in `IdMapping.extras` for conflict detection |
| `StorageBackend` | Recursive `.md` scan, staged writes, conflict copies |
| `SynchronizeNotes` | Path mapping; hash-aware pull; no delete if `getAll()==null` |

### Vault layout

```text
<vault root>/
  Work/Meeting.md
  Work/Meeting/camera_1.jpg    # assets beside note path
  .obsidian/                   # ignored
  .stfolder/                   # ignored
  .quillpad-staging/           # ignored; in-progress writes
```

Markdown body uses standard relative links, e.g. `![kitchen](Meeting/camera_1.jpg)`.

### Portable metadata (YAML front matter)

Supported: `title`, `pinned`, `archived`, `hidden`, `color`, `tags`, `notebook`, `isList`, `created`, `modified`, `markdown` (bool).  
Unsupported round-trip: Quillpad reminders, `screenAlwaysOn`, compact preview, widgets.

### Attachment storage in Room

Vault-backed attachments use `path` prefix `vaultrel:` + normalized relative path from vault root (e.g. `vaultrel:Work/Meeting/camera_1.jpg`). Legacy private URIs unchanged.

## Safety

- Persisted SAF tree URI + permission check before destructive ops.
- `getAll()` returns `null` on unreadable vault → sync aborts **without** treating missing files as deletions.
- External edit detection: compare `VaultContentHash` to `IdMapping.extras`; divergent local+remote → conflict file on disk, local row kept until user resolves.
- Media retry: pending assets re-copied when file exists even if Markdown mtime unchanged.
- No GC of vault files except when app deletes a vault-backed note (same relative asset dir).
- Private `filesDir/media` not deleted automatically for vault migration (unchanged cleanup rules).

## File ownership

| File | Owner |
|------|--------|
| `data/sync/fs/vault/*.kt` | Path, document, attachments, hash |
| `data/sync/fs/StorageBackend.kt` | SAF I/O orchestration |
| `data/sync/core/SynchronizeNotes.kt` | Hash conflict branch for FILE_STORAGE |
| `data/sync/Converters.kt` | Vault import → `Note` |
| `data/repo/NoteRepositoryImpl.kt` | Scan failure guard, vault pending media |
| `ui/attachments/AttachmentUtils.kt` | `vaultrel:` URIs |
| `ui/sync/SyncSettingsFragment.kt` | Vault copy + rebuild index |
| `docs/folder-vault-sync.md` | This plan |

## Phased checklist

- [x] Design doc
- [x] `VaultPaths` + unit tests
- [x] `VaultNoteDocument` + front matter tests
- [x] `VaultContentHash` + conflict naming
- [x] `StorageBackend` vault scan/write/conflict
- [x] `SynchronizeNotes` FILE_STORAGE hash conflicts + legacy export cutoff
- [x] Import attachments from relative links
- [x] `vaultrel:` resolution for UI/Coil
- [x] Sync settings: vault behavior strings + rebuild index action
- [x] Push path materializes attachments into vault (`ProcessRemoteActions` patches local index)
- [ ] Notebook/tag front matter round-trip (names only in YAML today)
- [ ] Instrumented SAF provider test (provider-dependent)
- [ ] Manual: Syncthing + Obsidian checklist (below)

## Acceptance tests (automated)

See `VaultPathsTest`, `VaultNoteDocumentTest`, `VaultContentHashTest`, extensions to `SynchronizeNotesTest`.

## Manual integration checklist (device / desktop)

1. Select vault folder on internal storage Syncthing syncs.
2. Create nested note `Team/Standup.md` with camera photo; confirm file + `Team/Standup/*.jpg` visible in file manager.
3. Syncthing to PC; open vault in Obsidian; image renders.
4. Edit text on PC; pull-to-sync on phone; body updates; attachment still opens.
5. Edit same note on phone and PC without sync; next sync creates `Standup (conflict …).md` on disk.
6. Deny/revoke SAF permission; sync shows error; notes not deleted from Room.
7. Add image on PC before `.md` arrives; later sync imports image on retry.
8. Two folders `A/Untitled.md` and `B/Untitled.md` → two distinct mappings.

## Schema

No Room migration: reuse `cloud_ids.storageUri` for **relative path** (not document URI) when `provider == FILE_STORAGE`. Legacy FILE_STORAGE mappings are cleared when the vault folder changes (existing behavior); users re-select folder for fresh vault.
