# Folder vault sync (path-backed source of truth)

**Branch:** `cursor/folder-vault-sync-04d8`  
**PR:** https://github.com/serhii-ilin/quillpad/pull/1  
**Status:** Phase 2 (review fixes) implemented — device manual validation still required.

## Goals

- Selected SAF tree is the **source of truth** for note bodies, attachment bytes, and portable metadata.
- **Room** is a rebuildable index/cache for vault-backed notes only.
- **Identity** = normalized relative path from vault root (e.g. `Work/Meeting.md`). No UUIDs in files or IDs.
- **Fresh vault only:** enabling/changing the vault folder does not import or export legacy private notes; existing `isLocalOnly` / unmapped notes stay untouched.
- **Nextcloud** path unchanged (`ISyncBackend` + `SynchronizeNotes` mapping/title logic).

## Non-goals (this phase)

- Heuristic identity across external renames (old path vanishes, new path = new note).
- Obsidian wikilinks / `[[wiki]]` syntax.
- Migrating legacy private media into the vault or deleting private `filesDir/media`.

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
| `VaultLinkResolver` | Markdown link resolution vs **note parent directory** (standard relative paths) |
| `VaultNoteDocument` | YAML front matter + body; preserve unknown YAML keys; tag/notebook export |
| `VaultAttachments` | Relative links, asset subfolder `{basename}/`, copy in/out |
| `VaultContentHash` | SHA-256 baseline in `IdMapping.extras` for conflict + dirty detection |
| `VaultSyncImport` | `isLocallyDirty`, attachment-only refresh paths |
| `VaultTreeAccess` | Cached SAF tree for `vaultrel:` and Markwon/Coil (invalidate on folder change) |
| `StorageBackend` | Recursive `.md` scan, staged writes, conflict copies, notebook name on export |
| `SynchronizeNotes` | Path mapping; hash-aware pull; no delete if `getAll()==null` |

### Vault layout

```text
<vault root>/
  Work/Meeting.md
  Work/Meeting/camera_1.jpg    # assets beside note path
  Work/photo.jpg               # valid target from Work/Meeting.md via `photo.jpg`
  .obsidian/                   # ignored
  .stfolder/                   # ignored
  .quillpad-staging/           # ignored; in-progress writes
```

Markdown relative links resolve from the **directory containing the note file** (not an asset-directory-first guess). Example: `Work/Meeting.md` + `photo.jpg` → `Work/photo.jpg`; `Meeting/photo.jpg` → `Work/Meeting/photo.jpg`.

### Portable metadata (YAML front matter)

Known fields: `title`, `pinned`, `archived`, `hidden`, `color`, `tags`, `notebook`, `isList`, `created`, `modified`, `markdown`.  
Unknown / nested keys (e.g. Obsidian `aliases`) are **preserved** in `preservedYaml` and merged back on export.

| Semantics | Behavior |
|-----------|----------|
| `tags: []` | Clears all tags in Room on import |
| `tags` absent | Room tags unchanged |
| `notebook: "Name"` | Assigns/creates notebook |
| `notebook: ""` | Clears notebook assignment |
| `notebook` absent | Fallback: parent folder name if path has a parent |
| `isList: false` | Keeps body as Markdown; does not auto-enable task UI |
| `pinned: false` | Exported explicitly; hash includes metadata |

Unsupported round-trip: Quillpad reminders, `screenAlwaysOn`, compact preview, widgets.

### Dirty local edits vs attachment refresh (`refreshVaultAttachmentIndex`)

When `VaultSyncImport.isLocallyDirty` (local hash ≠ `IdMapping.extras`):

- **Do not** overwrite body or portable metadata from disk.
- **May** update attachment list from remote body links only.
- **Do not** update hash baseline in mapping until push reconciles.

When clean:

- Full body/metadata import + tag reconciliation + notebook rules above.
- Update `IdMapping.extras` from remote hash.

### Inline images & attachments (UI)

- `VaultMarkwonContext(noteVaultRelativePath)` stored on the target `TextView` tag (not ThreadLocal).
- `CoilImagesPlugin` resolves relative `![](...)` via `VaultLinkResolver` + `VaultTreeAccess`.
- `vaultrel:` paths in attachment rows use `VaultTreeAccess` (no `runBlocking` on main thread in `AttachmentUtils`).
- `VaultTreeAccess.invalidate()` when vault folder URI changes in sync settings.

### Attachment storage in Room

Vault-backed attachments use `path` prefix `vaultrel:` + normalized relative path. After successful vault materialization on push, UI should not depend on legacy private paths for vault-backed notes.

## Safety

- Persisted SAF tree URI + permission check before destructive ops.
- `getAll()` returns `null` on unreadable vault → sync aborts **without** treating missing files as deletions.
- External edit detection: compare `VaultContentHash` to `IdMapping.extras`; divergent local+remote → conflict file on disk.
- No GC of vault files except when app deletes a vault-backed note.

## Phased checklist

- [x] Design doc
- [x] Core vault stack + tests (phase 1)
- [x] Dirty-gated `refreshVaultAttachmentIndex`
- [x] Tag set reconciliation (including removals / `tags: []`)
- [x] Notebook export assigned name; explicit clear; parent-folder fallback
- [x] Unknown YAML preservation + merge on write
- [x] `VaultLinkResolver` + Markwon/Coil note-scoped context
- [x] `VaultTreeAccess` + attachment URI resolution
- [x] Task-list row Markwon (`TaskViewHolder`) uses parent-note `VaultMarkwonContext` (list preview + checked rows)
- [ ] Instrumented SAF provider test (environment-dependent)
- [ ] Manual: Syncthing + Obsidian checklist (below)

## Acceptance tests (automated)

`VaultPathsTest`, `VaultLinkResolverTest`, `VaultNoteDocumentTest`, `VaultContentHashTest`, `VaultSyncImportTest`, `VaultAttachmentsTest`, `TaskListMarkwonBindingsTest`, extensions to `SynchronizeNotesTest`.

Commands (agent env):

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

**Not proven by unit tests:** SAF permission UX, Coil loading on device, Syncthing/Obsidian interoperability.

## Manual integration checklist (device / desktop)

1. Select vault folder on internal storage Syncthing syncs.
2. Create nested note `Team/Standup.md` with `![](photo.jpg)` where `Team/photo.jpg` exists; preview renders in list + editor.
2b. Task-list note `Work/Repairs.md` with task text `Fix shelf ![](photos/shelf.jpg)` (`Work/photos/shelf.jpg` on disk): image renders in note-card task preview and in editor when task is checked / in preview mode (not while plain-text edit field is active).
3. Edit body offline; pull sync with unchanged remote — **local edits must remain**.
4. Add attachment on PC only; sync — attachment appears without overwriting dirty local text.
5. Syncthing to PC; Obsidian opens image via relative link.
6. Edit same note on phone and PC without sync; next sync creates conflict copy on disk.
7. Revoke SAF permission; sync errors; Room rows not mass-deleted.
8. Front matter with `aliases:` / nested YAML survives edit in app and re-export.
9. Remove tag in YAML on PC; sync removes tag in app.
10. Root-level `Note.md` with assigned notebook exports `notebook:` correctly.

## Schema

No Room migration: reuse `cloud_ids.storageUri` for **relative path** when `provider == FILE_STORAGE`. Legacy FILE_STORAGE mappings cleared when vault folder changes (existing behavior).
