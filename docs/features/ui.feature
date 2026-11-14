@ui
Feature: Drawer and Settings UI
  The drawer names the folder the user picked, not the provider behind it. Folder actions say what they are doing,
  and say the result once. There is no switch that could hide notes which arrived while the app was closed.

  Background:
    Given the user is in the app

  @UI-01 @gap
  Scenario: The drawer shows the folder path
    Given a notes folder is configured at "Notes"
    When the user opens the drawer
    Then the header shows the folder path, up to two lines and ellipsized
    And it shows no provider label such as "External storage"
    But without a folder it shows the prompt to choose one
    # Gap: no automated test covers the drawer text.

  @UI-02 @gap
  Scenario: Folder actions give visible feedback
    Given the user opens Settings then Notes folder
    When the user taps Rescan notes folder or Clear unfinished saves
    Then the action is disabled and shows progress while it runs
    And afterwards the row keeps the result (notes indexed, failure reason, items cleared, or nothing to clear)
    And the result is announced exactly once
    # Partial (AUD-10): the result is shown as both a Snackbar and a Toast.

  @UI-03 @gap
  Scenario: There is no background-sync switch
    Given the app re-reads the folder on resume, hourly, on observer events and on request
    When the user looks at Settings
    Then there is no switch that turns background sync off
    # Gap: the decision is recorded; no automated test asserts the absence of the switch.

  @UI-05 @gap
  Scenario: The drawer header does not overlap system or main UI chrome
    Given a notes folder is configured
    When the user opens the drawer
    Then the header is opaque and the folder path does not overlap the app name or status bar
    And the sync-settings control sits in the vault band, not over the main toolbar icons
    # Gap: no automated test covers drawer header layout.
