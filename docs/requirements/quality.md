# Quality and non-functional facts (NFR)

The platform the app runs on, the permissions it asks for, what it does and does not put on the network or on disk in
readable form, and how it handles accessibility, languages and speed. These are facts about the app as it is built
(`app/build.gradle.kts`, `AndroidManifest.xml`, `app/src/main`), not targets: where a target is missing the requirement
says so and is `Proposed`. Principles are in the [constitution](../CONSTITUTION.md); behaviors owned by another area are
linked, not repeated. Owner findings: [2026-11-12 review](../audits/2026-11-12-undocumented-features-review.md).
Gherkin: [quality.feature](../features/quality.feature).

### NFR-01 Supported Android versions
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
The app installs on Android 7.0 (API 24) and later, is compiled against and targets API 37, and ships Java 17 bytecode
with core-library desugaring. Newer-API behavior is compiled in per version: notification channels on API 26+, the
platform image decoder on API 28+, dynamic colour and exact alarms on API 31+, the notification permission on API 33+,
typed foreground-service permissions on API 34+. Below those versions the older path is used; where there is none the
feature is hidden or falls back (the **Follow system** colour scheme is hidden below Android 12, and an exact alarm is
scheduled inexactly when the access is missing).

**Acceptance criteria**
- AC1: The app installs on API 24 and later and targets API 37.
- AC2: Notification channels are created only on API 26 and later, and the app works without them below that.
- AC3: **Follow system** is offered only on API 31 and later, and an exact reminder becomes an inexact alarm without
  the access.
- AC4: The notification permission is asked only on API 33 and later; the typed foreground-service permissions are
  declared for API 34 and later.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-01`

### NFR-02 Permissions asked at runtime
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Only the microphone and notifications are requested while the app runs. **Record audio** asks for `RECORD_AUDIO` before
its dialog is shown, and a refusal closes the dialog with nothing recorded. `POST_NOTIFICATIONS` is asked on Android 13
and later from the reminder dialog only, when notifications are off, and a refusal closes that dialog. Exact alarms have
no prompt of the app's own: on Android 12 and later a new reminder without the access opens the system's exact-alarm
screen first, and the alarm is still set, inexactly, if the user does not grant it. Playback asks for no permission at
all, so its notification is invisible when notifications were never granted.

**Acceptance criteria**
- AC1: The recorder asks for `RECORD_AUDIO` before it shows itself; a refusal closes it and adds no attachment.
- AC2: `POST_NOTIFICATIONS` is asked only on API 33+, only from the reminder dialog, and only when notifications are off;
  a refusal closes the dialog.
- AC3: On API 31+ without exact-alarm access, creating a reminder opens the system exact-alarm screen and the reminder
  is still scheduled with an inexact alarm.
- AC4: No other runtime permission is requested, and playback asks for none.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-02`

### NFR-03 Permissions declared at install
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
The manifest declares permissions that are granted at install and never prompted for: `INTERNET` and
`ACCESS_NETWORK_STATE` (the only network path is Coil rendering a note's own remote image link — INT-14; nothing reads
the connection state), `WAKE_LOCK` (playback holds a partial wake lock so audio continues with the screen off),
`RECEIVE_BOOT_COMPLETED` (a boot broadcast re-arms reminders), `FOREGROUND_SERVICE` with the `dataSync`, `microphone`
and `mediaPlayback` types for the backup, recorder and playback services, and `SCHEDULE_EXACT_ALARM` for exact
reminders. Apart from the two runtime permissions in NFR-02, no other permission is declared.

**Acceptance criteria**
- AC1: `INTERNET` is declared and used only for remote images in note previews, and `ACCESS_NETWORK_STATE` is declared
  but unread.
