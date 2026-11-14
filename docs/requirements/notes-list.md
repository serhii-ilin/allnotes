# Notes lists (NTL)

How notes are browsed, selected and organized from the list screens: All Notes, Archive, Bin, a notebook and
Search. File and bin semantics stay in [deletion-and-bin.md](deletion-and-bin.md). Use cases: UC-03, UC-04, UC-05,
UC-07. Gherkin: [notes-list.feature](../features/notes-list.feature).

### NTL-01 All Notes lists every live note
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
All Notes is the default screen and its toolbar title is "Notes". It shows every note that is neither archived nor
deleted, from every notebook, with pinned notes first. Archived and binned notes have their own screens. Hidden notes
are filtered out unless hidden notes are shown (NTL-07). An empty list says "Your notes will appear here." Pulling the
list down runs a sync, then stops the refresh indicator.

**Acceptance criteria**
- AC1: All Notes shows notes that are neither archived nor deleted, from every notebook.
- AC2: Archived and binned notes do not appear in All Notes.
- AC3: Pinned notes come before unpinned ones; the rest follow the chosen sort (NTL-05).
- AC4: An empty All Notes shows no cards and the text "Your notes will appear here.".
- AC5: Pulling the list down runs a sync and ends with the refresh indicator stopped.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-01`

### NTL-02 A card previews the note
**Status:** Implemented · **Scope:** Core · **Tests:** `PreviewSourceTest` (the cut to 15 lines/800 characters and the surrogate-safe cut)
Each note is a card. An empty title leaves the title row blank. A text note shows its body cut to 15 lines or 800
characters, whichever comes first; Markdown notes are rendered and plain notes are shown as text. A checklist note
shows at most 8 tasks and a "+N items" line instead of the body. Up to four attachments are previewed with a "+N"
count for the rest, and the first tag is shown with "+N" for further tags. Compact preview hides the body, the tasks
and the attachments.

**Acceptance criteria**
- AC1: A card shows the note title; an empty title shows no title.
- AC2: A text note previews at most 15 lines and 800 characters, cut without splitting a character; Markdown notes
  are rendered and plain notes are shown as text.
- AC3: A checklist note shows at most 8 tasks plus "+N items" and no body preview.
- AC4: At most four attachments are previewed with "+N" for the rest; the first tag is shown with "+N" for further
  tags.
- AC5: Compact preview hides body, tasks and attachments; full preview restores them.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-02`

### NTL-03 Note cards carry no date
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A note card never shows its creation or modification date. The Settings preference "Show date created/modified" does
not change any card; it only controls the date line in the editor, which reads "Created at … / Last modified at …" in
the selected date and time formats. That line is shown by default.

**Acceptance criteria**
- AC1: No card in any list shows a creation or modification date.
- AC2: Changing Show date created/modified does not change what a card shows.
- AC3: With the setting on (the default) the editor shows both timestamps; with it off the editor hides them.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-03`

### NTL-04 Layout is a grid or a list
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Every note list uses one app-wide layout: grid (two staggered columns, the default) or list (one column). All Notes and
a notebook screen offer a toolbar action that switches the mode, and its icon shows the mode it switches to. Archive,
Bin and Search use the stored mode but offer no switch. The choice is remembered across restarts.

**Acceptance criteria**
- AC1: Grid mode lays cards out in two staggered columns; list mode uses one column.
- AC2: The default layout is grid.
- AC3: All Notes and a notebook screen switch between the modes and their icon shows the other mode.
- AC4: Archive, Bin and Search use the stored mode and have no layout action.
- AC5: The chosen mode survives leaving the screen and restarting the app.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-04`

### NTL-05 Notes are sorted by name, creation or modification
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Every list uses one sort method: title, creation date or modification date, ascending or descending. The default is
modification date, newest first. The sort method is a single-choice checkable menu on All Notes and a notebook
screen; Archive, Bin and Search follow the same stored method but cannot change it. Pinned notes always come before
unpinned ones (NTL-06).

**Acceptance criteria**
- AC1: The six choices are title, created and modified, each ascending and descending.
- AC2: The default is most recently modified first.
- AC3: Pinned notes come before unpinned notes for every method.
- AC4: The menu checkmark matches the current method, and only All Notes and notebook screens show the menu.
- AC5: The choice is remembered and used by All Notes, notebooks, Archive, Bin and Search.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-05`

### NTL-06 Pinned notes stay at the top
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A live note can be pinned or unpinned from its action sheet; archived and binned notes have no pin action. Pinning or
unpinning updates the note's modification date, and a pinned card shows a pin icon outside search results. Pinned
notes are listed before unpinned ones in every list. The bulk action "Pin / Unpin" toggles each selected note on its
own, so a mixed selection is inverted note by note.

**Acceptance criteria**
- AC1: A live note offers Pin when unpinned and Unpin when pinned; archived and binned notes offer neither.
- AC2: Pinned notes appear before unpinned notes in All Notes, notebooks, Archive, Bin and Search.
- AC3: A pinned card shows a pin icon outside search results.
- AC4: Pinning and unpinning update the modification date.
- AC5: "Pin / Unpin" on a selection toggles every selected note individually.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-06`

