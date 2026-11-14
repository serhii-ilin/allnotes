@organization
Feature: Notebooks and tags
  Notes are grouped into notebooks and labelled with tags. These screens create, rename and delete both, show them in
  the Manage notebooks screen and the drawer, attach a tag to a note and let a tag lead to the notes carrying it.

  Background:
    Given the notes folder is "Notes"

  @ORG-01 @gap
  Scenario: A notebook is created from the Manage notebooks screen
    Given the user is on the Manage notebooks screen
    When the user taps "Create a notebook"
    Then a dialog titled "New notebook" appears with an empty name field
    When the user types "Work" and taps "Save"
    Then the dialog closes
    And "Work" is listed on the screen and in the drawer
    # Gap: no automated test drives the notebook dialog or asserts the new row.

  @ORG-02 @test:NotebookDialogViewModelTest.aNameThatOnlyDiffersByCaseFromAnExistingOne_isADuplicate @test:NotebookDialogViewModelTest.renamingANotebookToItsOwnName_isValid
  Scenario: A notebook name another notebook already uses is a duplicate
    Given the notebook "work" exists
    When the user types "Work" in the notebook dialog
    Then the name is a duplicate by collision key
    And renaming that notebook to its own name is not a duplicate

  @ORG-02 @gap
  Scenario: A duplicate notebook name is refused inline
    Given the notebook "Work" exists
    When the user tries to save another notebook named "Work"
    Then "Notebook with name Work already exists." is shown below the name field
    And the dialog stays open and no notebook is created
    # Gap: the dialog's inline error and the fact that it stays open are not asserted by a test.

  @ORG-03 @gap
  Scenario: A notebook is renamed from its row
    Given the notebooks "Work" and "Home" exist
    When the user long-presses "Work"
    Then the sheet offers "Rename notebook"
    When the user chooses it
    Then the dialog is titled "Rename notebook" with "Work" prefilled
    When the user saves "Job"
    Then the list and the drawer entry show "Job"
    # Gap: no automated test drives the rename dialog or the drawer entry.

  @ORG-04 @test:VaultNotebookFolderTest.deletingNotebook_movesNotesToRoot_andNotebookIsNotRecreated
  Scenario: Deleting a notebook keeps its notes
    Given the notebook "Work" has the note "A"
    When the notebook is deleted
    Then "A" is kept and belongs to no notebook
    And the notebook is gone and a later sync does not recreate it

  @ORG-04 @gap
  Scenario: Deleting a notebook asks for no confirmation
    Given the user long-presses the notebook "Work"
    When the user chooses "Delete"
    Then "Work" is removed immediately without a confirmation dialog
    # Gap: the sheet action and the absence of a prompt are not asserted by a test.

  @ORG-05 @gap
  Scenario: The Manage notebooks screen lists and opens notebooks
    Given the notebooks "Work" and "Home" exist
    When the user opens "Your Notebooks"
    Then each notebook is listed with its icon and name
    When the user taps "Work"
    Then the note list of "Work" opens
    And a long press offers "Rename notebook", "Delete" and "Select more"
    And with no notebooks the screen shows "You have no notebooks."
    # Gap: no automated test covers the notebook list screen.

  @ORG-06 @gap
  Scenario: Notebooks are sorted by one shared preference
    Given the notebooks "Home" (older) and "Work" (newer)
    When the notebook sort is "Title (ascending)"
    Then "Home" is listed before "Work"
    When the sort is "Date created (descending)"
    Then "Work" is listed before "Home"
    And the Manage notebooks screen and the drawer use the same order
    # Gap: neither sort option nor its persistence is asserted by a test.

  @ORG-07 @gap
  Scenario: The drawer lists the notebooks
    Given the notebooks "Work" and "Home" exist
    When the user opens the drawer
    Then "Your Notebooks" is the first entry of the notebook section
    And the notebook entries follow, each with the notebook icon
    When the user taps "Home"
    Then the note list of "Home" opens and its drawer entry is checked
    And a renamed notebook shows its new name and a deleted one is gone
    # Gap: seed the drawer by hand; no automated test covers it.

  @ORG-08 @gap
  Scenario: "Other" holds the notes without a notebook
    Given "Group notes which are not in any notebook" is "No"
    When the user opens the drawer
    Then there is no "Other" entry
    When the user sets the setting to "Yes"
    Then "Other" appears in the drawer
    When the user opens "Other" and creates a note there
    Then the note has no notebook
    And a real notebook named "Other" makes the entry read "Other (Default)"
    # Gap: the drawer's default-notebook entry is not covered by a test.

  @ORG-09 @gap
  Scenario: Tags are created and renamed from the tag list
    Given the user is on the Tags screen
    When the user taps "New tag"
    Then a dialog titled "New tag" appears with an empty name field
    When the user saves "work"
    Then the tag "work" is listed
    When the user long-presses "work" and chooses "Rename tag"
    Then the dialog is titled "Rename tag" with "work" prefilled
    And saving "job" renames the tag
    # Gap: no automated test drives the tag dialog.

  @ORG-09 @gap
  Scenario: A tag name is stored as typed
    Given the user saves a tag with an empty name
    Then the tag is called "Untitled"
    And a tag name is not trimmed
    And "Work" and "work" are two different tags
    And saving a tag whose exact name already exists shows a Toast and keeps the dialog open
    # Gap: no automated test covers tag names, the duplicate check or the empty-name fallback.

  @ORG-10 @gap
  Scenario: The Tags screen lists and deletes tags
    Given the tags "work" and "home" exist and a note carries "work"
    When the user opens "Tags"
    Then both tags are listed with the tag icon
    And a long press offers "Rename tag", "Delete" and "Select more"
    When the user deletes "work"
    Then it disappears from the list and from the note
    And the note is kept and queued for a push
    But no confirmation is asked
    # Gap: no automated test covers the Tags screen or tag deletion from it.

  @ORG-11 @gap
  Scenario: Tags are sorted by one shared preference
    Given the tags "work" (older) and "home" (newer)
    When the tag sort is "Title (descending)"
    Then "work" is listed before "home"
    When the sort is "Date created (descending)"
    Then "home" is listed before "work"
    And the choice is also shown in Settings
    # Gap: neither sort option nor its persistence is asserted by a test.

  @ORG-12 @gap
  Scenario: A tag is attached to a note from the editor
    Given the note "Plan" has no tags and the tag "work" exists
    When the user opens "Plan", opens the menu and chooses "Tags"
    Then every tag has a checkbox, unchecked
    When the user taps "work"
    Then its checkbox is checked immediately
    And the editor shows the chip "# work"
    And the tag is written to the note's front matter on the next push
    When the user taps "work" again
    Then the tag is removed from the note and the chip disappears
    # Gap: no automated test covers attaching a tag from the editor.

  @ORG-13 @gap
  Scenario: Tapping a tag searches for its name
    Given the tags "work" and "homework" exist
    And the note "Chores" is tagged "work" and the note "Essay" is tagged "homework"
    And a note "Weekend" whose body says "work in the garden"
    And a notebook "Work"
    When the user taps the tag "work" on the Tags screen
    Then the Search screen opens with "work" in the query field
    And it lists the notes tagged "work" and "homework"
    And the note that merely contains "work"
    And the notes in the notebook "Work"
    # Gap: no automated test covers the tag-to-search navigation or the search match rules.
