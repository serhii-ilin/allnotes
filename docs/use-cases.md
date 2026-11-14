# Use cases

What a person does with AllNotes. Each use case lists its requirements. Multi-device and failure stories are in
[scenarios.md](scenarios.md). Keep this file current per [docs/README.md](README.md).

**Actors**
- **User**: the person using AllNotes on a phone.
- **External editor**: any other tool that edits the notes folder (Obsidian, a desktop text editor, a file manager).
- **Sync tool**: Syncthing or similar, copying the folder between devices. It can add, change, rename or delete files
  at any time.

---

## UC-01 Choose a notes folder for the first time
**Actor:** User · **Requirements:** FLD-02, FLD-03, FLD-10, UI-01, FLD-01
1. The user opens the app; it works with notes stored only on the device.
2. The drawer and an onboarding dialog suggest choosing a notes folder.
3. The user picks a folder in the system picker.
4. The app exports every existing note into the folder, and links notes whose file already exists there instead of
   duplicating them.
5. The drawer shows the folder path.

*Alternatives:* 3a. The folder belongs to a cloud app's provider: the app warns once that listings may be unreliable.

## UC-02 Confirm the folder after upgrading from the old app
**Actor:** User · **Requirements:** FLD-04, FLD-05
1. After the update, the app asks whether to keep using the folder configured in the old version.
2. Until the user answers, the app reads and writes nothing in that folder.
3. On confirmation, old mappings are relinked to files by content, and unmatched notes are exported on the following
   sync.

## UC-03 Write and edit a note
**Actor:** User · **Requirements:** FMT-01, FMT-02, SYN-06, EDT-01, EDT-02, EDT-13, EDT-14, NTL-14
1. The user creates or edits a note.
2. A moment after saving, the note's file is written (created on first save, renamed when the title changes).

## UC-04 Organize notes with notebooks and tags
**Actor:** User · **Requirements:** FMT-04, FMT-02, FMT-11, ORG-01…ORG-13
1. The user moves a note to a notebook, renames a notebook, or deletes one.
2. Creating or renaming a notebook accepts only a name that is a valid folder name and not another notebook's
   folder; an invalid or duplicate name is refused inline and the dialog stays open.
3. The file and its attachments move to the notebook's folder (to the root when deleted). Tags are written to front
   matter.

## UC-05 Attach files
**Actor:** User · **Requirements:** FMT-05, MED-01…MED-12, FMT-10
1. The user adds a photo, recording or file to a note.
2. The file is copied into the note's asset folder and linked from the note's text. Removing it removes the link.

## UC-06 Set and clear reminders
**Actor:** User · **Requirements:** REM-01…REM-04, REM-06…REM-14, SYN-08
1. The user sets a reminder; it is written into the note's front matter and fires on every device that has the note.
2. The user removes the last reminder; the file records an empty list so other devices drop theirs.

## UC-07 Bin, restore and permanently delete
**Actor:** User · **Requirements:** DEL-01, DEL-02, REM-04
1. The user bins a note; its file moves to `.trash/`.
2. The user restores it; the file moves back with its reminders.
3. Or the user empties the bin; the `.trash/` file and the assets are removed.

## UC-08 Edit notes with another tool
**Actor:** External editor · **Requirements:** SYN-07, FMT-03, FMT-07, FMT-08, FMT-09, CON-03, DEL-06, CON-04
1. The user edits, creates or deletes Markdown files in another app.
2. On the next sync AllNotes shows the change. New files become notes and receive the attributes the app requires.
   Foreign front matter is kept.

## UC-09 Use the same notes on several devices
**Actor:** User, Sync tool · **Requirements:** SYN-01, SYN-02, IDN-01…IDN-05, DEL-03, CON-01, IDN-06, DEL-04, DEL-05, SYN-04, SYN-05, FLD-11, HST-13, WRS-01, WRS-03, WRS-05
1. The user points Syncthing at the notes folder on each device and chooses that folder in AllNotes everywhere.
2. Changes made on any device appear on the others after the sync tool copies them and AllNotes rescans.

## UC-10 Rename or move a note anywhere
**Actor:** User, External editor · **Requirements:** IDN-02, IDN-03, FMT-04
1. A note's title or notebook changes on one device, or its file is renamed or moved in another tool.
2. On every device it stays the same single note, with the new name and notebook.

