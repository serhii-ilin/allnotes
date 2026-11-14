# Settings, folder history, about and the folder dialogs

Back to [screens](../README.md). Behavior: [settings.md](../../requirements/settings.md),
[notes-folder.md](../../requirements/notes-folder.md), [history.md](../../requirements/history.md),
[integration.md](../../requirements/integration.md). The folder lifecycle is in [states.puml](../states.puml).

## SCR-09 Settings
One scrolling page of rows; each row shows its title and, below it, the current value or a hint. Tapping a value row
opens DLG-18.

```text
┌────────────────────────────────────────┐
│ ←  Settings                            │
├────────────────────────────────────────┤
│ NOTES FOLDER                           │
│ Storage location                       │  folder path, or "Tap to choose a folder" (FLD-01) → system folder picker
│   /storage/emulated/0/Notes            │  → DLG-03 when a folder is already set, DLG-04 for a cloud provider
│ Rescan notes folder                    │  SYN-03; shows a progress bar while it runs
│   Look for notes changed outside AllNotes.
│ Folder history                         │  → SCR-10 (HST-07)
│   What happened in the notes folder, on every device.
│ Keep folder history          3 months  │  SET-12
│ Clear unfinished saves                 │  → DLG-17 (WRS-04)
│   Only if a note seems stuck while saving.
│ Sync conflicts                   None  │  "None" or "N conflict copies" → DLG-16 (CON-02)
│ GENERAL                                │
│ Theme mode                     System  │  SET-02
│ Dark theme mode              Standard  │
│ Color scheme                     Blue  │  SET-03
│ VIEW · NOTEBOOK                        │
│ Layout mode                      Grid  │  SET-04
│ Sort by        Date modified (descending)  SET-05
│ VIEW · NOTE CONTENT                    │
│ Text editor/view font size    Default  │  SET-06
│ Edit/View note button position    FAB  │  SET-08
│ Default editor mode              View  │  SET-06
│ Show date created/modified        Yes  │  SET-07
│ Date format             November 13 2026
│ Time format                     21:42  │
│ VIEW · TAG LIST                        │
│ Sort tags by        Title (ascending)  │  SET-05
│ VIEW · NAVIGATION DRAWER               │
│ Sort notebooks by   Title (ascending)  │  SET-05
│ OTHER                                  │
│ Open media in      Internal player     │  SET-09
│ Group notes which are not in any notebook   No   SET-10
│ Move (un)checked items in the lists   Yes  SET-06
│ Delete notes in bin   After 7 days     │  SET-11
│ BACKUP                                 │
│ Create a backup                        │  BAK-03 → system create-document picker
│   Export all notes to a backup file.   │
│ Restore                                │  BAK-04 → system open-document picker
│   Load notes from a backup file.       │
│ Backup strategy for attachments  Include files   SET-13
└────────────────────────────────────────┘
```
Not offered: a switch for "Use folder history for sync" (HST-11) —
[FC-12](../../audits/2026-11-13-full-coverage-review.md#fc-12).

### DLG-18 Preference picker
```text
┌──────────────────────────────┐
│ Theme mode                   │  row title
│ ◉ System                     │  single choice; only values the device supports
│ ○ Dark                       │  (e.g. Color scheme "System" needs Android 12)
│ ○ Light                      │
│                     [Done!]  │  a pick applies at once
└──────────────────────────────┘
```

## DLG-01 Choose a notes folder
Shown once per app start while no folder is set (FLD-02, UC-01).
```text
┌──────────────────────────────────────────┐
│ Choose a notes folder                    │
│ AllNotes keeps notes as Markdown files in│
│ a folder you choose. Notes stay on this  │
│ device until you pick a folder; you can  │
│ change or back up that folder anytime.   │
│      [Cancel]  [Select Storage location] │  → SCR-09
└──────────────────────────────────────────┘
```

## DLG-02 Keep using the folder from the old version
Shown after upgrading from a version that had a storage folder; nothing in it is read or written until answered
(FLD-04, FLD-05).
```text
┌──────────────────────────────────────────┐
│ Use this notes folder?                   │
│ AllNotes now keeps every note as a       │
│ Markdown file in your storage folder:    │
│ /storage/emulated/0/Notes. Use this      │
│ folder?                                  │
│ (Nextcloud is no longer supported … — only for former Nextcloud users)
│ [Not now] [Choose another folder] [Use this folder]
└──────────────────────────────────────────┘
```

## DLG-03 Switch the notes folder
After picking a different folder in Storage location (FLD-07…FLD-09).
```text
┌──────────────────────────────────────────┐
│ Switch notes folder?                     │
│ You picked a different folder: <path>    │
│ Move my notes there, or open that folder │
│ as it is? Opening it removes the notes   │
│ saved in your current folder from the    │
│ app (the files stay where they are) and  │
│ shows the notes of the new folder.       │
│ [Cancel] [Open this folder as-is] [Move my notes here]
└──────────────────────────────────────────┘
```
Result snackbars (UI-02): the move result and that the old files remain (FLD-08).

## DLG-04 Folder provided by another app
Once per folder (FLD-10).
```text
┌──────────────────────────────────────────┐
│ Folder from another app                  │
│ This folder is provided by another app   │
│ (for example a cloud drive). Listing can │
│ be slow or incomplete; AllNotes keeps    │
│ notes whose files go missing instead of  │
│ deleting them.                    [OK]   │
└──────────────────────────────────────────┘
```

## DLG-16 Sync conflicts
```text
┌──────────────────────────────────────────┐
│ Conflict copies                          │
│ Shopping                                 │  title (or path), then path; tap opens the copy in the editor
│ Shopping (conflict 1731500000).md        │
│ Plan                                     │
│ Work/Plan (conflict 1731500999).md       │
│                               [Cancel]   │
└──────────────────────────────────────────┘
```

## DLG-17 Clear unfinished saves
Only when there is something to clear; otherwise the row clears at once (WRS-04).
```text
┌──────────────────────────────────────────┐
│ Clear unfinished saves?                  │
│ Discards note saves that did not finish. │
│ Use this only if saving a note seems     │
│ stuck.                [Cancel]  [Clear]  │
└──────────────────────────────────────────┘
```

## SCR-10 Folder history
```text
┌────────────────────────────────────────┐
│ ←  Folder history                      │
├────────────────────────────────────────┤
│ 3 lines could not be read.             │  skipped-line count (HST-06), only when > 0
│ NOT APPLIED HERE                       │  mismatches (HST-11)
│ Binned “Plan”                          │
│ Pixel 9 · a1b2c3 · Nov 13, 10:42       │
│ Renamed or moved here; kept            │
│ ──────────────────────────────────     │
│ Binned “Shopping”                      │  merged events, newest first (HST-07)
│ Pixel 9 · a1b2c3 · Nov 13, 10:40       │  device label · time
│ Moved “Plan” to Work/Plan.md           │
│ Galaxy · 77f0e1 · Nov 12, 18:03        │
│ Created notebook “Work”                │
│ From a newer version (note.archive)    │  unknown op from a newer app
└────────────────────────────────────────┘
```
Empty: "Nothing has happened in the notes folder yet." · Error: "Could not read the folder history. Tap to try
again." · Pull to refresh reloads.

## SCR-11 About
```text
┌────────────────────────────────────────┐
│ ←  About                               │
│ Version                  <version name>│
│ Website                                │  links open in another app
│ Developer                              │
│ Contribute                             │
│ Support                                │  "Buy the developers a coffee!"
│ Send Error Logs                        │  one-off report by mail (INT-08, NFR-16)
│   Send the application logs to the developer for investigation
│ Libraries                              │  → DLG-20
│   View third-party libraries and licenses.
└────────────────────────────────────────┘
```

## DLG-19 What's new
Once per catalog update (INT-09).
```text
┌──────────────────────────────┐
│ <update title>               │
│ • item                       │
│ • item                       │
│                      [OK]    │
└──────────────────────────────┘
```

## DLG-20 Libraries
A scrolling dialog with the third-party licence text (INT-08).
