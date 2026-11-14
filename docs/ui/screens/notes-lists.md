# Note lists, drawer and search

Back to [screens](../README.md). Behavior: [notes-list.md](../../requirements/notes-list.md).

## Note card
Used by every list (NTL-02, NTL-03, MED-04). Grid shows two columns, list one (NTL-04).

```text
┌──────────────────────────────┐
│ [img] [img] [♪ ] [+2]        │  attachment tiles: at most 4, last shows +N (hidden in compact preview)
│ Shopping            👁̸ 🔔 📌 │  title (blank row when empty) · indicators: hidden, has reminder, pinned,
│                              │  in bin / archived (Search results only)
│ Milk                         │  body: ≤15 lines / 800 chars, Markdown rendered or plain text
│ Eggs                         │
│ ☐ call Anna                  │  checklist instead of body: ≤8 tasks
│ ☑ ~~buy bread~~              │
│ +3 items                     │
│ #home  +1                    │  first tag, +N for the rest
└──────────────────────────────┘
```
- The card background is the note's colour (EDT-09). No date is shown (NTL-03).
- Compact preview: title, indicators and tag only.

## SCR-01 All Notes

### Default state (grid)
```text
┌────────────────────────────────────────┐
│ ☰  Notes                 🔍  ⇅  ▦  ⋮   │  top bar: drawer · title · Search · Sort by · Layout · overflow
├────────────────────────────────────────┤
│ ┌───────────────┐ ┌───────────────┐    │  pinned notes first (NTL-06), then the chosen sort (NTL-05)
│ │ 📌 Plan       │ │ 📌 Passwords  │    │
│ │ …             │ │ …             │    │
│ └───────────────┘ └───────────────┘    │
│ ┌───────────────┐ ┌───────────────┐    │
│ │ Shopping      │ │ Ideas         │    │
│ └───────────────┘ └───────────────┘    │
│                  ↓ pull to sync        │  pull to refresh runs a sync (NTL-01)
├────────────────────────────────────────┤
│  ☑  📎  📷  🎙            ( + )        │  bottom bar: Create a list · Attach files · Take a photo ·
└────────────────────────────────────────┘  Record audio · FAB: new note (NTL-14)
```
Overflow: Show hidden notes (checkable, NTL-07) · Select All.
Sort by: Title ↑ / Title ↓ / Date created ↑ / ↓ / Date modified ↑ / ↓ (default) — radio list.
Layout: toggles grid ⇄ list (NTL-04, SET-04).

### Empty state
```text
┌────────────────────────────────────────┐
│ ☰  Notes                 🔍  ⇅  ▦  ⋮   │
│                                        │
│          Your notes will appear here.  │
│                                        │
│  ☑  📎  📷  🎙            ( + )        │
└────────────────────────────────────────┘
```

### Selection mode
```text
┌────────────────────────────────────────┐
│ ✕  Selected 2 notes          📌  ⋮     │  selection bar (NTL-10); FAB and bottom bar hidden
├────────────────────────────────────────┤
│ ┃▓ Plan ▓┃  ┌ Shopping ┐                │  selected cards are highlighted
│ ┃▓▓▓▓▓▓▓▓┃  └──────────┘                │
└────────────────────────────────────────┘
```
Selection actions (NTL-11): Pin / Unpin · Compact preview · Full preview · Archive · Delete · Hide · Move to… ·
Duplicate · Export · Select All. Back clears the selection first.

### Messages (snackbars)
"Moved note to bin" · "Deleted note permanently." · folder results such as "Vault scan incomplete; local notes were
not changed." or the kept-files notice when many files vanished (DEL-04, UI-02).

## SCR-02 Notebook
Same as SCR-01 with the notebook name as title (NTL-13). New notes start in this notebook. The "Other" entry
(ORG-08) shows notes without a notebook.
```text
┌────────────────────────────────────────┐
│ ☰  Work                  🔍  ⇅  ▦  ⋮   │
│ … cards of notes in "Work" only …      │
│  ☑  📎  📷  🎙            ( + )        │
└────────────────────────────────────────┘
```

