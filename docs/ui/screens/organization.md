# Notebooks and tags

Back to [screens](../README.md). Behavior: [organization.md](../../requirements/organization.md).

## SCR-07 Manage notebooks
```text
┌────────────────────────────────────────┐
│ ←  Your Notebooks            ⇅   ＋    │  Sort by (ORG-06) · Create a notebook → DLG-05 (ORG-01)
├────────────────────────────────────────┤
│ 📓 Home                                │  tap → SCR-02
│ 📓 Work                                │  long press → sheet: Rename notebook · Delete · Select more (ORG-05)
└────────────────────────────────────────┘
```
Empty: "You have no notebooks." · Selection bar: count · Delete · Select all. Delete never asks for confirmation; the
notes stay and lose their notebook (ORG-04).

## SCR-08 Tags
Opened from the drawer (manage) or from the editor's Tags item (select for one note, ORG-12).
```text
Manage                                  Select for a note
┌──────────────────────────────────┐    ┌──────────────────────────────────┐
│ ←  Tags                 ⇅   ＋   │    │ ←  Tags                 ⇅   ＋   │
├──────────────────────────────────┤    ├──────────────────────────────────┤
│ #  errands                       │    │ ☑  errands                       │  tap toggles the tag on the note at once
│ #  home                          │    │ ☐  home                          │
└──────────────────────────────────┘    └──────────────────────────────────┘
```
- Manage: tap → SCR-05 with the tag name as query (ORG-13); long press → Rename tag · Delete · Select more (ORG-10).
- Empty: "You have no tags." · ＋ → DLG-06 (ORG-09) · Sort by (ORG-11).

## DLG-05 Notebook name
```text
┌──────────────────────────────────────┐
│ New notebook   (or Rename notebook)  │
│ [ Notebook name                   ]  │  prefilled when renaming
│ Notebook name cannot start with a dot.   inline error; the dialog stays open (FMT-11, ORG-02)
│                  [Cancel]   [Save]   │
└──────────────────────────────────────┘
```
Inline errors: "Notebook name cannot be empty." · "…cannot contain \ / : * ? " < > | or control characters." ·
"…cannot start with a dot." · "…cannot end with a dot or a space." · "That name is reserved by the file system.
Choose another." · "Notebook name is too long. Use at most 200 bytes." · "Notebook with name X already exists."

## DLG-06 Tag name
```text
┌──────────────────────────────────────┐
│ New tag   (or Rename tag)            │
│ [ Tag name                        ]  │
│                  [Cancel]   [Save]   │  duplicate name → toast "Tag with name X already exists."
└──────────────────────────────────────┘
```

## DLG-07 Move to notebook
From the editor's notebook chip, the single-note sheet or a selection (NTL-11, EDT-10).
```text
┌──────────────────────────────────────┐
│ Move to…                             │
│ ○ No Notebook                        │
│ ◉ Work                               │  radio list with the current notebook when all notes share one;
│ ○ Home                               │  a plain list otherwise. A tap moves the notes and closes.
│ [Your Notebooks]            [Done!]  │  Your Notebooks → SCR-07
└──────────────────────────────────────┘
```