### NTL-07 Hidden notes stay out of the lists
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Hiding a note removes it from every list unless hidden notes are shown. All Notes, Archive and Bin have a checkable
"Show hidden notes" overflow item, and the state is shared by all screens for the current app session. A shown hidden
note carries a hidden icon outside search. The action sheet offers Hide on a visible note and Show on a hidden one.
Search applies the same filter but has no toggle of its own.

**Acceptance criteria**
- AC1: Hidden notes are absent from All Notes, a notebook, Archive and Bin while hidden notes are not shown.
- AC2: "Show hidden notes" is checkable on All Notes, Archive and Bin; toggling it on one screen affects the others.
- AC3: Hiding a note removes it from the list, and Show restores it.
- AC4: A hidden note that is shown carries a hidden icon, except in search results.
- AC5: Search applies the same hidden filter and offers no way to change it.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-07`

### NTL-08 Archiving moves a note to the Archive screen
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Archiving a live note marks it archived, updates its modification date, removes it from All Notes and its notebook,
and announces "Archived note" or "Archived notes". The Archive screen is titled "Archive" and lists archived,
non-deleted notes; when empty it says "Your archived notes will appear here." It offers search, Show hidden notes and
Select All, but no sort or layout action, no new-note button and no bottom bar. Unarchiving returns the note to its
notebook.

**Acceptance criteria**
- AC1: Archiving removes the note from All Notes and its notebook and adds it to Archive; unarchiving reverses that.
- AC2: Archive lists archived, non-deleted notes and shows "Your archived notes will appear here." when empty.
- AC3: Archive offers search, Show hidden notes and Select All, but no sort, layout, new-note button or bottom bar.
- AC4: Archiving updates the modification date and announces a singular or plural message.
- AC5: The Archive selection menu offers unarchive, delete, hide, export and select all.

**Screens:** SCR-01, SCR-03
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-08`

### NTL-09 The Bin screen lists binned notes
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The Bin screen is titled "Deleted" and lists the notes in the bin. Its empty state explains the retention setting:
the number of days, never, or instantly. "Empty bin" asks for confirmation and, on Yes, permanently deletes every
binned note. The selection menu offers restore, delete permanently, hide, export and select all. The screen has search
and Show hidden notes but no new-note button. What binning does to files is DEL-01…DEL-06.

**Acceptance criteria**
- AC1: Bin lists binned notes under the title "Deleted".
- AC2: The empty state follows the retention setting: "for N days", "never" or "instantly".
- AC3: "Empty bin" asks for confirmation; Yes permanently deletes all binned notes and No changes nothing.
- AC4: The Bin selection menu offers restore, delete permanently, hide, export and select all.
- AC5: Bin has search and Show hidden notes but no new-note button, bottom bar, sort or layout action.

**Screens:** SCR-01, SCR-04
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-09`

### NTL-10 Multi-select works the same on every note list
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A long press on a card opens the note's action sheet (NTL-15); its **Select more…** item enters selection mode with
that note selected. In selection mode a tap toggles a note and a long press does nothing. The primary
toolbar is replaced by a selection toolbar that says "Selected N notes". Back clears the selection before leaving the
screen. Select All selects the cards currently visible. The selection survives rotation, and a bulk action clears it
except Select All. Search has no selection mode.

**Acceptance criteria**
- AC1: Select more… in the action sheet enters selection mode with that note selected; in selection mode a tap
  toggles a note and a long press does nothing.
- AC2: The selection toolbar replaces the primary toolbar and shows the selected count with the correct plural.
- AC3: Back clears the selection; a second back leaves the screen.
- AC4: Select All selects every card currently visible, including hidden notes only when they are shown.
- AC5: Selection survives rotation and is cleared by every bulk action except Select All; Search cannot select.

**Screens:** SCR-01, SCR-03
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-10`

### NTL-11 Bulk actions apply to the selected notes
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
All Notes and notebook selections offer Pin / Unpin, Compact preview, Full preview, Archive, Delete, Hide, Move to…,
Duplicate, Export and Select All. Archive selections offer unarchive, delete, hide, export and select all; Bin
selections offer restore, delete permanently, hide, export and select all. Delete moves notes to the bin, or deletes
them permanently when the retention setting is "instantly", and announces the result. Move to… lists "No Notebook" and
the notebooks, preselects the common notebook only when all selected notes share one, and links to manage notebooks.
Duplicate creates an independent copy with the note's tags and reminders. Export opens the system create-document
picker for a zip of the selected notes.

**Acceptance criteria**
- AC1: Each screen's selection menu offers exactly the actions listed above for it.
- AC2: Pin / Unpin, Compact/Full preview, Hide and Archive/Unarchive apply to every selected note.
- AC3: Delete bins the notes, or deletes permanently when the setting is "instantly", and announces the singular or
  plural result.