- AC2: `WAKE_LOCK` is declared and playback takes a partial wake lock.
- AC3: `RECEIVE_BOOT_COMPLETED` is declared and a reboot re-arms the user's reminders.
- AC4: `FOREGROUND_SERVICE` is declared with the `dataSync`, `microphone` and `mediaPlayback` types (typed permissions
  from API 34), and `SCHEDULE_EXACT_ALARM` is declared for exact reminders.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-03`

### NFR-04 No storage or camera permission
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
The app declares no storage permission (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `WRITE_EXTERNAL_STORAGE`) and no
`CAMERA`. Files reach it as content URIs from the system file picker, the photo picker, the system camera app or another
app's share; the notes folder is the storage-access tree the user picked; the app's own media copies are plain files it
reads by path. Nothing needs a broad view of the device's storage.

**Acceptance criteria**
- AC1: No storage permission is declared; picked, captured and shared files arrive as content URIs.
- AC2: No `CAMERA` permission is declared; **Take a photo** hands a FileProvider URI to the camera app.
- AC3: The notes folder is reached through the tree URI the user picked, not through a file-system path.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-04`

### NFR-05 What is and is not encrypted at rest
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Ordinary settings (theme, layout, sorting, editor defaults, formats, backup and retention) live in an unencrypted DataStore
preferences file that Android backup may upload. The secrets live in `EncryptedSharedPreferences` (`encrypted_prefs`,
AES-256-SIV keys and AES-256-GCM values, master key in the AndroidKeyStore): the notes-folder URI, when it was confirmed,
the cloud-folder warning marker, the vault generation fence, this install's history device id and last sequence, the
history switches and prune day, the notebook-name migration flag and the last What's-new id. That file is backup-excluded
(INT-13). Notes and attachments are plain files in the user's folder (FMT-01, FMT-05); the Room index is an unencrypted
database in private storage, rebuilt from that folder. The theme cache is a plain preferences file used before the first
frame (SET-02).

**Acceptance criteria**
- AC1: `encrypted_prefs` is encrypted with AES-256-SIV keys and AES-256-GCM values and is backup-excluded.
- AC2: It holds only the listed secrets: folder URI, vault confirmation time and fence, history device id and sequence,
  history switches and prune day, the notebook-name flag and the last What's-new id.
- AC3: Ordinary settings are kept in an unencrypted DataStore preferences file, and the theme cache is a plain
  preferences file.
- AC4: Note files, attachment copies and the Room index are not encrypted, and note text stays readable by other tools.
- AC5: An encrypted file that cannot be opened is recreated empty, so the secrets fall back to defaults rather than
  crashing the app.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-05`

### NFR-06 Note encryption targets are not defined
**Status:** Proposed · **Scope:** Core · **Tests:** none yet (@gap)
Notes, attachments and the index are readable by anything that can read the folder, the app's private storage or the
backup. Whether the app should encrypt more than its device-local settings, with which key, and what a restore should
then do, is undecided.

**Acceptance criteria**
- AC1: The owner sets a measurable target.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-06`

### NFR-07 Accessibility of controls as built
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
No layout or view in `app/src/main` sets a content description, so icon-only controls — both floating action buttons,
the drawer's sync-settings button, the recorder's microphone and the note card's status icons — have no spoken label;
actions in menus are labelled by their menu title. Touch-target sizes come from the Material widgets and row padding,
with no minimum declared in the layouts or `dimen.xml`. Every text size is in `sp` and follows the system font scale,
and the editor's own size setting (Default or 10–50) is applied in `sp` on top of it. The theme offers light, standard
dark and black dark, seven colour schemes and, on Android 12+, the system colours; there is no high-contrast option.

**Acceptance criteria**
- AC1: Icon-only controls carry no content description, while menu items carry their title.
- AC2: All text grows with the system font scale, and the editor size setting is added on top of it.
- AC3: The theme offers light, standard dark, black dark and seven colour schemes, plus system colours on Android 12+;
  no high-contrast theme exists.
- AC4: No minimum touch target is declared; sizes come from the Material widgets and row padding.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-07`

### NFR-08 Accessibility targets are not defined
**Status:** Proposed · **Scope:** Core · **Tests:** none yet (@gap)
Nothing in the build states a talkback, touch-target, contrast or font-scale goal, and no check enforces one. What the
app must reach, and which failures are defects, is undecided.

**Acceptance criteria**
- AC1: The owner sets a measurable target.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-08`

