@quality
Feature: Quality and non-functional facts
  The platform the app runs on, the permissions it asks for, what leaves the device or stays readable on it, and how it
  handles accessibility, languages and speed. These are facts about the current build, not targets; a missing target is
  its own requirement. Principles are in the constitution; behaviors another area owns are pointed at, not repeated.

  Background:
    Given the app is installed on the device

  @NFR-01 @gap
  Scenario: The app runs from Android 7.0 to the current release
    Given a device running Android 7.0 (API 24)
    When the app is installed
    Then it installs and starts, and targets API 37
    And notification channels are created only from Android 8.0 (API 26)
    And "Follow system" is offered only from Android 12 (API 31)
    And the typed foreground-service permissions are declared only for Android 14 (API 34) and later
    # Gap: no automated test reads minSdk, targetSdk or the per-version branches.

  @NFR-02 @gap
  Scenario: The recorder asks for the microphone first
    Given the app does not have the microphone permission
    When the user taps "Record audio"
    Then the dialog stays hidden until the permission is granted
    And a refusal closes it and adds no attachment
    # Gap: the permission flow is handled in the dialog only; no test covers it.

  @NFR-02 @gap
  Scenario: Notifications are asked for from a reminder
    Given the device runs Android 13 and notifications are off
    When the user creates a reminder
    Then the system asks for notification permission
    And a refusal closes the reminder dialog
    But playback never asks for it
    # Gap: no automated test drives the notification-permission request.

  @NFR-02 @gap
  Scenario: A reminder without exact-alarm access is still scheduled
    Given the device runs Android 12 and the app may not schedule exact alarms
    When the user adds a reminder
    Then the system exact-alarm screen opens
    And the reminder is scheduled with an inexact alarm
    # Gap: no automated test covers the exact-alarm hand-off.

  @NFR-03 @gap
  Scenario: Install-time permissions match what the app does
    Given the manifest of the app
    Then it declares INTERNET for remote images in note previews
    And it declares ACCESS_NETWORK_STATE, which nothing reads
    And it declares WAKE_LOCK, which playback takes so audio continues with the screen off
    And it declares RECEIVE_BOOT_COMPLETED, so a reboot re-arms reminders
    And it declares FOREGROUND_SERVICE with the dataSync, microphone and mediaPlayback types
    And it declares SCHEDULE_EXACT_ALARM for exact reminders
    And, apart from the two runtime permissions, it declares nothing else
    # Gap: no automated test reads the manifest's permission list.

  @NFR-04 @gap
  Scenario: Files arrive through system pickers
    Given the user attaches a file, takes a photo or shares a file into the app
    Then no storage or camera permission is involved
    And the file arrives as a content URI
    And the notes folder is the storage-access tree the user picked
    # Gap: no automated test asserts the absence of storage and camera permissions.

  @NFR-05 @gap
  Scenario: Secrets are encrypted and notes are not
    Given the user chose a notes folder and the app recorded a folder-history identity
    Then the folder URI, the vault generation fence and the history id and sequence are in "encrypted_prefs"
    And that file uses AES-256 keys from the AndroidKeyStore and is excluded from Android backup
    But the theme, layout, sorting and format settings sit in an unencrypted DataStore file
    And the note files, the attachment copies and the Room index are readable without a key
    And a theme cache file holds the last theme in the clear so it applies before the first frame
    And an encrypted file that cannot be opened is recreated empty instead of crashing
    # Gap: no automated test opens the encrypted preferences.

  @NFR-06 @gap
  Scenario: Encrypting note files is undecided
    Given notes, attachments and the index are stored unencrypted
    When the owner decides whether that must change
    Then the owner sets a measurable target
    # Gap: no target exists yet; the owner decides.

  @NFR-07 @gap
  Scenario: Icon-only controls carry no spoken label
    Given the main screen and the editor
    Then both floating action buttons have no content description
    And the drawer's sync-settings button and the recorder's microphone have none either
    But actions in menus are labelled by their menu title
    # Gap: no automated test checks content descriptions.

  @NFR-07 @gap
  Scenario: Text follows the system font scale
    Given the device font size is increased
    When the app is opened
    Then the text grows, because every size is in sp
    And the editor size chosen in Settings is applied in sp on top of it
    And the editor's checklist rows scale with it
    # Gap: no automated test measures text scaling.

  @NFR-07 @gap
  Scenario: The appearance options that exist
    Given the user opens the appearance settings
    Then the theme is light, dark or follow system
    And the dark theme is standard or black
    And seven colour schemes are offered, plus the system colours on Android 12 and later
    But there is no high-contrast option
    And no minimum touch target is declared in the layouts
    # Gap: no automated test renders the themes or measures touch targets.

  @NFR-08 @gap
  Scenario: Accessibility goals are undecided
    Given the app has no content descriptions and no declared touch-target minimum
    When the owner decides what accessibility level is required
    Then the owner sets a measurable target
    # Gap: no target exists yet; the owner decides.

  @NFR-09 @gap
  Scenario: Translation folders, the build filter and the system list disagree
    Given the app has string resources for 21 languages against 303 English strings
    When the app is built and the system language list is opened
    Then 19 locales pass the build's filter and 18 are offered
    And Arabic passes the filter but is not offered
    But Persian, Romanian and Swedish are in the tree and in neither list
    And any string missing from a translation is shown in English
    # Gap: no automated test compares the resource folders, localeFilters and locales_config.xml.

  @NFR-09 @gap
  Scenario: Right-to-left layout and localized dates
    Given the device language is right-to-left
    Then the manifest declares support and the layouts mirror, except one left padding in the Settings header
    When the user opens a note
    Then its date and time use the patterns chosen in Settings
    And month names are localized while the field order is fixed
    And the widget shows its date as a fixed "dd MMM" in the device locale
    # Gap: no automated test covers text direction or date formatting.

  @NFR-10 @gap
  Scenario: Localization goals are undecided
    Given translation completeness varies and the offered list is shorter than the resource folders
    When the owner decides how complete a translation must be
    Then the owner sets a measurable target
    # Gap: no target exists yet; the owner decides.

  @NFR-11 @test:VaultScanCacheTest.hitOnSameMtimeAndSize_whenOlderThanRacyWindow @test:VaultScanCacheTest.missOnChangedMtimeOrSize @test:VaultScanCacheTest.savePrunesRemovedPaths @test:VaultScanCacheTest.saveSkipsWriteWhenEntriesUnchanged
  Scenario: An unchanged file is not read again
    Given the scan cache holds "Plan.md" with its time and size
    When the folder is scanned and the file has not changed
    Then its text comes from the cache
    But a changed time or size, or unknown metadata, makes the app read the file again
    And paths that are gone are dropped from the cache
    And an unchanged cache is not rewritten

  @NFR-11 @test:VaultScanCacheTest.corruptFile_isIgnored @test:VaultScanCacheTest.clear_dropsEntriesAndFile_soNextScanRereads
  Scenario: A corrupt or cleared cache is harmless
    Given the scan cache file is unreadable
    When the folder is scanned
    Then the app reads the folder as if there were no cache
    And clearing the cache makes the next scan read everything again

  @NFR-12 @test:EditorViewModelSaveTest.typingBurst_persistsOnlyLastContentOnceAfterDebounce @test:EditorViewModelSaveTest.typingBurst_persistsOnlyLastTitleOnceAfterDebounce @test:EditorViewModelSaveTest.vaultPush_happensOnceAfterLongerIdleWindow @test:EditorViewModelSaveTest.flush_persistsPendingContentImmediately
  Scenario: A burst of typing becomes one write and one push
    Given the user types "abc" in quick succession
    When nothing is typed for 300 ms
    Then the index is written once with the last text
    And nothing is pushed to the folder yet
    When nothing is typed for a further 1500 ms
    Then the note is pushed to the folder once
    But leaving the editor writes pending text at once, without waiting

  @NFR-12 @test:VaultFolderChangeMonitorTest.rapidChanges_collapseToSingleSync
  Scenario: A burst of folder events becomes one sync
    Given another app writes several files into the folder in quick succession
    When the monitor sees them
    Then it runs one sync two seconds after the last event

  @NFR-12 @gap
  Scenario: Search waits for a pause in typing
    Given the user is typing in the search box
    When they stop for 300 ms
    Then the query runs once
    # Gap: SearchViewModel's debounce has no automated test.

  @NFR-13 @test:NoteRecyclerAdapterVaultPathTest.identical_map_triggers_no_item_change_notifications @test:NoteRecyclerAdapterVaultPathTest.changed_path_notifies_only_the_affected_position
  Scenario: The list rebinds only what changed
    Given the note list shows three notes
    When an equal map of vault paths is delivered again
    Then no item change is notified
    But a changed path notifies only its own position

  @NFR-13 @test:NoteAdapterPayloadTest.testPayloadFlattening
  Scenario: Changes travel as payloads
    Given one note changed its title and content
    When the list delivers the change
    Then the payloads are flattened into one update for that note

  @NFR-14 @gap
  Scenario: Performance goals are undecided
    Given the app has caches and debounces but no stated speed for a start, a scan or a search
    When the owner decides what is fast enough
    Then the owner sets a measurable target
    # Gap: no target exists yet; the owner decides.

  @NFR-15 @test:VaultMarkwonImageLoaderTest.httpUrl_passesThrough @test:TaskListMarkwonBindingsTest.remoteHttps_stillMarkdownWithoutVaultContext
  Scenario: A remote image link is passed to the image loader
    Given a note body contains "![x](https://example.com/x.png)"
    When the note is previewed
    Then the address is passed through to Coil unchanged
    And the same remote link stays plain Markdown in a row with no note context

  @NFR-15 @gap
  Scenario: Nothing else leaves the device
    Given the app syncs through the folder and has no account or analytics SDK
    When the user reads, writes and syncs notes
    Then no note text, attachment or index row is uploaded by the app
    And Android Auto Backup may upload the index, private media and the other preferences except the excluded paths
    But note files in the user's folder are outside app data
    And a crash report leaves only when the user sends it
    # Gap: no automated test inspects the full set of outbound paths.

  @NFR-16 @gap
  Scenario: A crash sends nothing by itself
    Given the app crashed while reporting was disabled
    When the app is started again
    Then no report is sent
    # Gap: no automated test covers ACRA being initialised disabled.

  @NFR-16 @gap
  Scenario: Send logs hands one report to the mail app
    Given the user opens About and taps "Send logs"
    Then reporting is enabled for one report and disabled again
    And a JSON report is handed to the user's mail app, addressed to the project's support address
    And it holds the app and device metadata, the stack trace, the user comment and up to 500 logcat lines
    But no note text
    # Gap: no automated test covers the manual report.
