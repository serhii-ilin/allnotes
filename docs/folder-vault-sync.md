# Folder vault sync (path-backed source of truth)

**Branch:** `cursor/folder-vault-sync-04d8`  
**PR:** https://github.com/serhii-ilin/quillpad/pull/1

## Product model

- **Folder vault is the only sync mode.** Nextcloud/DAV sync has been removed.
- Default cloud preference is **folder storage** (`CloudService.FILE_STORAGE`). Legacy `DISABLED` / `NEXTCLOUD` values migrate to folder mode on read.
- Until the user picks a SAF folder, the app works **fully offline in Room** and prompts to choose a folder (onboarding dialog + drawer hint).
- When a folder is first configured, **existing local notes** (including former local-only notes without mappings) are **exported once** into the vault. Export is idempotent (mapping-based); Room rows are kept until vault writes verify successfully.
- **INTERNET** remains for non-sync features (e.g. ACRA error mail, Coil loading `https://` images in Markdown). Sync itself is local/SAF-only and does not require network.

## Goals

- Selected SAF tree is the **source of truth** for note bodies, attachment bytes, and portable metadata.
- **Room** is a rebuildable index/cache for vault-backed notes.
- **Identity** = normalized relative path from vault root (e.g. `Work/Meeting.md`).

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

## Related tests

`CloudServiceMigrationTest`, vault orchestration/sync tests, `SynchronizeNotesTest` (export when cutoff set).
