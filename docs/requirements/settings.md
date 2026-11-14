# Settings (SET)

What the Settings screen offers, what each value changes and what its default is. Settings belong to the install, not
to the notes folder. The screen is grouped **Notes folder** · **General** · **View** (Notebook, Note content, Tag list,
Navigation drawer) · **Other** · **Backup**. A preference row opens a single-choice dialog with the current value
preselected and stores the tapped option at once; the action rows hand off to the requirement that owns them (SET-14).
Use cases: UC-03, UC-07, UC-13, UC-14, UC-15, UC-16. Gherkin: [settings.feature](../features/settings.feature).

### SET-01 Settings belong to the device and start from defaults
**Status:** Implemented · **Scope:** Core · **Tests:** `PreferenceRepositoryLegacyKeysTest.legacySyncKeys_doNotBreakDefaults`, `ThemeCacheTest.writeThenRead_survivesNewInstance`
Every value is stored in app-private storage: the enum choices in the DataStore file `preferences`, and string values
such as the notes-folder URI in EncryptedSharedPreferences. Nothing is written into the notes folder, and a AllNotes
zip backup contains no preferences (BAK-01), so settings never travel with the notes. A value that was never stored, or
one whose stored name this version does not know, reads as its default. Android's own Auto Backup is a separate
channel: it leaves out `encrypted_prefs` but not the DataStore file, so an OS restore can bring settings back without
the folder location. The theme is also cached in `theme_cache` so it is applied before the first frame.

**Acceptance criteria**
- AC1: No Settings value is written into the notes folder or into a AllNotes zip backup.
- AC2: An install with nothing stored, or with stored keys this version does not know, shows the defaults.
- AC3: A setting changed on one device never changes on another device that shares the same notes folder.
- AC4: The chosen theme is used again on the next cold start, from the first frame.

**Screens:** SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-01`

### SET-02 Theme mode and dark theme
**Status:** Implemented · **Scope:** Core · **Tests:** `ThemeCacheTest`, `ThemeRecreationTest`
**Theme mode** is Follow system (default), Dark or Light; Follow system uses the device's night setting and switches
with it, while Dark and Light ignore it. **Dark theme** is Standard (default) or Black and applies only while the
resolved theme is dark. A change is applied at once: the current screen is recreated instead of asking for a restart,
and AppCompat's own night-mode recreation is not repeated. The theme cache keeps the same choice for the next start.

**Acceptance criteria**
- AC1: Theme mode offers Follow system (default), Dark and Light; Follow system follows the device.
- AC2: Dark theme offers Standard (default) and Black; Black applies only while the resolved theme is dark.
- AC3: A change shows the new theme without restarting the app, with one recreation, not two.
- AC4: After a restart the same theme is used from the first frame.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-02`

### SET-03 Colour scheme
**Status:** Implemented · **Scope:** Core · **Tests:** `ThemeCacheTest`, `ThemeRecreationTest`
**Color scheme** is Blue (default), Red, Yellow, Green, Pink, Orange, Purple or Follow system. The choice colours the
whole app; changing it recreates the current screen so the new colours show at once. Follow system uses the system's
accent colours and is offered only on Android 12 and newer; on older versions it is hidden from the list.

**Acceptance criteria**
- AC1: Color scheme offers Blue (default), Red, Yellow, Green, Pink, Orange and Purple.
- AC2: Follow system is offered on Android 12 and newer and hidden below that.
- AC3: A change is visible without restarting the app, with one recreation.
- AC4: The chosen scheme is used again after a restart.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-03`

### SET-04 Layout mode
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Layout mode** is Grid (default) or List for the note lists: All notes, a notebook, Archive, Bin and Search. Grid lays
notes out in two staggered columns, List in one. The same choice is offered by the list's toolbar button; both read and
write one stored value, so changing either changes the other.

**Acceptance criteria**
- AC1: Layout mode offers Grid (default) and List.
- AC2: Grid shows the notes in two columns and List in one.
- AC3: The Settings row and the toolbar button are one value: a change in either place applies to the other.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-04`