### NFR-09 Translations, the language list and text direction
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Translation folders exist for 21 languages (`values-ar`, `-ca`, `-cs`, `-de`, `-el`, `-es`, `-fa`, `-fr`, `-it`,
`-nb-rNO`, `-nl`, `-pl`, `-pt-rBR`, `-ro`, `-ru`, `-sv`, `-tr`, `-uk`, `-vi`, `-zh-rCN`, `-zh-rTW`) against 303 English
strings, and a missing string falls back to English. The build's `localeFilters` names 19 locales and
`locales_config.xml` offers 18: Arabic passes the filter but is not offered, and `values-fa` (30 strings), `values-ro`
and `values-sv` (37 strings) are in the tree but in neither list (INT-10, audit). Layouts use start/end margins and the
manifest sets `supportsRtl`, with one `paddingLeft` left in the Settings header. Dates and times follow the five date
and two time patterns chosen in Settings — month names are localized, field order is not — and the widget formats its
date as a fixed `dd MMM` in the default locale.

**Acceptance criteria**
- AC1: 21 translation folders exist; a missing string falls back to English.
- AC2: The build filter names 19 locales and the system list offers 18; Arabic passes the filter but is unlisted, and
  `fa`, `ro` and `sv` are in neither list.
- AC3: The manifest declares right-to-left support and the layouts use start/end positioning, except one `paddingLeft`
  in the Settings header.
- AC4: A note's date and time use the pattern chosen in Settings; month names are localized and the field order is fixed.
- AC5: The widget shows its date in a fixed `dd MMM` format in the device's default locale.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-09`

### NFR-10 Localization targets are not defined
**Status:** Proposed · **Scope:** Core · **Tests:** none yet (@gap)
No target says how complete a translation must be to ship, how far the system list must agree with the build filter, or
whether dates should follow the device's locale conventions. The owner decides.

**Acceptance criteria**
- AC1: The owner sets a measurable target.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-10`

### NFR-11 Folder scans reuse a cache
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultScanCacheTest.hitOnSameMtimeAndSize_whenOlderThanRacyWindow`,
`VaultScanCacheTest.missOnChangedMtimeOrSize`, `VaultScanCacheTest.savePrunesRemovedPaths`,
`VaultScanCacheTest.corruptFile_isIgnored`, `VaultScanCacheTest.clear_dropsEntriesAndFile_soNextScanRereads`
Each vault keeps a JSON cache in `filesDir/vault-scan-cache/` mapping a relative path to its modification time, size,
raw text and scan time. A file whose time and size are unchanged is taken from the cache instead of being read again;
unknown metadata or an entry too new to trust is re-read; paths that disappeared are pruned; and a missing or corrupt
cache only means a full read. The cache is written atomically and is excluded from Android backup (INT-13). A manual
rescan and a folder change clear it.

**Acceptance criteria**
- AC1: An unchanged path with known time and size is served from the cache without reading the file.
- AC2: A changed time or size, or unknown metadata, forces a re-read.
- AC3: Saving prunes paths that are gone, and an unchanged set is not rewritten.
- AC4: A corrupt or missing cache is ignored, and clearing it makes the next scan read everything again.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-11`

### NFR-12 Typing and change bursts are debounced
**Status:** Implemented · **Scope:** Core · **Tests:** `EditorViewModelSaveTest.typingBurst_persistsOnlyLastContentOnceAfterDebounce`,
`EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce`,
`EditorViewModelSaveTest.vaultPush_happensOnceAfterLongerIdleWindow`,
`EditorViewModelSaveTest.flush_persistsPendingContentImmediately`,
`VaultFolderChangeMonitorTest.rapidChanges_collapseToSingleSync`
Typing reaches the Room index 300 ms after the last keystroke and the notes folder 1500 ms after it, so a burst of edits
becomes one index write and one push; a newer edit cancels the waiting ones and a write that has already started is
allowed to finish. Leaving or pausing the editor flushes pending text at once. The folder monitor collapses a burst of
external file events into one sync after two seconds. Search waits 300 ms after the last query edit.

**Acceptance criteria**
- AC1: After a burst of edits one index write happens, 300 ms after the last one, and it carries the last text.
- AC2: One push to the notes folder happens, 1500 ms after the last edit.
- AC3: A flush writes the pending text immediately without waiting for either timer.
- AC4: A rapid burst of folder events produces a single sync.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-12`