## UC-11 Resolve a conflict
**Actor:** User · **Requirements:** CON-01, CON-02
1. The app reports that a note was changed in two places and saved the other version as a conflict copy.
2. The user opens Settings → Sync conflicts, opens the copy, merges what they need, and deletes the copy.

## UC-12 Switch to a different notes folder
**Actor:** User · **Requirements:** FLD-06…FLD-09
1. The user picks another folder in Settings.
2. If it is the same folder, nothing changes. Otherwise the app asks:
   - **Move my notes here**: notes are copied into the new folder, and nothing in the old folder is deleted, even when
     the folders overlap; the user is told the previous folder still has the old files and can delete them;
   - **Open this folder as-is**: the app shows the new folder's notes and leaves the old files alone;
   - **Cancel**: nothing changes.

## UC-13 Rescan the folder now
**Actor:** User · **Requirements:** SYN-03, UI-02
1. The user taps **Rescan notes folder**. The row shows progress, then the result.

## UC-14 Recover from a stuck save
**Actor:** User · **Requirements:** WRS-02, WRS-04, UI-02
1. A save was interrupted (crash, provider error). The app recovers it on the next start, or quarantines the file and
   keeps the note's content.
2. If a save still seems stuck, the user taps **Clear unfinished saves** and confirms.

## UC-15 Back up and restore
**Actor:** User · **Requirements:** BAK-01, BAK-02, BAK-03…BAK-05, EDT-11
1. The user exports a zip backup, and later restores it.
2. After restore, one sync reconciles with the folder without duplicates.

## UC-16 See what happened in the notes folder
**Actor:** User · **Requirements:** HST-01…HST-12
1. The user opens Settings → Notes folder → **Folder history**.
2. The app lists what every device did, newest first: binned, restored, purged and moved notes, and created, renamed
   and deleted notebooks and tags, each with the device (`Pixel 9 · a1b2c3`) and the time.
3. Entries from a newer app version are marked as such. Actions this device did not apply are listed with the reason.

*Alternatives:* 1a. No history yet, or `.allnotes/` was deleted: the screen says so; notes are unaffected.

## UC-17 Search and sort the notes
**Actor:** User · **Requirements:** NTL-04, NTL-05, NTL-07, NTL-12, NTL-01…NTL-03, NTL-13
1. The user opens Search from All Notes, Archive, Bin or a notebook and types a word; after a 300 ms pause the results
   list every note whose title, body or task text, attachment description, tag name or notebook name contains it,
   ignoring case.
2. Archived and binned notes are included and their cards carry an archive or bin icon; hidden notes appear only while
   hidden notes are shown.
3. An empty query says "Search results will be displayed here." and a query with no match says "No results found.".
4. On All Notes or a notebook the user changes the sort method among title, creation and modification date, ascending
   or descending; pinned notes stay first, and Archive, Bin and Search follow the same stored method.
5. With the layout action the user switches the notes between a two-column grid and a single list; the choice is
   remembered, and Archive, Bin and Search use it without offering the action.

*Alternatives:* 1a. Search results cannot be selected in bulk; a long press opens the single-note actions.

## UC-18 Pin, archive, hide and select several notes
**Actor:** User · **Requirements:** NTL-06…NTL-11, SET-11, NTL-15, EDT-10
1. In All Notes or a notebook the user long-presses a card; the toolbar becomes a selection toolbar reading
   "Selected N notes".
2. Taps add or remove notes, Select All takes every card currently visible, and Back clears the selection before it
   leaves the screen.
3. The selection menu offers Pin / Unpin, Compact preview, Full preview, Archive, Delete, Hide, Move to…, Duplicate,
   Export and Select All, and applies the chosen action to every selected note.
4. Delete moves the notes to the bin, or deletes them permanently when the retention setting is "Instantly", and the
   result is announced.
5. A note's action sheet pins or unpins it, archives it (which moves it to the Archive screen) or hides it; the Bin
   screen, titled "Deleted", lists the binned notes, and both screens offer their own selection actions.

*Note:* "Pin / Unpin" toggles each selected note on its own, so a mixed selection is inverted note by note; "Show
hidden notes" is checkable on All Notes, Archive and Bin and lasts for the current session.