### SET-05 Sorting defaults for notes, tags and notebooks
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Three separate sort choices. **Sort by** (notes): Title ascending or descending, Date created ascending or descending,
Date modified ascending or descending; default Date modified, newest first. **Sort tags by**: Title or Date created,
ascending or descending; default Title ascending. **Sort notebooks by**: the same four values; default Title ascending,
used by the drawer and the Manage notebooks screen. The notes sort applies to All notes, a notebook, Archive, Bin and
Search, and pinned notes stay first in every order. The lists' own toolbar menus write the same values.

**Acceptance criteria**
- AC1: Notes sort offers six values and defaults to Date modified descending; pinned notes stay first.
- AC2: Tag sort offers four values and defaults to Title ascending.
- AC3: Notebook sort offers four values, defaults to Title ascending, and applies to the drawer and Manage notebooks.
- AC4: A sort chosen in Settings and in a list's menu is the same stored value and takes effect immediately.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-05`

### SET-06 Editor defaults and checklist behaviour
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Default editor mode** is View (default) or Edit: the mode an existing note opens in. An empty note still opens for
editing, and a binned note opens read-only. **Text editor/view font size** is Default (the theme's size) or 10, 15, 20,
25, 30, 35, 40, 45 or 50, and sets the title, body and checklist text size of a note opened afterwards. **Move
(un)checked items in the lists** is Yes (default) or No: with Yes, checking an item moves it to the end of the checklist
and unchecking moves it up to just after the last open item (never further down); with No the order is left alone.

**Acceptance criteria**
- AC1: Default editor mode View (default) or Edit decides how an existing note opens; empty notes open in Edit and
  binned notes are read-only either way.
- AC2: Font size Default or 10–50 changes the note title, body and checklist text of a note opened after the change;
  Default keeps the theme's size.
- AC3: With Move checked items Yes (default), a checked item moves to the end and an unchecked item moves back up; with
  No the order does not change.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-06`

### SET-07 Show date and the date/time formats
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Show date created/modified** (Yes by default) shows or hides the "Created at … / Last modified at …" line of an open
note; the note lists never show a date. **Date format** offers five patterns, default `MMMM d yyyy`, and **Time format**
offers 24-hour `HH:mm` (default) or 12-hour `hh:mm a`; each row shows a sample of the current choice formatted for
today. Both formats are used by the note's date line and by the date and time buttons of the reminder dialog.

**Acceptance criteria**
- AC1: Show date Yes (default) shows the created/modified line in an open note and No hides it; no note list shows a
  date.
- AC2: Date format offers five patterns, defaults to a month-name date, and its row shows today's date in the chosen
  pattern.
- AC3: Time format offers 24-hour (default) and 12-hour, and its row shows the current time in the chosen pattern.
- AC4: The chosen date and time formats are used by the note date line and by the reminder date and time buttons.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-07`

### SET-08 Edit/View button position
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Edit/View note button position** is Floating button (default) or Top bar: the control that switches a note between
view and edit mode either floats over the note or sits in the note's toolbar. Exactly one of the two is shown. The
control is absent on a binned note and on an empty note in both positions.

**Acceptance criteria**
- AC1: The options are Floating button (default) and Top bar, and exactly one toggle is shown, in the chosen place.
- AC2: The toggle is absent on a binned note and on an empty note in both positions.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-08`

### SET-09 Open media in
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Open media in** is Internal player (default) or External player. With Internal player, image, audio and video
attachments open in the app's own media screen; with External player every attachment is handed to another app. A
generic (non-media) attachment is always handed to another app, even with Internal player.

**Acceptance criteria**
- AC1: Internal player (default) opens image, audio and video attachments in the app.
- AC2: External player opens attachments with another app.
- AC3: A generic attachment opens with another app even when Internal player is chosen.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-09`

### SET-10 Notes without a notebook in the drawer
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Group notes which are not in any notebook** is No (default) or Yes. With No, the drawer has no extra entry for notes
that have no notebook. With Yes, it shows a notebook-like entry titled **Other** (plus " (Default)" when a real
notebook is also called Other) that lists only notes without a notebook. All notes lists them either way.

**Acceptance criteria**
- AC1: With No (default) the drawer has no entry for notes without a notebook.
- AC2: With Yes the entry **Other** appears and lists only notes without a notebook.
- AC3: Notes without a notebook appear under All notes in both cases.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-10`

### SET-11 Delete notes in bin
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Delete notes in bin** is the bin retention: After 7 days (default), After 14 days, After 30 days, Never or Instantly.
A periodic clean permanently deletes a binned note once its deletion time is older than the chosen period, and then
cleans up attachments that nothing uses. Never keeps binned notes until the user empties the bin; Instantly skips the
bin, so deleting a note removes it at once. The Bin screen states the active choice. Bin, restore and delete mechanics
are DEL-01 to DEL-06.

**Acceptance criteria**
- AC1: The options are After 7 days (default), After 14 days, After 30 days, Never and Instantly.
- AC2: With a day value, a note binned longer ago than that period is permanently deleted by the periodic clean,
  attachments included.
- AC3: Never keeps the note until the user empties the bin; Instantly deletes a note without binning it.
- AC4: The Bin screen names the active period, or says that notes are deleted instantly or never.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-11`

### SET-12 Keep folder history
**Status:** Implemented · **Scope:** Core · **Tests:** `EachPhoneRemovesItsOwnOldHistoryTest.theRetentionSetting_defaultsToThreeMonths`,
`EachPhoneRemovesItsOwnOldHistoryTest.threeMonths_deletesJuly_andKeepsAugustAndNovember`,
`EachPhoneRemovesItsOwnOldHistoryTest.anotherDevicesOldLog_isNeverDeleted`,
`EachPhoneRemovesItsOwnOldHistoryTest.forever_keepsEveryFile`
**Keep folder history** is 1 month, 3 months (default), 6 months, 1 year or Forever. It is this device's setting and
only governs pruning of this device's own month files; another device's files are never deleted. Forever keeps every
file, and a shortened period takes effect at the next daily prune. Pruning rules and what the Folder history screen
then shows are HST-13.

**Acceptance criteria**
- AC1: The options are 1 month, 3 months (default), 6 months, 1 year and Forever.
- AC2: The chosen period applies to this device's files only.
- AC3: Forever keeps every file, and a shortened period acts at the next daily prune.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-12`

### SET-13 Backup strategy for attachments
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Backup strategy for attachments** is read when a backup is created: **Backup everything** (default) puts attachment
files into the zip, **Only backup description and local path** keeps each attachment's description and path without the
file, and **Do not backup attachments** leaves attachments out of the backup. Notes, notebooks, tags and reminders are
included in every case (BAK-01).

**Acceptance criteria**
- AC1: The options are Backup everything (default), Only backup description and local path, and Do not backup
  attachments.
- AC2: Backup everything includes attachment files in the zip.
- AC3: Only backup description and local path keeps the attachments' metadata but no files.
- AC4: Do not backup attachments leaves attachments out of the backup; notes, notebooks, tags and reminders are always
  included.

**Screens:** DLG-18, SCR-09
**Gherkin:** [`settings.feature`](../features/settings.feature) — `@SET-13`

### SET-14 Rows that hand off to their own requirement
See FLD-01, FLD-07, FLD-10, SYN-03, WRS-04, CON-02 and HST-07.

These Settings rows add no behaviour of their own:

- **Storage location** shows the folder path or the prompt to choose one — FLD-01; picking a different folder asks
  Move my notes here / Open this folder as-is / Cancel — FLD-07, FLD-08, FLD-09 — and warns once about a folder provided
  by another app — FLD-10.
- **Rescan notes folder** — SYN-03.
- **Clear unfinished saves** — WRS-04.
- **Sync conflicts** — CON-02.
- **Folder history** — HST-07.
- **Create a backup** and **Restore** — BAK-01, BAK-02.

**Screens:** SCR-09
**Gherkin:** none of its own; each row is covered by the requirement it points at.
