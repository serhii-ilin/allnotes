# Folder vault sync (path-backed source of truth)

**Branch:** `cursor/folder-vault-sync-04d8`  
**PR:** https://github.com/serhii-ilin/allnotes/pull/1

## Product model

- **Folder vault is the only sync mode.** Nextcloud/DAV sync has been removed.
- Default cloud preference is **folder storage** (`CloudService.FILE_STORAGE`). Legacy `DISABLED` / `NEXTCLOUD` values migrate to folder mode on read.
- Until the user picks a SAF folder, the app works **fully offline in Room** and prompts to choose a folder (onboarding dialog + drawer hint).
- When a folder is first configured, **existing local notes** are **exported once** into the vault (no separate “local-only / not syncable” mode). Export is idempotent (mapping-based); Room rows are kept until vault writes verify successfully.
- **INTERNET** remains for non-sync features (e.g. ACRA error mail, Coil loading `https://` images in Markdown). Sync itself is local/SAF-only and does not require network.

## Goals

- Selected SAF tree is the **source of truth** for note bodies, attachment bytes, and portable metadata.
- **Room** is a rebuildable index/cache for vault-backed notes.
- **Identity** = the `id` in the file's front matter, with the normalized relative path from the vault root
  (e.g. `Work/Meeting.md`) as the first match. A rename or move keeps the note. See
  [requirements/identity.md](requirements/identity.md).

Product requirements, use cases and scenarios live in [docs/README.md](README.md); this file covers implementation.

## Background sync & change detection

- `SyncWorker` runs hourly **without** `NetworkType.CONNECTED`.
- `VaultFolderChangeMonitor` registers a `ContentObserver` on the vault tree URI when supported; on change it triggers `syncNotes()`. **SAF tree observers often do not fire on direct file writes** (e.g. Syncthing copying files), so this is best-effort only.
- **Foreground fallback:** throttled `syncNotes()` on `MainActivity.onResume()` and while note list fragments are visible (`VaultForegroundRescan`, default interval 2 minutes). Manual pull-to-refresh is always available.

## Vault layout

```text
<vault root>/
  Work/Meeting.md
  Work/Meeting/camera_1.jpg
  .obsidian/                   # dot-prefixed dirs ignored at any depth
  .trash/
  Note.sync-conflict-20240101.md  # Syncthing conflict files: not indexed, left on disk
```

Staging/recovery journals live in **app-private storage** (not in the synced tree); see Phase 1 recovery work.

## Implementation notes

- All vault tree I/O goes through the [VaultFs](app/src/main/java/org/qosp/notes/data/sync/fs/vault/VaultFs.kt) interface: production uses [SafVaultFs](app/src/main/java/org/qosp/notes/data/sync/fs/vault/SafVaultFs.kt); unit tests use [InMemoryVaultFs](app/src/main/java/org/qosp/notes/data/sync/fs/vault/InMemoryVaultFs.kt). `StorageBackend` has no parallel SAF vs in-memory code paths.
- Integration scenarios for sync blockers use real [NoteRepositoryImpl](app/src/main/java/org/qosp/notes/data/repo/NoteRepositoryImpl.kt) (`syncNotes`, bin/restore/delete, debounced `ProcessRemoteActions`) via [NoteRepositoryVaultHarness](app/src/test/java/org/qosp/notes/data/repo/NoteRepositoryVaultHarness.kt).
- Obsidian-style YAML: scalar `tags`, block lists, ISO `created`/`modified`, and foreign keys round-trip through `VaultNoteDocument` (`knownKeysVerbatim` + `preservedYaml`).

## Related tests

`VaultFolderBlockerScenariosTest`, `NoteRepositoryVaultHarnessSmokeTest`, `PreferenceRepositoryLegacyKeysTest`, vault orchestration/sync tests, `SynchronizeNotesTest`, `VaultNoteIdScenariosTest`.
