# Editor

Back to [screens](../README.md). Behavior: [editor.md](../../requirements/editor.md); attachments
[media.md](../../requirements/media.md); reminders [reminders.md](../../requirements/reminders.md). The mode state
machine is in [states.puml](../states.puml).

## SCR-06 Editor

### Text note, preview mode (default for an existing note)
```text
┌────────────────────────────────────────┐
│ ←  📓 Work ▾        ✎  📌  🔔  ⋮       │  notebook chip (tap → DLG-07) · mode · Pin · Reminders (filled bell when any)
├────────────────────────────────────────┤
│ [img] [♪ ] [📄]                        │  attachment tiles (MED-04); tap opens (MED-05), long press → DLG-14
│  photo  clip  spec.pdf                 │  descriptions under the tiles
│                                        │
│ Shopping                               │  title, rendered
│ Milk, **eggs** → rendered Markdown     │  body rendered as Markdown (or plain text when Markdown is off)
│                                        │
│ Created at Nov 13 2026, 10:42          │  date line (SET-07; hidden when Show date = No)
│ Last modified at Nov 13 2026, 11:03    │
│ #home  #errands                        │  read-only tag chips (ORG-12)
│                                 ( ✎ )  │  FAB: switch to edit (EDT-02); or a top-bar item (SET-08)
└────────────────────────────────────────┘
```
Overflow (text note, live): Convert to list · Archive · Delete · Hide · Screen always on (checkable) · Change color ·
Enable/Disable markdown · Attach files · Record audio · Take a photo · Tags · Export · Share.

### Text note, edit mode
```text
┌────────────────────────────────────────┐
│ ←  📓 Work ▾        👁  📌  🔔  ⋮       │
├────────────────────────────────────────┤
│ [ Title                             ]  │  hint "Title"
│ [ Take a note!                      ]  │  body hint; live Markdown styling (EDT-03)
│ [ Milk, **eggs**|                   ]  │
│                                        │
├────────────────────────────────────────┤
│ B  I  S̶  H  ❝  ▮  </>  🔗  ▦  🖼  ☑  ⤒  ⤓  ↶  ↷ │  formatting bar (EDT-04): only for Markdown text notes while
└────────────────────────────────────────┘     the body has focus; scrolls horizontally
```
Buttons: bold · italics · strikethrough · heading · quote · highlight (`==`) · code · link (DLG-10) · table (DLG-12) ·
image (DLG-11) · toggle checkbox line · scroll to top · scroll to bottom · undo · redo.

### Checklist note
```text
┌────────────────────────────────────────┐
│ ←  📓 Home ▾     ✎  📌  🔔  ☐̸  🗑✓  ⋮  │  Uncheck all items · Remove all checked tasks (EDT-08)
├────────────────────────────────────────┤
│ [ Groceries                         ]  │
│  ＋ Add item                            │  edit mode only: adds at the top (EDT-06)
│ ≡ ☐ [ milk                    ]        │  drag handle (edit mode), checkbox, text
│ ≡ ☐ [ bread                   ]        │  swipe to delete (edit mode)
│ ≡ ☑ ~~eggs~~                           │  checked: struck through; moved to the end when "Move checked items" is on
└────────────────────────────────────────┘
```
Overflow: Convert to note (instead of Convert to list); no formatting bar.

### Binned note (read-only)
```text
┌────────────────────────────────────────┐
│ ←                         ♻  ⋮         │  Restore · overflow: Delete permanently
│ Shopping                               │
│ Milk …                                 │
├────────────────────────────────────────┤
│ Notes inside the bin cannot be edited.   [RESTORE] │  snackbar (EDT-10)
└────────────────────────────────────────┘
```

### Empty new note
Opens in edit mode with the keyboard up and no mode switch (EDT-02). Leaving it empty discards it with
"Empty note discarded" (NTL-14, EDT-14).

## DLG-08 Reminders
```text
Sheet (REM-06)                         Dialog (REM-07, REM-08)
┌──────────────────────────────┐       ┌──────────────────────────────┐
│ Reminders                    │       │ New reminder   (or Reminder) │
│ 🔔 Call Anna (Nov 14, 09:30) │──tap──▶ [ Reminder name           ]  │
│ 🔔 Pay rent (Dec 1, 08:00)   │       │ [ Nov 14 2026 ]  [ 09:30 ]   │  Set date / Set time → system pickers
│ ＋ New reminder               │──tap──▶                              │
└──────────────────────────────┘       │        [Delete]     [Save]   │  Delete only when editing
                                       └──────────────────────────────┘
```
- Saving a past time shows "Cannot set a reminder for a past date" and saves nothing (REM-07).
- Before the dialog, Android 13+ asks for the notification permission (REM-14) and Android 12+ may require the
  exact-alarm permission (REM-10).

## DLG-09 Note colour
```text
┌──────────────────────────────┐
│ Change color                 │
│ ◉ Default                    │  single choice, current colour selected (EDT-09)
│ ○ Red    ○ Orange  ○ Yellow  │
│ ○ Green  ○ Teal    ○ Cyan    │
│ ○ Blue   ○ Purple  ○ Pink    │
│ ○ Brown  ○ Gray              │
│                     [Done!]  │  a pick applies at once; Done! closes
└──────────────────────────────┘
```

## DLG-10, DLG-11, DLG-12 Insert link, image, table
```text
┌──────────────────────────┐ ┌──────────────────────────┐ ┌──────────────────────────┐
│ Insert link              │ │ Insert image             │ │ Insert table             │
│ [ Text               ]   │ │ [ Description        ]   │ │ [ Number of columns ]    │  digits only
│ [ URL                ]   │ │ [ Image path         ]   │ │ [ Number of rows    ]    │
│      [Cancel] [Insert]   │ │      [Cancel] [Insert]   │ │      [Cancel] [Insert]   │
└──────────────────────────┘ └──────────────────────────┘ └──────────────────────────┘
```
The selected text seeds Text / Description (EDT-05). Known defects: the image alt text and the table row count
(2026-11-12 review).