- AC4: Move to… assigns the chosen notebook or No Notebook; the current notebook is preselected only when all selected
  notes share it.
- AC5: Duplicate makes a new note with the same tags and reminders; Export writes the selected notes to a zip chosen in
  the system picker.

**Screens:** DLG-07, DLG-21, SCR-01, SCR-03
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-11`

### NTL-12 Search finds notes by the words they contain
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Search opens from All Notes, Archive, Bin and a notebook. Typing waits 300 ms, then matches case-insensitively any
note whose title, body (or task text for a checklist), attachment description, tag name or own notebook name contains
the text. It searches archived and binned notes, and hidden notes when hidden notes are shown; result cards show
archive and bin icons. Results are not selectable in bulk; a long press opens the single-note actions. An empty query
shows "Search results will be displayed here.", and a query with no match shows "No results found.".

**Acceptance criteria**
- AC1: The query is matched case-insensitively against title, body or task text, attachment descriptions, tag names
  and the note's notebook name.
- AC2: Archived and binned notes are searched and carry their card icon; hidden notes are searched only when hidden
  notes are shown.
- AC3: Results follow the stored sort method, are not selectable in bulk, and a long press opens the single-note
  actions.
- AC4: An empty query shows the prompt; a non-empty query with no match shows "No results found.".
- AC5: A query passed to Search is pre-filled and focused; otherwise the empty field is focused with the keyboard.

**Screens:** DLG-21, SCR-05
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-12`

### NTL-13 A notebook screen shows one notebook
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The drawer lists every notebook and opens a screen titled with the notebook name that lists only that notebook's
notes which are neither archived nor deleted. The screen has the same sort, layout, search, Show hidden notes and
selection actions as All Notes, and a note created there belongs to the notebook. If the notebook no longer exists,
the screen closes on resume. When "Group notes which are not in any notebook" is Yes, the drawer also shows "Other",
which lists notes without a notebook; a note created there stays without one.

**Acceptance criteria**
- AC1: The drawer lists every notebook by name; opening one shows only its live notes and uses the name as the title.
- AC2: The notebook screen offers the All Notes sort, layout, search, Show hidden notes and selection actions.
- AC3: A note created from the notebook screen is assigned to that notebook.
- AC4: A deleted notebook's screen closes on resume.
- AC5: "Other" appears only when the group setting is Yes, lists notes with no notebook, and new notes created there
  stay unassigned.

**Screens:** SCR-02
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-13`

### NTL-14 New notes start from the FAB and the bottom bar
**Status:** Implemented · **Scope:** Core · **Tests:** `NoteRepositoryDiscardEmptyNotesTest` (a blank note is discarded); the rest none
yet (@gap)
All Notes and notebook screens have a new-note button and a bottom bar with four actions. The button opens an empty
editor. Create a list opens the editor in checklist mode. Attach files opens the system picker for images, video,
audio, PDF and text, allows several files, and opens the editor with them attached. Take a photo opens the camera and
attaches the result; cancelling adds nothing. Record audio opens the recorder dialog and attaches the recording. The
button and bottom bar disappear while notes are selected. A new note left completely empty is discarded on returning
to the list; a title, text, attachment, reminder or tag keeps it.

**Acceptance criteria**
- AC1: The new-note button opens an empty editor.
- AC2: Create a list opens the editor with a checklist note.
- AC3: Attach files accepts images, video, audio, PDF and text files, several at once, and opens the editor with them
  attached.
- AC4: Take a photo and Record audio attach the captured media and open the editor; cancelling either adds nothing.
- AC5: The button and bottom bar are hidden in selection mode, and an untouched empty note is discarded when returning
  to the list.

**Screens:** SCR-01
**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-14`

### NTL-15 A long press opens the note's action sheet
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A long press on a card in All Notes, a notebook, Archive, Bin or Search opens a sheet titled with the note's title.
It offers, depending on the note: **Move to…** and **Duplicate** (only for a note that is neither archived nor
binned), **Delete** (not in the bin; announces "Moved note to bin", or "Deleted note permanently." when the bin
retention is *Instantly*), **Show** or **Hide**, **Compact preview** or **Full preview**, **Enable/Disable screen
always on** and **Enable/Disable markdown** (not in the bin), **Export**, **Share**, and **Select more…** (not in
Search), which enters selection mode (NTL-10).

**Acceptance criteria**
- AC1: A long press on a card opens a sheet titled with the note's title.
- AC2: Move to… and Duplicate are offered only for a note that is neither archived nor binned.
- AC3: Show/Hide, Compact/Full preview, screen-always-on and markdown items show the action that changes the note's
  current state; the last two are not offered in the bin.
- AC4: Delete is not offered in the bin and announces the bin or permanent-delete result.
- AC5: Export, Share are always offered; Select more… is offered everywhere except Search.

**Gherkin:** [`notes-list.feature`](../features/notes-list.feature) — `@NTL-15`
