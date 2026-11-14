# Platform boundary

The requirements describe behavior. Most of it is **Core**: it must behave identically on every implementation that
opens the same notes folder. Some of it is **Platform**: the intent is the same everywhere, but the mechanism belongs
to the operating system. Every requirement carries `**Scope:** Core` or `**Scope:** Platform` on its status line.

This page lists, for each platform capability, what the app needs from it (the contract), how Android provides it,
and the requirements that depend on it. A port to another platform implements the same contract with its own
mechanism and keeps every Core requirement unchanged. The folder contract itself is in [../spec/](../spec/).

## Capability contract

| Capability | What the app needs | Android today | Requirements |
|---|---|---|---|
| Folder access | A user-chosen folder, kept across restarts without asking again; list, read, write, rename, delete files and folders; file modification times in milliseconds. A listing must be able to report failure distinctly from "empty". | Storage Access Framework tree URI with a persisted permission; `DocumentFile`/`DocumentsContract` (`SafVaultFs`). | FLD-01…FLD-11, SYN-01, SYN-05 |
| Crash-safe writes | Stage, verify, publish, verify; a durable journal that survives process death. | App-private `filesDir/vault-recovery/`, journal file (`VaultRecoverableWrite`). | WRS-01…WRS-05 |
| Change notification | Best-effort signal that the folder changed. Never trusted as complete. | `ContentObserver` on the tree URI (`VaultFolderChangeMonitor`); often silent for Syncthing writes. | BGD-04, SYN-02 |
| Foreground resume | Run a sync when the app comes back, throttled. | `MainActivity.onResume` with a 2-minute throttle (`VaultForegroundRescan`). | BGD-05 |
| Periodic background work | A sync about hourly and a bin purge about every five hours, without network. | WorkManager periodic jobs (`SyncWorker`, `BinCleaningWorker`). | BGD-01…BGD-03, BGD-06, DEL-08 |
| Scheduled local notifications | Fire a notification at an exact time; survive reboot; re-arm after sync. | `AlarmManager` exact/inexact alarms, `BOOT_COMPLETED` receiver, `SCHEDULE_EXACT_ALARM` permission. | REM-09…REM-14, BGD-07, SYN-08 |
| Notifications | Channels for reminders, playback and backups; a runtime permission where the OS needs one. | Notification channels; `POST_NOTIFICATIONS` on Android 13+. | REM-11, REM-14, MED-08, BAK-05, INT-12 |
| Long-running user tasks | Backup/restore and audio playback continue with the app in the background. | Foreground services (`BackupService`, `MusicService`); recorder as a started + bound service. | BAK-05, MED-03, MED-08, BGD-09 |
| Camera, microphone, file picker | Take a photo, record audio (AAC in MP4), pick files of any listed type. | Activity Result contracts; `RECORD_AUDIO` permission; no storage or camera permission. | MED-01…MED-03, NFR-02, NFR-04 |
| Private media | Keep attachment copies that are not yet in the folder; share them with other apps temporarily. | `filesDir/media/`, `FileProvider` with URI grants. | MED-12, INT-11 |
| Share-in | Receive text and media from other apps and open a new note. | `SEND` / `SEND_MULTIPLE` intent filters on `MainActivity`. | INT-01, INT-02, MED-11 |
| Quick actions | "Take a note" and "Make a list" from the launcher. | Static shortcuts (`shortcuts.xml`) and a deep link. | INT-03, INT-04 |
| Home-screen widget | List pinned notes; open one. | App widget (`NotesWidgetProvider`, `RemoteViewsService`). | INT-05…INT-07 |
| App language | Per-app language selection. | `locales_config.xml`, AppCompat per-app locales. | INT-10, NFR-09 |
| Secure local storage | Keep the folder location and per-device ids out of cloud backups. | `EncryptedSharedPreferences`; Auto Backup exclusions. | NFR-05, INT-13, SYN-09 |
| Theme | Light/dark/system theme, dynamic colour where the OS offers it. | AppCompat night modes; Material You on Android 12+. | SET-02, SET-03 |
| Crash reports on request | Send a one-off report only when the user asks. | ACRA, disabled except for "Send Error Logs". | INT-08, NFR-16 |

## Rules a port must keep (from the constitution)
1. **Uncertainty means keep.** Any platform condition that hides files — a provider that lists lazily, files that
   are still downloading, cloud placeholders standing in for evicted files, a revoked permission — must make the scan
   *fail* or count those files as *present*, never as missing ([algorithms §3](../spec/algorithms.md#3-scan-guards-uncertainty-means-keep)).
   For example, a cloud drive that replaces an evicted `Note.md` with a hidden placeholder file must not make the
   note look deleted.
2. **Same bytes.** Front matter, ids, file names and history lines follow [vault-format.md](../spec/vault-format.md)
   exactly; run the [fixtures](../spec/fixtures/README.md) as a conformance suite.
3. **Never act on an unconfirmed folder,** and never apply queued work to a different folder (Article 2.6).
4. **No note text leaves the device** except through the sync tool the user chose and actions the user starts
   (share, export, send logs) (Article 5).
5. **Device-local stays device-local.** Settings, mapping state, history device ids and the outbox are not synced
   ([data-model.md](../spec/data-model.md)).

## Platform-specific behavior recorded as Core
Some requirements name Android artefacts but are Core because they concern the **shared folder**, which every
implementation sees: Android's `.trashed-<epoch>-<name>` files (DEL-06, FMT-07) and Syncthing's `*.sync-conflict-*`
files (CON-04) must be ignored on every platform.
