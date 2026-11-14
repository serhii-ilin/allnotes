@notes-list
Feature: Notes lists
  The list screens show notes as cards and let the user browse, select and organize them. All Notes, Archive, Bin,
  a notebook and Search share the card, layout and sort rules. File and bin semantics live in
  deletion-and-bin.feature.

  Background:
    Given the app has notes in several notebooks
    And some notes are pinned, hidden, archived or binned

  @NTL-01 @gap
  Scenario: All Notes shows every live note
    Given the notes "Home plan" (no notebook) and "Work plan" (notebook "Work")
    And an archived note and a binned note
    When the user opens All Notes
    Then the screen is titled "Notes"
    And "Home plan" and "Work plan" are listed
    And the archived and binned notes are not listed

  @NTL-01 @gap
  Scenario: An empty All Notes waits for the first note
    Given there are no live notes
    When the user opens All Notes
    Then no cards are shown
    And the list says "Your notes will appear here."

  @NTL-02 @test:PreviewSourceTest
  Scenario: A long note is cut to a short card preview
    Given a plain note with 20 lines
    When its card is shown
    Then the preview has at most 15 lines and 800 characters
    And a cut never splits a character

  @NTL-02 @gap
  Scenario: A checklist, attachments, tags and compact preview shape the card
    Given a checklist note with 10 tasks, 5 attachments and 2 tags
    When its card is shown
    Then it shows 8 tasks and "+2 items"
    And it shows 4 attachment previews and "+1"
    And the first tag is shown with "+1" for the second
    When the note is set to compact preview
    Then the body, the tasks and the attachments are hidden
    When it is set to full preview
    Then they are shown again

  @NTL-02 @gap
  Scenario: A card without a title or with Markdown
    Given a note whose title is empty
    And a Markdown note and a plain note
    Then the first card shows no title
    And the Markdown note is previewed as formatted text
    But the plain note is previewed as plain text

  @NTL-03 @gap
  Scenario: Cards never show a date
    Given the Show date created/modified setting is on
    When the user looks at a note card
    Then no created or modified date appears
    When the user opens the note
    Then the editor shows the creation and modification dates
    And turning the setting off hides them in the editor

  @NTL-04 @gap
  Scenario: The list switches between grid and list
    Given the layout is the default grid
    When the user opens All Notes
    Then cards are in two staggered columns
    When the user taps the layout action
    Then cards are in one column
    And the icon offers the grid again

  @NTL-04 @gap
  Scenario: The layout is remembered on the other screens
    Given the user chose list mode on All Notes
    When the user opens Archive, Bin and Search
    Then each of them uses one column
    And none of them shows a layout action

  @NTL-05 @gap
  Scenario: The default sort is the newest modification first
    Given no sort method has been chosen
    When the user opens All Notes
    Then the most recently modified note is first
    And pinned notes stay above unpinned notes

  @NTL-05 @gap
  Scenario Outline: Every sort method orders every list
    Given notes with different titles and dates
    When the user chooses "<method>"
    Then they are ordered by <field> in <direction> order
    And All Notes, a notebook, Archive, Bin and Search use that order

    Examples:
      | method        | field      | direction   |
      | Title A to Z  | title      | ascending   |
      | Title Z to A  | title      | descending  |
      | Oldest first  | created    | ascending   |
      | Newest first  | created    | descending  |
      | Least recent  | modified   | ascending   |
      | Most recent   | modified   | descending  |

  @NTL-06 @gap
  Scenario: A pinned note stays at the top
    Given "Plan" is pinned and "Zebra" was modified more recently
    When the user sorts by modification date
    Then "Plan" is listed before "Zebra"
    And "Plan" carries a pin icon
    When the user unpins "Plan"
    Then it moves to its date position

  @NTL-06 @gap
  Scenario: Pin / Unpin inverts a mixed selection
    Given the selection contains a pinned note and an unpinned note
    When the user chooses "Pin / Unpin"
    Then the pinned note is unpinned
    And the unpinned note is pinned

  @NTL-07 @gap
  Scenario: Hidden notes appear only when asked
    Given "Secret" is hidden
    When the user opens All Notes
    Then "Secret" is not listed
    When the user checks "Show hidden notes"
    Then "Secret" is listed with a hidden icon
    When the user opens Archive
    Then "Show hidden notes" is still checked

  @NTL-07 @gap
  Scenario: Hiding a note removes it and Show restores it
    Given "Plan" is visible
    When the user chooses Hide
    Then "Plan" leaves the list
    When the user checks "Show hidden notes"
    Then "Plan" is listed with a hidden icon
    When the user chooses Show
    Then "Plan" is listed without the icon

  @NTL-08 @gap
  Scenario: Archiving moves a note to the Archive screen
    Given "Plan" is in notebook "Work"
    When the user archives "Plan"
    Then "Plan" leaves All Notes and "Work"
    And it appears in Archive
    And the message says "Archived note"
    When the user unarchives it
    Then it returns to "Work"

  @NTL-08 @gap
  Scenario: The Archive screen is for archived notes only
    Given there are no archived notes
    When the user opens Archive
    Then the list says "Your archived notes will appear here."
    And only search, Show hidden notes and Select All are offered

  @NTL-09 @gap
  Scenario: The Bin screen explains retention
    Given the retention is 7 days
    When the user opens Bin
    Then the title is "Deleted"
    And the empty list mentions 7 days
    And with "never" or "instantly" the empty list says so instead

  @NTL-09 @gap
  Scenario: Emptying the bin asks first
    Given the bin has two notes
    When the user chooses "Empty bin"
    Then a confirmation appears
    When the user chooses No
    Then nothing is deleted
    When the user chooses Yes
    Then both notes are permanently deleted

  @NTL-10 @gap
  Scenario: Selection mode replaces the toolbar
    Given the user long-presses a note and chooses "Select more…"
    Then the card is selected
    And the toolbar says "Selected 1 note"
    When the user taps another card
    Then the toolbar says "Selected 2 notes"
    When the user presses back
    Then the selection is cleared and the screen stays

  @NTL-10 @gap
  Scenario: Selection survives rotation and Select All
    Given the user selected two of five visible notes
    When the screen rotates
    Then both notes are still selected
    When the user chooses Select All
    Then all five visible cards are selected
    But hidden notes that are not shown are not selected
    And the selection stays after the action

  @NTL-10 @gap
  Scenario: Search has no selection mode
    Given the user long-presses a search result
    Then the single-note actions open
    And there is no "Select more…" action

  @NTL-11 @gap
  Scenario: Bulk actions transform the selection
    Given three notes are selected
    When the user chooses Compact preview
    Then all three show only their title, tags and indicators
    When the user chooses Hide
    Then all three leave the list
    And the selection is cleared

  @NTL-11 @gap
  Scenario: Delete follows the retention setting
    Given the retention is 7 days and two notes are selected
    When the user chooses Delete
    Then both notes move to the bin
    And the message says "Moved notes to bin"
    But with the retention set to "instantly" Delete removes them permanently

  @NTL-11 @gap
  Scenario: Move, duplicate and export
    Given two notes in "Work" are selected
    When the user chooses "Move to…"
    Then "No Notebook" and the notebooks are listed
    And "Work" is preselected
    When the user chooses "Home"
    Then both notes belong to "Home"
    When the user duplicates a note with tags and reminders
    Then the copy has the same tags and reminders but is a new note
    When the user exports the selection
    Then the system picker asks where to save a zip

  @NTL-12 @gap
  Scenario: Search matches words from several fields
    Given notes whose title, body, task text, attachment description, tag or own notebook name contains "plan"
    When the user types "plan"
    Then every matching note is listed
    And the match is case-insensitive

  @NTL-12 @gap
  Scenario: Search includes archived, binned and shown hidden notes
    Given "Old plan" is archived, "Gone plan" is binned and "Secret plan" is hidden
    When the user searches "plan"
    Then "Old plan" and "Gone plan" are listed with archive and bin icons
    And "Secret plan" is listed only when Show hidden notes is on

  @NTL-12 @gap
  Scenario: Search with an empty or unmatched query
    Given the search field is empty
    Then the list says "Search results will be displayed here."
    When the user types "zzz"
    Then the list says "No results found."
    And no result can be selected in bulk

  @NTL-13 @gap
  Scenario: A notebook screen shows only its notes
    Given notebook "Work" has one note and "Home" has one note
    When the user opens "Work" from the drawer
    Then the title is "Work"
    And only the "Work" note is listed
    But an archived note in "Work" is not listed
    When the user creates a note there
    Then it belongs to "Work"

  @NTL-13 @gap
  Scenario: The Other entry groups notes without a notebook
    Given "Group notes which are not in any notebook" is No
    When the user opens the drawer
    Then "Other" is not listed
    When the user changes the setting to Yes
    Then "Other" lists notes without a notebook
    And a note created there has no notebook

  @NTL-13 @gap
  Scenario: A deleted notebook closes its screen
    Given notebook "Work" is deleted
    When the user returns to the "Work" screen
    Then the screen closes

  @NTL-14 @gap
  Scenario: The new-note button opens an empty editor
    Given the user is on All Notes
    When the user taps the new-note button
    Then an empty editor opens

  @NTL-14 @test:NoteRepositoryDiscardEmptyNotesTest
  Scenario: A blank note is discarded
    Given a blank note with no title and no content
    And no editor session is open for it
    When empty notes are discarded
    Then the note is deleted

  @NTL-14 @gap
  Scenario: The bottom bar starts a note with content
    Given the user is on All Notes
    When the user chooses "Create a list"
    Then the editor opens in checklist mode
    When the user chooses "Attach files"
    Then a picker offers images, video, audio, PDF and text, and allows several files
    And the editor opens with the chosen files attached
    When the user chooses "Take a photo" or "Record audio"
    Then the editor opens with the photo or recording attached

  @NTL-14 @gap
  Scenario: The entry points disappear during selection
    Given the user selected a note
    Then the new-note button and the bottom bar are hidden

  @NTL-15 @gap
  Scenario: A long press opens the note's action sheet
    Given a live note "Plan" that is not hidden and has Markdown on
    When the user long-presses its card in All Notes
    Then a sheet titled "Plan" offers Move to…, Delete, Hide, Compact preview, Enable screen always on,
      Disable markdown, Duplicate, Export, Share and Select more…
    When the user chooses Select more…
    Then selection mode starts with "Plan" selected

  @NTL-15 @gap
  Scenario: The sheet adapts to archived, binned and searched notes
    Given an archived note, a binned note and a search result
    When the user long-presses each card
    Then the archived note's sheet has no Move to… or Duplicate
    And the binned note's sheet has no Delete, Move to…, Duplicate, screen-always-on or markdown items
    And the search result's sheet has no Select more…
