# Note editor (EDT)

Gherkin: [editor.feature](../features/editor.feature).

### EDT-01 Preview shows unsaved title and body
**Status:** Partial · **Scope:** Core · **Tests:** `EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce`
When the user switches from edit mode to preview, the preview reflects the text in the title and body fields, not only
what has already been written to the database.

**Acceptance criteria**
- AC1: After typing a title and switching to preview without leaving the editor, the preview title matches what was typed.
- AC2: An empty title and empty body show the untitled placeholder only when both fields are actually blank.
- AC3: Title edits are queued for save the same way as body edits (debounced), not only on pause.
- AC4: The first Room load must not overwrite text the user already typed in the title or body fields before that emission.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-01`

### EDT-02 Edit and preview modes
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A note opens in preview when the default-editor-mode setting is View (the default) and in edit mode when it is Edit.
The mode is switched with a floating button, or with a menu item when the change-mode setting puts it in the top bar
instead. A note whose body is empty, or a checklist with no items, always opens in edit mode and offers no switch. The
switch's icon shows the mode it leads to.

**Acceptance criteria**
- AC1: With the setting View, opening a note with a body shows the preview; with Edit it opens with the title and body
  fields editable.
- AC2: The floating button switches in both directions and its icon shows the mode it leads to.
- AC3: With the change-mode setting "Top bar" the switch is a menu item and no floating button is shown; with "FAB" the
  menu item is not shown.
- AC4: A note with an empty body (or a checklist with no items) opens in edit mode whatever the setting, and has no
  mode switch.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-02`

### EDT-03 Markdown is a per-note switch
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Markdown is on for a new note and can be turned off from the editor menu for that note alone. With Markdown on, the body
is edited with live styling and previewed as Markdown; with it off the body is plain text and the formatting toolbar
never appears. The note is stored as `.md` when Markdown is on and `.txt` when it is off (FMT-01), and the flag travels
in the front matter (FMT-02). Turning Markdown off or on keeps the body text as typed. A table wider than 15 columns is
not previewed: it is replaced by "Cannot preview table.".

**Acceptance criteria**
- AC1: The menu entry reads "Disable markdown" for a Markdown note and "Enable markdown" for a plain-text note, and
  changes only the open note.
- AC2: A plain-text note shows its body literally in both modes and never shows the formatting toolbar.
- AC3: Turning Markdown off or on is saved immediately and changes the note's file extension between `.md` and `.txt`.
- AC4: The body text is unchanged by turning Markdown off and on again.
- AC5: A table with more than 15 columns is previewed as "Cannot preview table." instead of the table.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-03`

### EDT-04 The formatting toolbar
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
While a Markdown note that is not a checklist is being edited and the body has focus, a toolbar under the editor offers
bold, italics, strikethrough, code, quote, heading, highlight, link, image, table, toggle checkbox line, scroll to top,
scroll to bottom, undo and redo. Inline styles wrap the selection in their markers, or insert the opening and closing
markers at the cursor. Heading adds one `#` per press up to six and removes the prefix on the next press; quote adds one
`>` per press; toggle checkbox line adds `- [ ] ` and switches the line between unchecked and checked (FMT-06). Undo and
redo follow the body's own history; the scroll buttons move the body cursor to its start or end.

**Acceptance criteria**
- AC1: The toolbar is visible only while editing a Markdown note that is not a checklist and the body has focus; it is
  hidden in preview, for plain-text notes and for checklist notes.
- AC2: Bold, italics, strikethrough, code and highlight wrap the selected text in their markers, or insert both markers
  at the cursor, and leave the cursor after the opening marker.
- AC3: Heading walks a line from plain to `# ` through `###### `, one level per press, and back to plain on the next
  press.
- AC4: Quote prefixes the current line with `> ` and adds one more `>` per press; toggle checkbox line cycles a line
  through plain, `- [ ] ` and `- [x] `.
- AC5: Undo and redo are enabled only when there is something to undo or redo, and scroll-to-top and scroll-to-bottom
  scroll and place the body cursor at the first or last character.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-04`

### EDT-05 Insert link, image and table dialogs
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Three toolbar buttons open dialogs: link (address and label), image (path and description) and table (rows and columns).
The dialog is seeded with the selected text as the label or description. Insert replaces the selected text with the
generated Markdown, or inserts it at the cursor when nothing is selected. Link writes `[label](address)`, image writes
`![alt text](path "description")`, table writes a pipe table with a content row and a separator row. The table dialog
accepts digits only.

**Acceptance criteria**
- AC1: The link dialog prefills the label with the selected text, and Insert replaces the selection with
  `[label](address)`.
- AC2: The image dialog prefills the description with the selected text, and Insert replaces the selection with an image
  whose path and description were entered.
- AC3: The table dialog inserts a pipe table of the requested width with a content row and a separator row, and rejects
  blank or non-numeric input with an error while staying open.
- AC4: With no selection, the generated Markdown is inserted at the cursor.

**Screens:** DLG-10, DLG-11, DLG-12, SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-05`

