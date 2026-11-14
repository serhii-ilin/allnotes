@editor
Feature: Note editor
  The editor lets users type a title and body and switch to preview without losing what they typed.

  Background:
    Given the user is editing a note

  @EDT-01 @test:EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce @gap
  Scenario: Preview shows the title before it is saved to the database (including before first Room load finishes)
    Given the note title in storage is empty
    When the user types "My meeting" as the title
    And the user switches to preview mode
    Then the preview shows "My meeting" as the title
    # Gap: no UI test covers preview binding; ViewModel debounce proves title is queued like body.

  @EDT-02 @gap
  Scenario Outline: The default editor mode decides how a note opens
    Given the default editor mode setting is <setting>
    And a note with a non-empty body
    When the user opens the note
    Then the editor opens showing <mode>
    And the mode switch leads to <other mode>

    Examples:
      | setting | mode        | other mode  |
      | View    | the preview | edit mode   |
      | Edit    | edit mode   | the preview |

  @EDT-02 @gap
  Scenario: A note with an empty body opens for editing whatever the setting
    Given the default editor mode setting is View
    And a note with an empty body
    When the user opens the note
    Then the title and body fields are editable
    And no mode switch is shown

  @EDT-02 @gap
  Scenario Outline: The change-mode control follows its setting
    Given the change-mode setting is "<placement>"
    When the user opens a note with a body
    Then the mode switch is <where>

    Examples:
      | placement | where                   |
      | FAB       | a floating button       |
      | Top bar   | an item in the top menu |

  @EDT-03 @gap
  Scenario: Markdown can be switched off for one note
    Given a note with Markdown on and the body "**bold**"
    When the user picks "Disable markdown"
    Then the body is shown as the literal text "**bold**"
    And the formatting toolbar does not appear while editing
    And the note is saved as a ".txt" file
    But another note with Markdown on keeps its ".md" file

  @EDT-03 @gap
  Scenario: Turning Markdown back on keeps the text
    Given a plain-text note with the body "**bold**"
    When the user picks "Enable markdown"
    Then the body is rendered as bold in the preview
    And the body text is still "**bold**"
    And the note is saved as a ".md" file

  @EDT-03 @gap
  Scenario: A table wider than the preview limit is replaced
    Given a Markdown note with a table of 16 columns
    When the user switches to preview
    Then the table is shown as "Cannot preview table."

  @EDT-04 @gap
  Scenario: The formatting toolbar appears only while editing Markdown
    Given a Markdown note that is not a checklist
    When the body has focus in edit mode
    Then the formatting toolbar is shown
    But it is hidden in preview, in a plain-text note and in a checklist note

  @EDT-04 @gap
  Scenario Outline: A style button wraps the selection in its markers
    Given the user selects "plan"
    When the user taps the <style> button
    Then the body contains "<result>"
    And the cursor sits after the opening marker

    Examples:
      | style         | result    |
      | bold          | **plan**  |
      | italics       | _plan_    |
      | strikethrough | ~~plan~~  |
      | code          | `plan`    |
      | highlight     | ==plan==  |

  @EDT-04 @gap
  Scenario: A style button with no selection inserts both markers
    Given the body is empty and the cursor is in it
    When the user taps the bold button
    Then the body is "****"
    And the cursor sits between the two pairs of markers

  @EDT-04 @gap
  Scenario: Heading walks a line through six levels and back
    Given a line with the text "Plan"
    When the user taps heading
    Then the line is "# Plan"
    When the user taps heading five more times
    Then the line is "###### Plan"
    When the user taps heading again
    Then the line is "Plan"

  @EDT-04 @gap
  Scenario: Quote adds one level per press
    Given a line with the text "Plan"
    When the user taps quote
    Then the line is "> Plan"
    When the user taps quote again
    Then the line is ">> Plan"

  @EDT-04 @gap
  Scenario: Toggle checkbox line cycles a line
    Given a line with the text "Plan"
    When the user taps toggle checkbox line
    Then the line is an unchecked checklist item "Plan"
    When the user taps toggle checkbox line again
    Then the item is checked
    When the user taps toggle checkbox line once more
    Then the item is unchecked again

  @EDT-04 @gap
  Scenario: Undo and the scroll buttons
    Given the body is longer than the screen and has been edited
    When the user opens the toolbar
    Then undo is enabled and redo is disabled
    When the user taps scroll to bottom
    Then the view scrolls down and the cursor is at the end of the body
    When the user taps scroll to top
    Then the view scrolls up and the cursor is at the start of the body

  @EDT-05 @gap
  Scenario: The link dialog replaces the selection with a link
    Given the user selects "meeting notes"
    When the user inserts the link "https://example.com"
    Then the body contains "[meeting notes](https://example.com)"
    And the selected text is replaced, not duplicated

  @EDT-05 @gap
  Scenario: The image dialog inserts an image
    Given the user selects "damage"
    When the user inserts the image "photos/shelf.jpg" described as "damage"
    Then the body embeds the image "photos/shelf.jpg" with the description "damage"
    And the selected text is replaced, not duplicated

  @EDT-05 @gap
  Scenario: The table dialog inserts a grid
    Given the table dialog is open
    When the user enters 2 rows and 3 columns
    Then the body contains a pipe table 3 columns wide with a content row and a separator row

  @EDT-05 @gap
  Scenario: The table dialog rejects empty or non-numeric input
    Given the table dialog is open
    When the user enters "two" as the number of rows
    Then an error message is shown
    And the dialog stays open and nothing is inserted

  @EDT-05 @gap
  Scenario: With no selection the Markdown is inserted at the cursor
    Given the body is "Notes" and the cursor is at its end
    When the user inserts the link "https://example.com" labelled "site"
    Then the body is "Notes[site](https://example.com)"

  @EDT-06 @test:TaskListMarkwonBindingsTest.editorChecked_strikethroughWithVaultContext @test:TaskListMarkwonBindingsTest.editorUnchecked_usesPlainTextNotMarkwonPreview
  Scenario: A checked item is struck through and an unchecked one is plain text
    Given a checklist note with the checked item "Fix shelf" and the unchecked item "Buy paint"
    When the editor shows the items
    Then the checked item is rendered struck through
    And the unchecked item is shown as plain text without Markdown rendering

  @EDT-06 @gap
  Scenario: Items can be ticked, added, reordered and deleted
    Given a checklist note with one unchecked item "Buy paint"
    When the user ticks it
    Then the item is saved as checked
    When the user taps the "+" button
    Then an empty item appears at the top
    When the user drags an item and swipes another one away
    Then the new order and the deletion are saved

  @EDT-06 @gap
  Scenario: Preview keeps the checkboxes and hides the editing controls
    Given a checklist note in preview mode
    When the user looks at the list
    Then no item text field and no drag handle is shown
    And ticking a checkbox still saves the change

  @EDT-07 @gap
  Scenario: A text note becomes a checklist
    Given a note with the body lines "Plan" and "Buy paint"
    When the user picks "Convert to list"
    Then the note has the unchecked items "Plan" and "Buy paint"
    And the body is empty

  @EDT-07 @gap
  Scenario: A checklist becomes a text note
    Given a checklist note with the checked item "Plan" and the unchecked item "Buy paint"
    When the user picks "Convert to note"
    Then the body has the lines "Plan" and "Buy paint"
    And the item list is empty
    And no checkbox marks are written
    And the checked state is not carried into the body

  @EDT-08 @gap
  Scenario: Uncheck all items clears every check
    Given a checklist note with two checked items
    When the user picks "Uncheck all items"
    Then both items are unchecked and the list is saved

  @EDT-08 @gap
  Scenario: Remove all checked tasks deletes only the checked items
    Given a checklist note with "Plan" checked and "Buy paint" unchecked
    When the user picks "Remove all checked tasks"
    Then the list holds only "Buy paint"

  @EDT-08 @gap
  Scenario Outline: Ticking an item moves it to the end when the setting is on
    Given the "Move checked items" setting is <setting>
    And a checklist note with "Plan" unchecked and "Buy paint" checked, in that order
    When the user ticks "Plan"
    Then the order becomes <order>

    Examples:
      | setting | order                    |
      | on      | "Buy paint", "Plan"      |
      | off     | "Plan", "Buy paint"      |

  @EDT-08 @gap
  Scenario: Unticking moves an item up, never down
    Given the "Move checked items" setting is on
    And a checklist note with "A" unchecked and "B" and "C" checked, in that order
    When the user unticks "C"
    Then the order becomes "A", "C", "B"

  @EDT-09 @gap
  Scenario: The note colour is chosen from the editor menu
    Given a note with the default colour
    When the user picks "Change colour"
    Then twelve colours are listed with "Default" selected
    When the user picks "Blue"
    Then the editor background and toolbars turn blue
    And the note is saved with the new colour without leaving the editor

  @EDT-09 @gap
  Scenario: Screen always on follows the note
    Given a note whose screen-always-on flag is off
    When the user checks "Screen always on"
    Then the window keeps the screen on
    And the note is saved with the flag on
    When the user closes the editor
    Then the keep-screen-on flag is cleared

  @EDT-10 @gap
  Scenario: Pin and hide stay in the editor
    Given a note that is neither pinned nor hidden
    When the user pins it
    Then the menu shows "Unpin" with the filled pin icon
    And the note is saved as pinned
    When the user hides it
    Then the note is saved as hidden and the editor stays open

  @EDT-10 @gap
  Scenario: Archive leaves the editor
    Given a note open in the editor
    When the user archives it
    Then the note is saved as archived and the editor closes

  @EDT-10 @gap
  Scenario: Binning leaves the editor
    Given a note open in the editor
    When the user bins it with a deletion delay other than "Instantly"
    Then the note is moved to the bin and the editor closes

  @EDT-10 @gap
  Scenario: A binned note is read-only and can be restored
    Given the user opens a note from the bin
    When the editor loads
    Then the menu offers "Restore" and "Delete permanently"
    And a snackbar explains that a binned note cannot be edited
    And the note cannot be edited
    When the user taps Restore
    Then the note leaves the bin and the editor closes

  @EDT-11 @gap
  Scenario: Export writes a zip backup of the open note
    Given a note is open
    When the user picks "Export"
    Then the system asks for a zip destination named "allnotes_backup_<epoch>.zip"
    And the written backup contains only that note

  @EDT-11 @gap
  Scenario: Share sends the note text
    Given a note titled "Plan" with the body "Buy paint"
    When the user picks "Share"
    Then the chooser offers a text send with the title "Plan" and the text "Buy paint"

  @EDT-11 @gap
  Scenario: A checklist is shared with check marks
    Given a checklist note with "Plan" checked and "Buy paint" unchecked
    When the user shares it
    Then the shared text has the lines "☑ Plan" and "☐ Buy paint"

  @EDT-11 @gap
  Scenario: Attachments are shared with the note
    Given a note with one image attachment
    When the user shares it
    Then the intent sends the text and the image together with read permission

  @EDT-12 @gap
  Scenario: A chosen font size applies to the whole editor
    Given the editor font size is set to 25
    When the user opens a note
    Then the title field, the body field and both previews use 25 sp

  @EDT-12 @gap
  Scenario: Default keeps the theme size
    Given the editor font size is set to Default
    When the user opens a note
    Then the editor keeps the theme's title and body sizes

  @EDT-12 @gap
  Scenario: Checklist rows follow the font size
    Given the editor font size is set to 40
    When the user opens a checklist note
    Then its items use 40 sp and the checkboxes are not enlarged

  @EDT-13 @test:EditorViewModelSaveTest.typingBurst_persistsOnlyLastContentOnceAfterDebounce @test:EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce @test:EditorViewModelSaveTest.vaultPush_happensOnceAfterLongerIdleWindow
  Scenario: A typing burst is written once, after the debounce
    Given the user types "a", "ab" and "abc" with 100 ms between the keystrokes
    When 299 ms pass after the last keystroke
    Then nothing has been written
    When another millisecond passes
    Then the database holds "abc" exactly once
    And the vault push follows after 1.5 s of quiet

  @EDT-13 @test:EditorViewModelSaveTest.taskListBurst_producesSingleVaultPush
  Scenario: A checklist burst is written once, after the debounce
    Given the user edits a checklist item three times with 100 ms between the edits
    When 1.5 s pass after the last edit
    Then the vault holds the last item text
    And a longer quiet period writes nothing more

  @EDT-13 @test:EditorViewModelSaveTest.flush_persistsPendingContentImmediately
  Scenario: Leaving the editor writes the pending text at once
    Given the user typed text that is still waiting for the debounce
    When the editor is paused
    Then the database gets the text first and the vault push second
    And the timers are cancelled so nothing is written again

  @EDT-13 @test:EditorViewModelSaveTest.mutations_applyInCallOrder @test:EditorViewModelSaveTest.externalChangeToNotebook_isNotRevertedByLaterContentSave
  Scenario: Saves run in order and never revert other changes
    Given the user types "a", changes the colour, then types "ab"
    When the saves run
    Then the saved note has the content "ab" and the new colour
    And a notebook move made elsewhere survives the next text save

  @EDT-13 @gap
  Scenario: Stored text is trimmed
    Given the user types a title with a trailing space and a body with a trailing blank line
    When the note is saved
    Then the stored title and body are trimmed

  @EDT-14 @test:EditorViewModelSaveTest.initialize_waitsForPreviousSessionFlush
  Scenario: Opening a note waits for the previous session's pending write
    Given the previous editor of the note still has a pending write
    When the user opens the note again
    Then the editor reads the note only after that write completes

  @EDT-14 @test:EditorViewModelSaveTest.initialize_stopsWaitingForAStuckPreviousSession
  Scenario: A stuck pending write does not block opening
    Given the previous editor's pending marker is never cleared
    When the user opens the note
    Then the editor still reads the note after five seconds

  @EDT-14 @test:EditorTitleOnPauseRegressionTest.noteWithTitleOnly_isNotEmpty @test:NoteUnitTests.defaultNoteShouldBeEmpty
  Scenario: A titled draft is not empty
    Given a note with only the title "Draft title"
    Then it is not empty
    And a note with no title, body, attachments, tags or reminders is empty
