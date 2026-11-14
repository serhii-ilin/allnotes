@settings
Feature: Settings
  The Settings screen holds this device's own choices: appearance, sort orders, editor and list defaults, the bin and
  folder-history retention, and the backup rows. Nothing here is written into the notes folder, so two devices that
  share a folder can look and behave differently.

  Background:
    Given the app is open on Settings

  @SET-01 @test:PreferenceRepositoryLegacyKeysTest.legacySyncKeys_doNotBreakDefaults
  Scenario: Unset or unknown stored settings read as their defaults
    Given an install whose stored preferences hold only keys this version does not know
    When the preferences are read
    Then every setting reads as its default

  @SET-01 @gap
  Scenario: Settings stay out of the notes folder
    Given the same notes folder is used on two devices
    When a setting is changed on one device
    Then the folder gains no settings file
    And the other device's settings are unchanged
    # Gap: no automated test asserts that preferences stay out of the vault and out of a zip backup.

  @SET-01 @test:ThemeCacheTest.writeThenRead_survivesNewInstance
  Scenario: The theme is ready on the next cold start
    Given the user chose the Red scheme on a light theme
    When a new theme cache instance reads the file at the next start
    Then it returns the same scheme, so the default theme is never shown first

  @SET-02 @test:ThemeRecreationTest
  Scenario: A theme change is applied exactly once
    Given a note list open in the light theme
    When the user chooses Dark
    Then AppCompat handles the night switch and the app does not recreate the screen a second time
    And when the new mode already matches the current night state, the app recreates the screen once

  @SET-02 @test:ThemeCacheTest.writeThenRead_returnsStoredValues
  Scenario: The theme mode and dark theme are remembered
    Given the user chooses Dark with the Black dark theme
    When the values are stored and read again
    Then Dark and Black come back to be applied at the next start

  @SET-02 @gap
  Scenario: Black belongs to the dark theme
    Given the user chooses the Black dark theme
    When the resolved theme is light
    Then the black palette is not applied
    But with a dark or follow-system-in-dark theme, Black replaces Standard
    # Gap: no automated test asserts when the Black style is applied.

  @SET-03 @test:ThemeRecreationTest
  Scenario: A new colour scheme shows at once
    Given the user is on Blue with the app open
    When the user chooses Green
    Then the screen is recreated so the new colours show without a restart

  @SET-03 @test:ThemeCacheTest.writeThenRead_returnsStoredValues
  Scenario: The colour scheme survives a restart
    Given the user chose Green
    When the app starts again
    Then Green is applied from the first frame

  @SET-03 @gap
  Scenario: Follow system is offered only where the system colours exist
    Given a device running Android 12 or newer
    When the user opens the colour scheme dialog
    Then Follow system is listed last
    But on Android 11 and older it is not listed at all
    # Gap: no automated test asserts the picker's filtered list.

  @SET-04 @gap
  Scenario Outline: The note lists use the chosen layout
    Given Layout mode is "<mode>"
    When the user opens All notes, a notebook, Archive, Bin or Search
    Then the notes are shown in <columns>
    # Gap: no automated test asserts the layout manager per mode.

    Examples:
      | mode  | columns                 |
      | Grid  | two staggered columns   |
      | List  | one column              |

  @SET-04 @gap
  Scenario: The Settings row and the toolbar button are one choice
    Given Layout mode is Grid
    When the user presses the list's layout button
    Then the notes switch to List and Settings shows List
    # Gap: no automated test asserts that both controls write one value.

  @SET-05 @gap
  Scenario Outline: A list starts from its default sort
    Given the user has never changed "<setting>"
    When the list is shown
    Then it is ordered <order>
    # Gap: no automated test asserts the default sort of each list.

    Examples:
      | setting           | order                        |
      | Sort by           | by date modified, newest first |
      | Sort tags by      | by title, A to Z             |
      | Sort notebooks by | by title, A to Z             |

  @SET-05 @gap
  Scenario: The notes sort applies to every note list
    Given Sort by is "Title (ascending)"
    When the user opens All notes, a notebook, Archive, Bin and Search
    Then each list is ordered by title, A to Z
    And pinned notes stay first in every order
    # Gap: no automated test asserts the sort preference reaching the list queries.

  @SET-05 @gap
  Scenario: A sort set in Settings and in a list menu is the same value
    Given the user chooses "Date created (descending)" in the All notes menu
    When the user opens Settings
    Then Sort by shows "Date created (descending)"
    # Gap: no automated test asserts that both entry points share one preference.

  @SET-06 @gap
  Scenario Outline: A note opens in the default editor mode
    Given Default editor mode is "<mode>"
    When the user opens an existing note with text
    Then the note opens in <state>
    But an empty note still opens for editing
    And a binned note is read-only
    # Gap: no automated test asserts the initial editor mode.

    Examples:
      | mode      | state    |
      | View mode | view     |
      | Edit mode | editing  |

  @SET-06 @gap
  Scenario: The font size reaches the note text and the checklist
    Given Text editor/view font size is "25"
    When the user opens a note and a checklist note
    Then the title, the body and the checklist items are drawn at 25
    But with "Default" they keep the theme's text size
    # Gap: no automated test asserts the applied font size.

  @SET-06 @gap
  Scenario Outline: Checking an item moves it only when the setting says so
    Given Move (un)checked items in the lists is "<choice>"
    When the user checks an item in a checklist note
    Then <result>
    # Gap: no automated test asserts checklist reordering.

    Examples:
      | choice | result                                                        |
      | Yes    | the item moves to the end, and unchecking moves it back up     |
      | No     | the item stays where it is                                     |

  @SET-07 @gap
  Scenario Outline: The date line follows Show date
    Given Show date created/modified is "<choice>"
    When the user opens a note
    Then the "Created at … / Last modified at …" line is <state>
    But no note list shows a date in either case
    # Gap: no automated test asserts the date line.

    Examples:
      | choice | state  |
      | Yes    | shown  |
      | No     | hidden |

  @SET-07 @gap
  Scenario Outline: The date format decides how the date line looks
    Given Date format is "<format>"
    When the user opens a note whose dates fall on 12 November 2026
    Then the line shows "<example>"
    And the date format row shows today's date in the same pattern
    # Gap: no automated test asserts the date patterns.

    Examples:
      | format         | example            |
      | MMMM d yyyy    | November 12 2026   |
      | d MMMM yyyy    | 12 November 2026   |
      | MM/d/yyyy      | 11/12/2026         |
      | d/MM/yyyy      | 12/11/2026         |
      | yyyy-MM-dd     | 2026-11-12         |

  @SET-07 @gap
  Scenario Outline: The time format decides how the time line looks
    Given Time format is "<format>"
    When the user opens a note at 14:05
    Then the line shows "<example>"
    # Gap: no automated test asserts the time patterns.

    Examples:
      | format  | example |
      | HH:mm   | 14:05   |
      | hh:mm a | 02:05 PM |

  @SET-07 @gap
  Scenario: The reminder dialog uses the same date and time formats
    Given Date format is "yyyy-MM-dd" and Time format is "HH:mm"
    When the user opens the reminder dialog
    Then its date and time buttons show the date and time in those patterns
    # Gap: no automated test asserts the reminder dialog's formatting.

  @SET-08 @gap
  Scenario Outline: The view/edit toggle sits where the user chose
    Given Edit/View note button position is "<choice>"
    When the user opens a note
    Then the toggle is shown <place>
    But there is no second toggle anywhere else
    # Gap: no automated test asserts the toggle's position.

    Examples:
      | choice          | place                 |
      | Floating button | over the note         |
      | Top bar         | in the note's toolbar |

  @SET-08 @gap
  Scenario: The toggle is absent on binned and empty notes
    Given the user opens a binned note or an empty note
    When the note is shown
    Then no view/edit toggle appears, whether it is set to Floating button or Top bar
    # Gap: no automated test asserts the hidden toggle.

  @SET-09 @gap
  Scenario Outline: Media opens where the user chose
    Given Open media in is "<choice>"
    When the user taps an image, an audio recording or a video
    Then it opens <where>
    # Gap: no automated test asserts the media intent.

    Examples:
      | choice          | where                     |
      | Internal player | in the app's media screen |
      | External player | in another app            |

  @SET-09 @gap
  Scenario: A generic attachment always goes to another app
    Given Open media in is "Internal player"
    When the user taps a generic attachment such as a PDF
    Then it opens in another app
    # Gap: no automated test asserts the generic-attachment path.

  @SET-10 @gap
  Scenario Outline: Notes without a notebook get a drawer entry only on request
    Given Group notes which are not in any notebook is "<choice>"
    When the user opens the drawer
    Then the entry "Other" is <state>
    But notes without a notebook are under All notes either way
    # Gap: no automated test asserts the drawer entry.

    Examples:
      | choice | state   |
      | Yes    | shown   |
      | No     | missing |

  @SET-10 @gap
  Scenario: The entry is marked Default when a real notebook has its name
    Given a notebook named "Other" exists
    And Group notes which are not in any notebook is "Yes"
    When the user opens the drawer
    Then the entry for notes without a notebook reads "Other (Default)"
    # Gap: no automated test asserts the drawer title.

  @SET-11 @gap
  Scenario Outline: The bin keeps a note for the chosen period
    Given Delete notes in bin is "<choice>"
    When a note has been in the bin for "<age>"
    Then at the next periodic clean it is <result>
    # Gap: no automated test asserts the bin cleaner against the setting.

    Examples:
      | choice        | age     | result                          |
      | After 7 days  | 8 days  | permanently deleted with its assets |
      | After 14 days | 10 days | kept                            |
      | After 30 days | 31 days | permanently deleted with its assets |
      | Never         | 1 year  | kept until the user empties the bin |
      | Instantly     | 0       | never enters the bin at all     |

  @SET-11 @gap
  Scenario: The bin screen names the active choice
    Given Delete notes in bin is "Never"
    When the user opens the Bin
    Then the screen says notes are never deleted automatically
    But with "Instantly" it says notes are deleted instantly
    # Gap: no automated test asserts the bin's empty-state text.

  @SET-12 @test:EachPhoneRemovesItsOwnOldHistoryTest.theRetentionSetting_defaultsToThreeMonths
  Scenario: Folder history offers the documented periods
    Given the user opens Keep folder history
    When the choices are listed
    Then they are 1 month, 3 months, 6 months, 1 year and Forever
    And 3 months is the default

  @SET-12 @test:EachPhoneRemovesItsOwnOldHistoryTest.threeMonths_deletesJuly_andKeepsAugustAndNovember @test:EachPhoneRemovesItsOwnOldHistoryTest.anotherDevicesOldLog_isNeverDeleted @test:EachPhoneRemovesItsOwnOldHistoryTest.forever_keepsEveryFile
  Scenario: Each device prunes only its own history
    Given two devices keep folder history with 3 months
    When one device prunes
    Then its own month older than the period goes and the newer months stay
    And the other device's files are never deleted
    But with Forever nothing is deleted

  @SET-12 @gap
  Scenario: A shortened period waits for the next daily prune
    Given Keep folder history is "1 year"
    When the user changes it to "1 month"
    Then older months go at the next daily prune, not at once
    # Gap: no automated test asserts the change timing; the prune itself is HST-13.

  @SET-13 @gap
  Scenario Outline: The backup strategy decides what reaches the zip
    Given Backup strategy for attachments is "<choice>"
    When the user creates a backup
    Then attachment files are <files>
    # Gap: no automated test asserts the attachment handler chosen per strategy.

    Examples:
      | choice                                  | files                        |
      | Backup everything                       | included in the zip          |
      | Only backup description and local path  | left out, their metadata kept|
      | Do not backup attachments               | left out entirely            |