### EDT-06 Checklist notes
**Status:** Implemented · **Scope:** Core · **Tests:** `TaskListMarkwonBindingsTest.editorChecked_strikethroughWithVaultContext`, `TaskListMarkwonBindingsTest.editorUnchecked_usesPlainTextNotMarkwonPreview`
A checklist note shows its items as checkbox rows in both modes. Ticking a row is saved and a checked row is rendered
struck through, while an unchecked row being edited shows its raw text. In edit mode the "+" button adds an item at the
top, Enter moves to the next item or adds one, rows are dragged to reorder and swiped to delete. In preview the item
text fields and drag handles are gone, and the checkboxes still toggle.

**Acceptance criteria**
- AC1: A checklist note shows one row per item with a checkbox, in both edit and preview mode.
- AC2: Ticking a row sets the item checked and saves the note.
- AC3: A checked row is rendered struck through; an unchecked row in the editor shows plain text without Markdown
  rendering.
- AC4: In edit mode Enter moves to the next item and adds an empty one when the next item is missing or already has
  text; "+" adds an item at the top; dragging reorders and swiping deletes; each change is saved.
- AC5: Preview mode hides the item text fields and drag handles while keeping the checkboxes usable.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-06`

### EDT-07 Convert between note and checklist
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The editor menu converts a text note into a checklist and back, and the entry names the conversion it performs.
Converting to a checklist takes every line of the body, including blank lines, as an unchecked item and empties the
body. Converting to a note writes one line per item into the body, without checkbox marks, and empties the item list.
Item order is kept; the checked state is not carried into the body.

**Acceptance criteria**
- AC1: "Convert to list" turns each body line into an unchecked item, in order, and empties the body.
- AC2: "Convert to note" writes one line per item with no checkbox marks and empties the item list.
- AC3: The menu entry reads "Convert to list" for a text note and "Convert to note" for a checklist note.
- AC4: The note stays open and the conversion is saved immediately.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-07`

### EDT-08 Bulk checklist actions and moving checked items
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A checklist note's menu offers "Uncheck all items" and "Remove all checked tasks". The first clears the checked state of
every item; the second deletes the checked items and their text. When the "Move checked items" setting is on (the
default), ticking an item moves it to the end of the list and unticking moves it up to just after the last item that is
still unchecked, never down. With the setting off, ticking changes nothing about the order. The setting is read when the
note is opened.

**Acceptance criteria**
- AC1: "Uncheck all items" sets every item unchecked and saves the list.
- AC2: "Remove all checked tasks" removes only the checked items and keeps the unchecked ones in order.
- AC3: With "Move checked items" on, a ticked item moves to the end of the list.
- AC4: With "Move checked items" on, unticking moves an item up to after the last unchecked item and never down; with
  the setting off the order is unchanged.
- AC5: Both menu entries are shown only for a checklist note that is not in the bin.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-08`

### EDT-09 Note colour and keep-screen-on
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The editor menu sets the note's colour and, separately, keeps the screen on. "Change colour" lists the twelve note
colours with the current one selected; choosing one recolours the editor and is saved immediately as the note's colour
(FMT-02). "Screen always on" is a checkable item: when checked the window keeps the screen on for as long as the note is
open, the check shows the note's stored value, and closing the editor clears the flag.

**Acceptance criteria**
- AC1: "Change colour" offers Default plus eleven colours, with the note's current colour selected.
- AC2: Picking a colour recolours the editor background and toolbars without closing the editor, and saves the note
  immediately.
- AC3: Checking "Screen always on" saves the note's flag and keeps the screen on while the editor is open; reopening the
  note shows the toggle checked.
- AC4: Closing the editor removes the keep-screen-on flag.

**Screens:** DLG-09, SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-09`

### EDT-10 Pin, archive, hide, bin and restore from the editor
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The editor menu pins or unpins, archives or unarchives, hides or shows and bins the note. Pin flips the flag and updates
the icon and label, archive flips the flag and leaves the editor, hide flips the flag and stays in the editor, and bin
moves the note to the bin (or deletes it at once when the deletion delay is "Instantly") and leaves the editor. A binned
note opens read-only with a snackbar that offers Restore, its menu offers Restore and Delete permanently, and both close
the editor.

