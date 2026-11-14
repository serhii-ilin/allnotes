# Notebooks and tags (ORG)

The screens that create, rename, delete and list notebooks and tags, the drawer's notebook entries, and attaching a
tag to a note. What a notebook does to files is FMT-04; what a name may contain is FMT-11.
Use cases: UC-04. Gherkin: [organization.feature](../features/organization.feature).

### ORG-01 A notebook is created from the Manage notebooks screen
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The only create control is the **Create a notebook** action on the Manage notebooks screen. It opens a dialog titled
**New notebook** with an empty name field and the keyboard up. **Save** creates the notebook and closes the dialog; the
new row appears in the list and in the drawer. There is no separate create screen.

**Acceptance criteria**
- AC1: The create action opens a dialog titled "New notebook" with an empty name field.
- AC2: Save with a valid unique name adds the notebook to the list and closes the dialog.
- AC3: The new notebook appears in the drawer's notebook section.

**Screens:** DLG-05, SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-01`

### ORG-02 A notebook name another notebook already uses is refused
**Status:** Implemented · **Scope:** Core · **Tests:** `NotebookDialogViewModelTest.aNameThatOnlyDiffersByCaseFromAnExistingOne_isADuplicate`, `NotebookDialogViewModelTest.renamingANotebookToItsOwnName_isValid`
Before saving, the name is compared with every other notebook by `VaultPaths.collisionKey` (NFC, lowercase). A match is
a duplicate: the dialog shows **Notebook with name %s already exists.** inline and stays open, and nothing is created
or renamed. Renaming a notebook to the name it already has is not a duplicate. This check is the only one the dialog
adds beyond FMT-11 name validity.

**Acceptance criteria**
- AC1: A name whose collision key matches another notebook is a duplicate.
- AC2: Renaming a notebook to its own current name is valid.
- AC3: A duplicate shows the inline message and neither creates nor renames anything.

**Screens:** DLG-05, SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-02`

### ORG-03 A notebook is renamed from its row
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
A notebook is renamed from the long-press sheet on its row (**Rename notebook**). The dialog is titled **Rename
notebook** and its name field is prefilled with the current name. **Save** stores the new name; the list row and the
drawer entry show it. The notebook keeps its notes (FMT-04).

**Acceptance criteria**
- AC1: A long press on a notebook row offers "Rename notebook".
- AC2: The rename dialog is titled "Rename notebook" and prefilled with the current name.
- AC3: Saving a new name updates the list and the drawer entry.

**Screens:** DLG-05, SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-03`

### ORG-04 Deleting a notebook keeps its notes
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultNotebookFolderTest.deletingNotebook_movesNotesToRoot_andNotebookIsNotRecreated`
Notebooks are deleted from the long-press sheet (**Delete**) or from the selection toolbar (**Delete**), without a
confirmation step. The notebook row disappears and the drawer entry goes. Its notes are not deleted: they lose their
notebook and become no-notebook notes, so they appear under **Other** when that grouping is on. The file move is FMT-04;
deletion is also recorded in folder history.

**Acceptance criteria**
- AC1: Delete removes the notebook without asking for confirmation.
- AC2: The notebook's notes are kept and no longer belong to any notebook.
- AC3: The notebook disappears from the list and the drawer and a later sync does not recreate it.

**Screens:** SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-04`

### ORG-05 The Manage notebooks screen lists, opens and selects notebooks
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Every notebook is listed as an icon and its name; tapping a row opens that notebook's note list. A long press opens a
sheet with **Rename notebook**, **Delete** and **Select more**. Selecting switches the toolbar to a contextual one with
the number selected, **Delete** and **Select all**. With no notebooks the screen shows **You have no notebooks.**

**Acceptance criteria**
- AC1: Each notebook is listed with its icon and name; with none, "You have no notebooks." is shown.
- AC2: Tapping a notebook opens its note list.
- AC3: A long press offers Rename notebook, Delete and Select more.
- AC4: Selecting shows the count and offers Delete and Select all.

**Screens:** SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-05`

### ORG-06 Notebooks are sorted by one shared preference
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Sort notebooks by** offers **Title (ascending)**, **Title (descending)**, **Date created (ascending)** and **Date
created (descending)**. The choice is stored with the other preferences, can also be changed in Settings, and drives
both the Manage notebooks list and the drawer. Until it is changed the order is title ascending. "Created" order
follows the notebook's creation order (its row id), not a timestamp.

**Acceptance criteria**
- AC1: The four orders are offered: Title (ascending), Title (descending), Date created (ascending), Date created
  (descending).
- AC2: The chosen order is stored and applied to both the Manage notebooks list and the drawer.
- AC3: Until the user changes it, notebooks are ordered title ascending.
- AC4: "Created" order is the order in which the notebooks were created.

**Screens:** SCR-07
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-06`

### ORG-07 The drawer lists notebooks and opens them
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The drawer's **Notebooks** section starts with **Your Notebooks**, which opens the Manage notebooks screen, then the
optional **Other** entry (ORG-08), then one checkable entry per notebook with the notebook icon. Tapping an entry opens
that notebook's note list. Entries follow the notebook sort; a rename updates the entry, and a deleted notebook's entry
disappears. When a notebook is the current screen, its entry is checked.

**Acceptance criteria**
- AC1: The section shows Your Notebooks first, then one icon entry per notebook.
- AC2: Tapping a notebook entry opens that notebook's note list.
- AC3: A renamed notebook shows its new name and a deleted notebook's entry disappears.
- AC4: Entries follow the notebook sort, and the open notebook's entry is checked.

**Screens:** SCR-13
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-07`

