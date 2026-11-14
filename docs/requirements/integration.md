# Integration with Android (INT)

How the app meets the rest of the operating system: incoming shares, launcher entries, the home-screen widget, About
and What's new, the language list, the FileProvider, notifications, Android backup and the network policy. Use case:
UC-03 (a shared text becomes a new note). Gherkin: [integration.feature](../features/integration.feature).

### INT-01 Shared text becomes a new note
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`MainActivity` is a share target for `text/plain` (`SEND`), labelled "Take a note". `EXTRA_TITLE` becomes the new
note's title and `EXTRA_TEXT` its body, and the editor opens on a new note. A share arriving while the app runs is
delivered to the running activity (`onNewIntent`) and starts another new note, not a second task. The activity reads a
share intent only when it is created fresh, so recreating it (for example after a theme change) does not import the
same share twice.

**Acceptance criteria**
- AC1: A `text/plain` share is offered in the system share sheet as "Take a note".
- AC2: The shared text becomes the body of a new note and `EXTRA_TITLE` its title.
- AC3: A share received while the app is open opens a new note in the same task.
- AC4: Recreating the activity after a share creates no second note.

**Screens:** SCR-16
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-01`

### INT-02 Shared media is attached to the new note
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`SEND` and `SEND_MULTIPLE` accept `image/*`, `video/*` and `audio/*`. The source URI comes from `EXTRA_STREAM` or,
when absent, from the first `ClipData` item (all items for `SEND_MULTIPLE`). Each URI is copied into the app's private
media storage before it is attached, so the new note owns a copy and does not depend on the sending app's permission.
A URI that cannot be copied is skipped and the rest of the note still opens. What the app later does with image,
video and audio content is the media area's (MED) concern; this requirement covers only what a share adds.

**Acceptance criteria**
- AC1: A single shared image, video or audio file is copied to app-private storage and attached to the new note.
- AC2: `SEND_MULTIPLE` attaches every listed media file that copies successfully.
- AC3: Without `EXTRA_STREAM`, the share's first `ClipData` URI is used (`SEND_MULTIPLE`: all of them).
- AC4: A file that cannot be copied is skipped; the editor still opens with the other attachments.

**Screens:** SCR-16
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-02`

### INT-03 Every launch intent lands in the right place
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
There is one exported screen, `MainActivity`, launched `singleTask`; every other entry point is a manifest alias, an
explicit intent or a deep link. An intent is read once per launch: `SEND`/`SEND_MULTIPLE` open the editor for a new
note, `org.qosp.notes.NEW_NOTE` opens a blank editor, an intent with a positive `noteId` extra opens that note, and
anything else is handed to the navigation graph. The graph's only deep link is
`allnotes://notes/{transitionName}?newNoteIsList={isList}`, and the app declares no browsable filter, so a web page
cannot open the app; a launch must name `MainActivity` explicitly (a shortcut, the widget, a reminder notification or
another app).

**Acceptance criteria**
- AC1: A launcher tap with no extras opens the notes list.
- AC2: An intent carrying a positive `noteId` opens that note in the editor.
- AC3: `org.qosp.notes.NEW_NOTE` opens a blank editor.
- AC4: The app registers no browsable web entry, so a link cannot start it implicitly.
- AC5: A second launch reuses the running task instead of stacking another activity.

**Screens:** SCR-16
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-03`

### INT-04 Launcher shortcuts start a note or a task list
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`res/xml-v25/shortcuts.xml` defines two shortcuts, `takeNote` and `makeList` ("Take a note", "Make a list"), each an
explicit `VIEW` intent to `org.qosp.notes.MainActivity` with
`allnotes://notes/editor_create?newNoteIsList=false|true`. The editor's `newNoteIsList` argument decides whether the
new note starts as a task list. The launcher entry is the manifest `activity-alias` `.ui.launcher.LauncherActivity`
(`MAIN`/`LAUNCHER`, with no class of its own) that targets `MainActivity` and carries the shortcuts metadata, so
pinned icons from older versions still work.

**Acceptance criteria**
- AC1: On API 25 and later the launcher icon offers "Take a note" and "Make a list"; the shortcuts file exists for API 25+.
- AC2: "Take a note" opens a blank note and "Make a list" opens a blank task list.
- AC3: The `LauncherActivity` alias resolves to `MainActivity` and can start the app.
- AC4: A shortcut tap starts the note in the existing task.

**Screens:** SCR-16
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-04`

### INT-05 The widget lists pinned notes
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`NotesWidgetProvider` (an exported receiver) serves `widget_notes_list` through `NotesWidgetService` and
`NotesWidgetViewsFactory`. It queries non-archived, non-deleted notes, keeps the pinned ones, sorts by modification
time newest first and shows at most 20. A row shows the title, or the first line of the body's first 50 characters
when the title is blank, a one-line preview of up to 100 characters ("done / total tasks" for a task note) and the
modification date as `dd MMM` in the device locale. The widget is home-screen only, resizable, at least 250×180 dp,
and described as "Show your recent notes on home screen".

**Acceptance criteria**
- AC1: Rows are pinned, non-archived, non-deleted notes; hidden pinned notes are listed too.
- AC2: At most 20 rows are shown, most recently modified first.
- AC3: A blank title is replaced with the body's first line (at most 50 characters), and a task note shows
  "done / total tasks" instead of the body preview.
- AC4: A row shows the modification date as `dd MMM`; the widget is resizable and home-screen only.

**Screens:** SCR-14
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-05`

### INT-06 Widget taps and the empty state
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Tapping a row opens that note in the editor: the row carries its `noteId` to `MainActivity`. With no pinned notes the
widget shows "No pinned notes yet. Pin a note to see it here." instead of a list, and tapping that message opens the
app's main screen. The widget has no control that creates a note and posts no notifications.

**Acceptance criteria**
- AC1: Tapping a note row opens that note.
- AC2: With no pinned notes, the empty-state message is shown instead of rows.
- AC3: Tapping the empty state opens the app.
- AC4: The widget contains no new-note control.

**Screens:** SCR-14
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-06`

### INT-07 The app and the system refresh the widget
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Every update re-runs the query and calls `notifyAppWidgetViewDataChanged`. The provider is updated by the system when
the widget is placed and every 30 minutes (`updatePeriodMillis="1800000"`, the shortest interval Android allows), by
`MainActivity.onCreate` on every app start, and by `WidgetUpdateHelper` after a note is pinned or unpinned in the list
or the editor. Editing a pinned note's text, binning it or hiding it does not refresh the widget by itself; the old
row stays until one of the updates above runs.

**Acceptance criteria**
- AC1: Adding the widget fills it without opening the app.
- AC2: The app refreshes every widget instance on start and after a pin or unpin.
- AC3: Each refresh re-reads the notes and tells the list that its data changed.
- AC4: An edit, bin or hide of a pinned note leaves the widget stale until the next refresh.

**Screens:** SCR-14
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-07`

### INT-08 About shows the version, the links and the log report
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The drawer's About destination shows a version row with `BuildConfig.VERSION_NAME` (no version code) and rows for
Website, Developer, Contribute, Support, Libraries and Send Error Logs. The four links open in another app via
`ACTION_VIEW`. Libraries opens a dialog with the third-party licence text. Send Error Logs enables ACRA for exactly
that one report, collects a JSON report (build, device and up to 500 app log lines from this process) and hands it to a
mail app addressed to the developer address; ACRA is disabled otherwise, so nothing is reported on its own.

**Acceptance criteria**
- AC1: About shows the app's version name.
- AC2: Website, Developer, Contribute and Support each open their fixed page in another app.
- AC3: Libraries shows the third-party licences in a dialog.
- AC4: Send Error Logs composes an email with a JSON report; no report is sent without that action.

**Screens:** DLG-20, SCR-11
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-08`

### INT-09 What's new appears once per catalog update
**Status:** Implemented · **Scope:** Core · **Tests:** `WhatsNewTest.updateToShow_returnsLatest_whenItIsNewerThanLastShown`, `WhatsNewTest.updateToShow_returnsNull_whenLatestWasAlreadyShown`, `WhatsNewTest.updateToShow_returnsLatest_whenNothingWasShownYet`, `WhatsNewViewModelTest`
The dialog is driven by `assets/whatsnew.yaml`, which holds an `id`, a `title` and bullet items per update.
`MainActivity` shows the newest update when its id is greater than the stored `LAST_SHOWN_WHATS_NEW_ID`; a first run
is treated as id 1, so a fresh install also sees the newest entry. The id is stored when the dialog is shown, not when
it is dismissed, so a rotation or a later start does not show it again. Builds with `TESTLAB_BUILD=true` never show
it. The dialog itself carries no app version number.

**Acceptance criteria**
- AC1: The dialog shows the newest catalog title and its bullets when its id is newer than the stored one.
- AC2: After it is shown, no start or recreation shows it again for that id.
- AC3: An absent or unreadable stored id is treated as a first run.
- AC4: A `TESTLAB_BUILD` build never shows the dialog.

**Screens:** DLG-19
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-09`

### INT-10 Translations are offered through Android's app-language settings
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`res/xml/locales_config.xml` lists 18 locales — en, ca, cs, de, el, es, fr, it, nb-NO, nl, pl, pt-BR, ru, tr, uk, vi,
zh-CN, zh-TW — and the manifest points Android at it. The app has no language picker of its own; on Android 13 and
later the list appears in the system's per-app language settings. Translations that exist only as resources (ar, fa,
ro) are used when the system language matches, but they are not in the list the app offers.

**Acceptance criteria**
- AC1: Android 13+ offers exactly those 18 languages for the app.
- AC2: The app itself has no language picker.
- AC3: Choosing another app language changes the app's UI strings.
- AC4: ar, fa and ro are used with a matching system language but are not offered in the app's list.

**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-10`

### INT-11 The FileProvider hands out app-private media
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`androidx.core.content.FileProvider` is declared with authority `${applicationId}.provider`
(`io.github.allnotes.provider`), not exported, with URI grants allowed. `file_provider_paths.xml` exposes only
`files/media/` and `files/test_media/`. The provider is how the app shares an attachment and how it gives the camera
and the recorder a place to write; those grants are temporary read (read and write for the camera). Notes and other
files (database, preferences, vault journals, vault copies) are never handed out through it, and a vault-stored
attachment is shared with its own content URI instead.

**Acceptance criteria**
- AC1: An attachment in private media storage is shared as a `content://` URI with a temporary read grant.
- AC2: A path outside `media/` and `test_media/` is refused by the provider.
- AC3: Another app can reach a file only through a granted URI; the provider is not exported.
- AC4: The camera and the recorder write through URIs issued by this provider.

**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-11`

### INT-12 Notification channels and foreground services are declared up front
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
`App.createNotificationChannels` creates three channels on API 26 and later, before the workers are enqueued:
Reminders (high importance), Backups (default) and Media playback (default). Reminders post to Reminders, backup and
restore progress and results to Backups from the `dataSync` foreground service, and playback controls to Media
playback from the `mediaPlayback` foreground service. The recorder is a bound `microphone` service and never enters
the foreground or notifies. No other channel is created; the notification permission is requested only when a
reminder needs it.

**Acceptance criteria**
- AC1: On API 26+ the three channels exist from the first app start.
- AC2: Reminder, backup and playback notifications each use their own channel.
- AC3: Backup and playback run as foreground services with a notification; the recorder does not.
- AC4: No fourth channel is created, and notification permission is asked only from the reminder flow.

**Screens:** SCR-15
**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-12`

### INT-13 Auto Backup keeps private caches and unrestorable keys out
**Status:** Implemented · **Scope:** Platform · **Tests:** `BackupRulesTest.fullBackupContent_excludesPrivateData`, `BackupRulesTest.dataExtractionRules_excludePrivateDataFromBothTransfers`, `BackupRulesTest.manifestReferencesBothRules`
Auto Backup is on. API 30 and lower read `backup_rules.xml`; API 31 and later read `data_extraction_rules.xml`,
which excludes the same four paths from both cloud backup and device transfer: `encrypted_prefs.xml` (whose key is
not restored), `vault-scan-cache/`, `vault-recovery/` and `vault-history/`. Everything else in app-private storage
stays eligible, including the Room database and the private media copies; note files in the user's folder are outside
the app's data and are not part of Android backup.

**Acceptance criteria**
- AC1: `encrypted_prefs.xml` is excluded from cloud backup and device transfer.
- AC2: `vault-scan-cache/`, `vault-recovery/` and `vault-history/` are excluded from both.
- AC3: The manifest references both rule files.
- AC4: The database and private media are not excluded.

**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-13`

### INT-14 The app has no network code of its own
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultMarkwonImageLoaderTest.httpUrl_passesThrough`, `TaskListMarkwonBindingsTest.remoteHttps_stillMarkdownWithoutVaultContext`
The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`, but nothing uses them for notes or sync: the app syncs
through the folder over SAF or the file system, and there is no HTTP client anywhere under `app/src/main/java`, no
account and no analytics SDK. The network-security config only declares system and user trust anchors; no cleartext
exception is configured. The one network path is a note's own Markdown: an `http(s)://` image link is passed to Coil
and downloaded when the preview renders, while an ordinary link opens in another app. ACRA reports only on the user's
action in About.

**Acceptance criteria**
- AC1: Notes, attachments and sync never make an HTTP request of the app's own.
- AC2: An `http(s)` image link in a note is fetched and shown when the preview renders.
- AC3: A link in a note opens in another app; the app has no in-app browser.
- AC4: The network-security config trusts the system and user certificate stores and enables no cleartext traffic.

**Gherkin:** [`integration.feature`](../features/integration.feature) — `@INT-14`