## UC-19 Create, rename and delete notebooks and tags
**Actor:** User · **Requirements:** FMT-04, FMT-11, ORG-01…ORG-13
1. The user opens **Your Notebooks** from the drawer's Notebooks section and taps **Create a notebook**.
2. A name that is invalid on a file system, or that another notebook already uses, is refused inline and the dialog
   stays open; a valid unique name creates the notebook in the list and the drawer.
3. A long press on a notebook row offers **Rename notebook**, **Delete** and **Select more**; renaming moves its notes
   to the new folder, and deleting keeps the notes, which become notes without a notebook.
4. On the Tags screen the user creates, renames or deletes tags; deleting a tag removes it from every note that
   carried it and keeps the notes.
5. In the editor, **Tags** opens the tag list with a checkbox per tag; tapping a row adds or removes the tag at once,
   and the editor shows a **# name** chip for each tag.
6. The user sorts notebooks and tags by title or creation date, ascending or descending, and tapping a tag opens
   Search with its name in the query field.

*Alternatives:* 1a. With **Group notes which are not in any notebook** set to **Yes**, the drawer also shows **Other**,
which lists the notes without a notebook.

## UC-20 Format a note and work with a checklist
**Actor:** User · **Requirements:** FMT-06, EDT-02…EDT-09
1. The user opens a note; it starts in preview or in edit mode according to the default, and the floating button or
   the top-bar item switches between the two.
2. While editing a Markdown note the formatting toolbar wraps the selection in bold, italics, strikethrough, code,
   quote or highlight, walks a heading through six levels, and toggles a line between unchecked and checked.
3. The link, image and table buttons open dialogs that insert Markdown at the selection or at the cursor.
4. **Disable markdown** turns the body into plain text and the note into a `.txt` file; enabling it again keeps the
   text as typed.
5. The user converts the note to a checklist and back; in a checklist, ticking a row saves it and strikes it through,
   Enter and "+" add items, dragging reorders and swiping deletes.
6. The editor menu unchecks all items or removes every checked one, and sets the note's colour or keeps the screen on
   while the note is open.

*Alternatives:* 4a. A table wider than 15 columns is previewed as "Cannot preview table.".

## UC-21 Attach, photograph, record and open media
**Actor:** User · **Requirements:** MED-01…MED-10, MED-12
1. In a note, or on the main screen for a new one, **Attach files** opens the system picker for several image, video,
   audio, PDF and text files; each chosen file is copied and verified, and a failed copy is reported.
2. **Take a photo** opens the camera and attaches a saved photo; a cancelled capture adds nothing.
3. **Record audio** asks for the microphone, records AAC in an `.m4a` file, and adds the clip when the user stops.
4. Each attachment shows a tile; tapping it opens the built-in viewer for image, video and audio, or another app,
   according to **Open media in**, and a generic file always opens in another app.
5. A long press on a tile edits its description, deletes it or shares it; deleting removes the attachment from the
   note and leaves the stored copy to the cleanup.

*Alternatives:* 3a. A user who never granted the notification permission sees no playback notification for audio (see
audit).

*Note:* A binned note offers no attach action and no tile actions.

## UC-22 Share into AllNotes and use the shortcuts and the widget
**Actor:** User · **Requirements:** MED-11, INT-01…INT-07, INT-11…INT-14
1. In another app the user shares text and picks **Take a note**; the editor opens a new note whose title is the
   shared title and whose body is the shared text.
2. Sharing one or several images, videos or audio files copies them into the app and opens one new note holding them
   as attachments.
3. From the launcher, **Take a note** opens a blank note and **Make a list** a blank task list, in the running app
   rather than in a second task.
4. The home-screen widget lists the pinned notes, newest first and at most 20; tapping a row opens that note, and with
   no pinned note it says "No pinned notes yet. Pin a note to see it here.".
5. The widget fills itself when it is placed, refreshes every 30 minutes and on app start or a pin or unpin.

*Note:* A hidden pinned note is listed on the widget, and an edit, bin or hide of a pinned note leaves the widget
stale until the next refresh (see audit).