### ORG-08 "Other" stands for notes without a notebook
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Other** is the drawer entry for notes that have no notebook. It is hidden unless Settings → **Group notes which are
not in any notebook** is **Yes**; the default is **No**. Tapping it lists exactly the notes with no notebook, and a note
started from it is created with no notebook. If a real notebook is also named **Other**, the entry is titled **Other
(Default)** so the two can be told apart.

**Acceptance criteria**
- AC1: The entry is hidden while the grouping setting is No and appears when it is Yes.
- AC2: Tapping it lists exactly the notes that have no notebook.
- AC3: A note created from it has no notebook.
- AC4: With a notebook named "Other", the entry reads "Other (Default)".

**Screens:** SCR-02, SCR-13
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-08`

### ORG-09 Tags are created and renamed from the tag list
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Tags are created from the tag list's **New tag** action and renamed from the long-press sheet (**Rename tag**); the
rename dialog is prefilled with the current name. **Save** stores the name as typed: it is not trimmed or validated,
and an empty field becomes **Untitled**. A name already used by another tag is refused with a Toast (**Tag with name %s
already exists.**) and the dialog stays open. The comparison is exact, so **Work** and **work** are different tags.
Renaming a tag to its own name is allowed.

**Acceptance criteria**
- AC1: New tag opens an empty "New tag" dialog; Rename tag opens a prefilled "Rename tag" dialog.
- AC2: Saving a unique name creates or renames the tag and closes the dialog.
- AC3: A tag whose exact name already exists shows the Toast and is not saved; the dialog stays open.
- AC4: Names are stored as typed, an empty name becomes "Untitled", and "Work" and "work" are distinct.
- AC5: Renaming a tag to its own current name is allowed.

**Screens:** DLG-06, SCR-08
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-09`

### ORG-10 The Tags screen lists and deletes tags
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The Tags screen lists every tag with a tag icon and its name; with none it shows **You have no tags.** A long press opens
**Rename tag**, **Delete** and **Select more**; selecting adds a contextual toolbar with the count, **Delete** and
**Select all**. Deleting a tag does not ask for confirmation: the tag leaves the list and every note that carried it,
and those notes are queued for a push so their files drop the tag. The notes themselves are kept.

**Acceptance criteria**
- AC1: Every tag is listed with the tag icon and name; with none, "You have no tags." is shown.
- AC2: A long press offers Rename tag, Delete and Select more.
- AC3: Deleting removes the tag from the list and from every note that carried it, without a confirmation.
- AC4: The notes that carried the tag are kept and queued for a push.

**Screens:** SCR-08
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-10`

### ORG-11 Tags are sorted by one shared preference
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**Sort tags by** offers **Title (ascending)**, **Title (descending)**, **Date created (ascending)** and **Date created
(descending)**. Like the notebook sort it is stored with the other preferences, can also be changed in Settings, and
applies to the whole tag list, including the per-note selection list. Until it is changed the order is title ascending;
"created" order follows the tag's creation order (row id).

**Acceptance criteria**
- AC1: The four orders are offered: Title (ascending), Title (descending), Date created (ascending), Date created
  (descending).
- AC2: The chosen order is stored, shown in Settings and applied to every tag list.
- AC3: Until the user changes it, tags are ordered title ascending.
- AC4: "Created" order is the order in which the tags were created.

**Screens:** SCR-08
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-11`

### ORG-12 A tag is attached to a note from the editor
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
For a note that is not in the bin, the editor menu has **Tags**. It opens the Tags screen in selection mode: each row
shows a checkbox instead of the tag icon, checked when the note carries that tag. Tapping a row adds or removes the tag
immediately; there is no Save. The editor then shows one read-only **# name** chip per tag. A change is written to the
note and queued for the next push, which puts the tag names in the file's front matter (FMT-02).

**Acceptance criteria**
- AC1: The Tags action is available for a note that is not in the bin and opens the tag list in selection mode.
- AC2: Each checkbox is checked exactly when the note carries that tag, and tapping a row toggles it immediately.
- AC3: The editor shows one "# name" chip per tag on the note.
- AC4: A tag change is written to the note and reaches the file's front matter on the next push.

**Screens:** SCR-06, SCR-08
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-12`

### ORG-13 Tapping a tag searches for its name
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
On the Tags screen (opened without a note) tapping a tag opens the Search screen with the tag's name in the query field.
The result is the normal search, not an exact tag filter: a note matches when the text occurs, case-insensitively, in
its title, body or task lines, an attachment description, a tag name or the name of its notebook.

**Acceptance criteria**
- AC1: Tapping a tag opens Search with that tag's name in the query field.
- AC2: Notes carrying a tag whose name contains the text are listed.
- AC3: Notes that contain the text elsewhere (title, body, attachment, notebook name) are also listed.
- AC4: Matching ignores case.

**Screens:** SCR-05
**Gherkin:** [`organization.feature`](../features/organization.feature) — `@ORG-13`
