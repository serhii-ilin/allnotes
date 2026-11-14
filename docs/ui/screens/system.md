# System surfaces

Back to [screens](../README.md). Behavior: [integration.md](../../requirements/integration.md),
[reminders.md](../../requirements/reminders.md), [media.md](../../requirements/media.md),
[backup.md](../../requirements/backup.md). How each is realized per platform: [platform/android.md](../../platform/android.md).

## SCR-14 Home-screen widget
```text
┌──────────────────────────────────────┐
│ AllNotes                             │
│ Plan                          13 Nov │  pinned, live notes; newest first; at most 20 (INT-05)
│ Buy bread, call Anna, book trip…     │  one-line preview (≤100 chars) or "done / total tasks"
│ Passwords                     02 Nov │
│ 3 / 7 tasks                          │
└──────────────────────────────────────┘
```
- A row opens that note in the editor (INT-06).
- No pinned notes: "No pinned notes yet. Pin a note to see it here." — tapping opens the app.
- Hidden pinned notes are listed too (open finding in the 2026-11-12 review).

## SCR-15 Notifications
```text
Reminder (REM-11, channel "Reminders")          Playback (MED-08, channel "Media playback")
┌──────────────────────────────────────┐        ┌──────────────────────────────────────┐
│ 🔔 You have a reminder.              │        │ ♪ recording.m4a           [art]      │
│    Call Anna                         │        │    ▶/❚❚    ■                         │
└──────────────────────────────────────┘        └──────────────────────────────────────┘
 tap → opens the note; no buttons                stop also closes the built-in player

Backup / restore (BAK-05, channel "Backups")
┌──────────────────────────────────────┐
│ Backing up your notes…   ▓▓▓▓▓░░░░   │  → "Backup complete!" / "Backup failed"
│ Restoring your notes…                │  → "Restore completed" / "Restore failed"
└──────────────────────────────────────┘
```

## SCR-16 Entry points from the system
| Entry | Result | Requirements |
|---|---|---|
| Share sheet → "Take a note" with text | New note: shared title as title, shared text as body, editor open | INT-01 |
| Share sheet with one or more images, videos or audio files | New note with copies of the files attached | INT-02, MED-11 |
| Launcher long press → "Take a note" / "Make a list" | Editor on a new text note / checklist | INT-04 |
| Widget row | Editor on that note | INT-06 |
| Reminder notification | Editor on that note | REM-11 |
| Deep link `allnotes://notes/{transitionName}?newNoteIsList=…` | Editor on a new note | INT-03 |