## UC-23 Change appearance and defaults in Settings
**Actor:** User · **Requirements:** SET-01…SET-14, INT-10, UI-03, EDT-12, UI-05
1. The user opens Settings and picks a theme mode (Follow system, Dark, Light), a dark theme (Standard or Black) and
   a colour scheme; the change shows at once and is kept for the next start.
2. **Layout mode**, **Sort by**, **Sort tags by** and **Sort notebooks by** set the same values the lists and the
   drawer use, and **Group notes which are not in any notebook** adds the drawer's **Other** entry.
3. Editor rows set the default editor mode, the editor font size, the checked-item behaviour, the created/modified line
   of an open note, the date and time formats, the edit/view button position and the media player.
4. **Delete notes in bin**, **Keep folder history** and **Backup strategy for attachments** set retention and backup
   behaviour; preferences stay on this device and never enter a AllNotes backup.
5. The rows for the notes folder, rescan, unfinished saves, sync conflicts, folder history, backup and restore hand
   off to those screens.

*Note:* The app has no switch that turns background scanning off, and no language picker of its own: the language is
chosen in Android's app-language settings.

## UC-24 The bin empties itself
**Actor:** User · **Requirements:** DEL-07…DEL-11, SET-11, BGD-03
1. The user sets **Delete notes in bin** to After 7 days (the default), 14 days, 30 days, Never or Instantly.
2. With a day value, a clean that runs about every five hours permanently deletes a binned note once it has been in
   the bin longer than that period, then deletes the private media no note refers to.
3. The purge stays on this device: it records nothing in the folder history, so the other devices keep their bin
   entries.
4. With **Never** the clean purges nothing, and with **Instantly** a deleted note never enters the bin.
5. **Empty bin**, after confirmation, deletes every note in the bin, whoever binned it, and clears the same media.

*Alternatives:* 2a. With **Never** or **Instantly** the automatic clean performs no media cleanup.

## UC-25 Know what runs in the background
**Actor:** User · **Requirements:** BGD-01…BGD-10, UI-03
1. Every app start registers two jobs: a sync every hour and a bin clean every five hours, both working without a
   network connection.
2. With a folder chosen, the app watches it and syncs when a file changes; returning to the app also syncs, at most
   once every two minutes.
3. A change that arrives while a sync is running does not cancel it; the app runs exactly one more sync afterwards.
4. The hourly sync reschedules the reminder alarms, even when it failed, and the bin clean applies the retention
   period in force at that run.
5. A scan ignores dot-folders, Android `.trashed-` files, Syncthing conflict copies and `.allnotes/`, whether it comes
   from the hourly job, the folder observer or a resume.
6. Playback and recording are user-started services, not scheduled work, and what they do is in the media area.

*Alternatives:* 2a. With no folder nothing is watched; returning to the app asks for one and syncs nothing.

*Note:* There is no switch that turns background scanning off.

## UC-26 Open the app on a new phone
**Actor:** User · **Requirements:** NFR-01…NFR-05, FLD-02, SET-01, NFR-06…NFR-15, NFR-17, SYN-09
1. The user installs AllNotes on Android 7.0 or later; features that need a newer Android appear on the versions that
   have them, and the older path is used elsewhere.
2. The install asks for no storage or camera permission; files arrive through the system picker, the camera app or
   another app's share.
3. The microphone is asked for by **Record audio** and notifications by the reminder dialog on Android 13 and later;
   exact alarms open the system screen on Android 12 and later.
4. Until a folder is picked the app keeps notes on the device; the user then picks the folder in the system picker, and
   the folder URI and this install's other secrets go to encrypted storage.
5. Notes and attachments stay plain files that any tool can read, and the app's settings belong to the install.

*Alternatives:* 5a. An Android backup restore can bring settings back but not the notes-folder location.

## UC-27 Check the version, the update notes and send a log
**Actor:** User · **Requirements:** INT-08, INT-09, NFR-16
1. After an update or a first install the app shows the newest What's new entry once, with its title and bullet items,
   and does not show it again for that id.
2. The drawer's About screen shows the app version and rows for Website, Developer, Contribute, Support, Libraries and
   Send Error Logs.
3. Libraries opens the third-party licences, and the four links open in another app.
4. Send Error Logs enables crash reporting for that one report and hands a JSON report to a mail app; no report leaves
   the device otherwise.