### NFR-13 Note lists update by diff
**Status:** Implemented · **Scope:** Platform · **Tests:** `NoteRecyclerAdapterVaultPathTest.identical_map_triggers_no_item_change_notifications`,
`NoteRecyclerAdapterVaultPathTest.changed_path_notifies_only_the_affected_position`,
`NoteAdapterPayloadTest.testPayloadFlattening`
The note list is a `ListAdapter` with a `DiffUtil.ItemCallback`: an unchanged list — including a structurally equal map
of vault paths — notifies nothing, a changed path rebinds only its note, and the change travels as a payload
(title, content, colour, tags) instead of a full bind. The list is read whole from Room; the app has no paging library.

**Acceptance criteria**
- AC1: A structurally equal replacement of the list or of the vault-path map produces no change notification.
- AC2: A changed path notifies only the affected position.
- AC3: Changes arrive as payloads that are flattened into one update per item.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-13`

### NFR-14 Performance targets are not defined
**Status:** Proposed · **Scope:** Core · **Tests:** none yet (@gap)
The mechanisms above exist and nothing states how fast a start, a scan, a search or a scroll must be, or on which
device class. The owner sets the numbers before any of them can be called a defect.

**Acceptance criteria**
- AC1: The owner sets a measurable target.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-14`

### NFR-15 Data leaving the device
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultMarkwonImageLoaderTest.httpUrl_passesThrough`,
`TaskListMarkwonBindingsTest.remoteHttps_stillMarkdownWithoutVaultContext`
Nothing leaves the device unless the user's own content asks for it. An `http(s)` image link in a note is fetched by
Coil when the preview renders; any other link leaves only when the user opens it in another app. There is no HTTP
client, account, analytics or crash SDK in the app, and sync is the folder. Android Auto Backup may upload the Room
index, the app-private media copies and the other preferences except the four excluded paths (INT-13); note files sit in
the user's folder, outside app data. A crash report goes out only when the user asks for one. Article 5 and the
[review](../audits/2026-11-12-undocumented-features-review.md) flag the remote-image fetch against "works fully
offline".

**Acceptance criteria**
- AC1: An `http(s)` image link in a note is fetched when the preview renders; no other note content is uploaded.
- AC2: Sync, notes and attachments make no request of the app's own, and no account or analytics SDK is present.
- AC3: The backup scope is the one in INT-13: the index and private media are eligible, note files are not app data.
- AC4: A crash report leaves only on the user's action.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-15`

### NFR-16 Crash reports are sent only on request
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
ACRA is bundled and initialized at start, then disabled immediately, so a crash is never reported by itself. **Send
logs** in About enables it for one report and hands a JSON file to the user's mail app, addressed to the project's
support address. The report carries the app version, Android version, product, brand and model, build config, stack
trace, user comment, start and crash dates and up to 500 lines of logcat; the app attaches no note text. No analytics
or other telemetry is bundled, and an offline device sends nothing.

**Acceptance criteria**
- AC1: Reporting is disabled at start, so an ordinary crash sends nothing.
- AC2: **Send logs** enables reporting for one report and disables it again.
- AC3: The report is a JSON file handed to the mail app the user chose, addressed to the project's support address.
- AC4: It contains the app/device metadata, stack trace, user comment and up to 500 logcat lines, and no note text.
- AC5: No analytics or other telemetry is bundled.

**Gherkin:** [`quality.feature`](../features/quality.feature) — `@NFR-16`

### NFR-17 Crash-safe writes are WRS
**Status:** Implemented · **Scope:** Core
Writes to the notes folder are staged, journaled and verified, and an interrupted one is recovered or quarantined: see
[WRS-01](write-safety.md) and [WRS-02](write-safety.md). This requirement adds nothing of its own.

**Gherkin:** none of its own; covered by `@WRS-01` and `@WRS-02` in
[`write-safety.feature`](../features/write-safety.feature).