## SCR-03 Archive
```text
┌────────────────────────────────────────┐
│ ☰  Archive                     🔍  ⋮   │  overflow: Show hidden notes · Select All
│ … archived note cards (same sort and layout as All Notes; no controls to change them) …
└────────────────────────────────────────┘
```
Empty state: "Your archived notes will appear here." 
Selection actions: Unarchive · Delete · Hide · Export · Select All. No FAB, no bottom bar.

## SCR-04 Bin
```text
┌────────────────────────────────────────┐
│ ☰  Deleted                     🔍  ⋮   │  overflow: Empty bin · Show hidden notes · Select All
│ … binned note cards …                  │
└────────────────────────────────────────┘
```
Empty state explains the retention (SET-11): "Your deleted notes will appear here for 7 days.", or the *never* /
*instantly* variant (NTL-09).
Selection actions: Restore · Delete permanently · Hide · Export · Select All. Tapping a note opens it read-only in the
editor with Restore and Delete permanently; editing shows "Notes inside the bin cannot be edited." (EDT-10).

### DLG-15 Empty bin
```text
┌──────────────────────────────────────┐
│ Are you sure?                        │
│ All deleted notes will be lost.      │
│                                      │
│                       [No]  [Yes]    │
└──────────────────────────────────────┘
```

## SCR-05 Search
```text
┌────────────────────────────────────────┐
│ ←  [ Search…                    ✕ ]    │  focused field; results after 300 ms (NTL-12)
├────────────────────────────────────────┤
│ ┌ Plan 🗄 ┐ ┌ Old list 🗑 ┐             │  archived/binned results carry their icon
│ └─────────┘ └─────────────┘             │
│                                        │
│   Search results will be displayed here.   (empty query)
│   No results found.                        (no match)
└────────────────────────────────────────┘
```
Opened from the 🔍 of SCR-01…SCR-04, or by tapping a tag chip (fills the field: ORG-13). No selection mode; a long
press opens DLG-21 without "Select more…".

## DLG-21 Single-note actions
Long press on a card (NTL-15).
```text
┌──────────────────────────────────────┐
│ Plan                                 │  sheet titled with the note title
│ ⇄  Move to…                          │  not archived / not binned
│ 🗑  Delete                            │  not in the bin
│ 👁  Hide            (or Show)         │
│ ▭  Compact preview (or Full preview) │
│ ☀  Enable screen always on (or Disable)   not in the bin
│ Ⓜ  Disable markdown (or Enable)      │  not in the bin
│ ⧉  Duplicate                         │  not archived / not binned
│ ⇪  Export                            │
│ ↗  Share                             │
│ ☑  Select more…                      │  not in Search
└──────────────────────────────────────┘
```

## SCR-13 Navigation drawer
```text
┌──────────────────────────────┐
│▓ /storage/emulated/0/Notes  ▓│  header: folder path (2 lines max) or "Tap to choose a folder" (UI-01, FLD-02)
│▓ ⚙ (folder settings)        ▓│  opens Settings → Notes folder
│▓ AllNotes                   ▓│  app name; opaque, below the status bar (UI-05)
├──────────────────────────────┤
│ 🗒  All Notes                 │
│ 🗄  Archive                   │
│ 🗑  Deleted                   │
│ ── Notebooks ──────────────  │
│ 📚 Your Notebooks            │  → SCR-07
│ 📓 Other                     │  only when "Group notes which are not in any notebook" is Yes (ORG-08, SET-10)
│ 📓 Home                      │  one checkable row per notebook, in the notebook sort (ORG-07)
│ 📓 Work                    ✓ │
│ ── Tags ───────────────────  │
│ #  Tags                      │  → SCR-08
│ ── Settings ───────────────  │
│ ⚙  Settings                  │  → SCR-09
│ ℹ  About                     │  → SCR-11
└──────────────────────────────┘
```