**Acceptance criteria**
- AC1: Pinning flips the note's pinned flag, changes the label and icon between Pin and Unpin, and does not close the
  editor.
- AC2: Archiving flips the archived flag and closes the editor; hiding flips the hidden flag and keeps it open.
- AC3: Binning moves the note to the bin and closes the editor; with the "Instantly" deletion setting it is deleted
  permanently instead.
- AC4: A binned note cannot be edited and shows a snackbar with a Restore action.
- AC5: From the bin, the menu restores the note or deletes it permanently, and both close the editor.

**Screens:** DLG-07, SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-10`

### EDT-11 Export and share a note
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Export asks for a `.zip` destination through the system file picker and writes a backup of this one note, with
attachments handled by the backup strategy (BAK-01). Share opens the system chooser with the note's title and text; a
checklist is shared as ☑ and ☐ lines. When the note has attachments the intent also carries them as a multiple send with
read permission; otherwise it is a plain-text send.

**Acceptance criteria**
- AC1: Export opens a create-document picker for a zip named `allnotes_backup_<epoch>.zip` and writes a backup that
  contains only the open note.
- AC2: Share sends the title and the body as text; a checklist is sent one item per line with ☑ for checked and ☐ for
  unchecked items.
- AC3: With attachments the share intent is a multiple send that includes them; without attachments it is a plain-text
  single send.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-11`

### EDT-12 Editor font size
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The editor font size setting is applied when a note opens: Default keeps the theme sizes, and the nine sizes from 10 to
50 sp in steps of five are applied to the title field, the body field, both previews and checklist rows. Checklist
checkboxes are scaled down with small text but never enlarged above their default size.

**Acceptance criteria**
- AC1: A chosen size is applied to the title field, the body field, the title preview and the body preview.
- AC2: Default leaves the theme sizes unchanged.
- AC3: Checklist rows use the chosen size and their checkboxes are never scaled above their default size.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-12`

### EDT-13 Saves are debounced and flushed
**Status:** Implemented · **Scope:** Core · **Tests:** `EditorViewModelSaveTest.typingBurst_persistsOnlyLastContentOnceAfterDebounce`, `EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce`, `EditorViewModelSaveTest.vaultPush_happensOnceAfterLongerIdleWindow`, `EditorViewModelSaveTest.flush_persistsPendingContentImmediately`, `EditorViewModelSaveTest.mutations_applyInCallOrder`
Title, body and checklist edits go through one channel, so they are applied in call order. Nothing is written while the
user keeps typing: the database catches up 300 ms after the last change and the vault push follows 1.5 s after it.
Leaving the editor flushes the pending text immediately (database first, then vault) and cancels the timers, and the
write survives the editor view being destroyed. Discrete actions such as colour, conversion or attachments are saved at
once and include the text typed just before them. Stored title and body are trimmed of leading and trailing whitespace.

**Acceptance criteria**
- AC1: During a typing burst nothing is written; the last title and body are written to the database once, 300 ms after
  the burst ends.
- AC2: The vault push happens once, 1.5 s after the last change, and a later quiet period writes nothing more.
- AC3: Leaving the editor writes the pending text at once, database first and vault second, and cancels the pending
  timers.
- AC4: Discrete actions are applied in call order with the pending text, and a change made elsewhere such as a notebook
  move is not reverted by a later text save.
- AC5: Stored title and body are trimmed of leading and trailing whitespace.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-13`

### EDT-14 A draft is not lost when opening or discarding
**Status:** Implemented · **Scope:** Core · **Tests:** `EditorViewModelSaveTest.initialize_waitsForPreviousSessionFlush`, `EditorViewModelSaveTest.initialize_stopsWaitingForAStuckPreviousSession`, `EditorTitleOnPauseRegressionTest.noteWithTitleOnly_isNotEmpty`, `NoteUnitTests.defaultNoteShouldBeEmpty`
Opening a note waits up to five seconds for the previous editor session of the same note to finish writing, so the
fields are not filled with stale text; if that write never completes, the note opens anyway. A note whose title is not
blank is not empty, so a title-only draft is never removed by the cleanup that deletes blank notes.

**Acceptance criteria**
- AC1: Opening a note whose previous session still has a pending write waits for that write before reading the note.
- AC2: If the pending write never completes, the note still opens after five seconds.
- AC3: A note with a title and no body is not empty.
- AC4: A note with no title, body, attachments, tags or reminders is empty.

**Screens:** SCR-06
**Gherkin:** [`editor.feature`](../features/editor.feature) — `@EDT-14`
