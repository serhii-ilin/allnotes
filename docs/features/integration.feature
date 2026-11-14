@integration
Feature: Integration with Android
  The app is a normal Android app: it accepts shared text and media, has launcher shortcuts, a home-screen widget, an
  About screen, a What's new dialog, a fixed language list, a FileProvider for its private media, notification
  channels, Android backup rules and a network policy that matches its offline design.

  Background:
    Given the app is installed on the device

  @INT-01 @gap
  Scenario: Shared text opens a new note
    Given another app shares the text "Buy milk" with the title "Shopping"
    When the user picks AllNotes, labelled "Take a note", in the share sheet
    Then the editor opens on a new note
    And the note's title is "Shopping" and its body is "Buy milk"
    # Gap: no automated test drives an incoming share intent.

  @INT-01 @gap
  Scenario: A share is not imported twice after the activity is recreated
    Given the user shared the text "Buy milk" into a new note
    When the activity is recreated, for example after a theme change
    Then no second note is created from the same share
    # Gap: no automated test covers the incoming-intent handling in MainActivity.

  @INT-02 @gap
  Scenario: A shared photo is copied and attached to the new note
    Given another app shares the image "photo.jpg"
    When the user shares it to AllNotes
    Then the image is copied into the app's private media storage
    And the new note has that copy as an attachment
    # Gap: no automated test drives an incoming share with EXTRA_STREAM.

  @INT-02 @gap
  Scenario: Several shared files are all attached, and an unreadable one is skipped
    Given another app shares "clip.mp4" and "song.m4a" together
    And one of the shared files cannot be copied
    When the user shares them to AllNotes
    Then every file that copied successfully is attached to the new note
    And the editor still opens
    # Gap: no automated test covers SEND_MULTIPLE share-in.

  @INT-03 @gap
  Scenario Outline: A launch intent opens the screen it names
    Given the app receives an intent with <extra>
    When the app is launched
    Then it shows <screen>

    Examples:
      | extra                        | screen                     |
      | no extra data                | the notes list             |
      | the noteId of a note         | that note in the editor    |
      | the action NEW_NOTE          | a blank editor             |

    # Gap: no automated test covers the intent dispatch in MainActivity.

  @INT-03 @gap
  Scenario: A second launch reuses the running task
    Given the app is already showing the notes list
    When the user launches it again from the launcher or a share
    Then no second activity is stacked
    And the new intent is handled by the running activity
    # Gap: no automated test covers singleTask delivery.

  @INT-03 @gap
  Scenario: Only explicit intents can use the deep link
    Given the manifest declares no browsable filter
    When a web page tries to open "allnotes://notes/editor_create"
    Then AllNotes is not offered as a handler for that link
    But a shortcut, the widget, a reminder notification or another app that names MainActivity can open it
    # Gap: no automated test asserts the manifest's intent filters.

  @INT-04 @gap
  Scenario Outline: A launcher shortcut starts the right kind of note
    Given the user long-presses the app icon on API 25 or later
    When the user picks "<shortcut>"
    Then a new note opens as <kind>
    And it opens in the existing task

    Examples:
      | shortcut    | kind         |
      | Take a note | a plain note |
      | Make a list | a task list  |

    # Gap: no automated test covers the shortcuts resource or newNoteIsList.

  @INT-04 @gap
  Scenario: The old launcher entry still starts the app
    Given a pinned icon from an older version points at org.qosp.notes.ui.launcher.LauncherActivity
    When the user taps it
    Then MainActivity starts and shows the notes list
    And the shortcuts stay attached to that launcher entry
    # Gap: no automated test covers the activity-alias.

  @INT-05 @gap
  Scenario: The widget lists the newest pinned notes
    Given the user pinned 25 notes and has archived, binned and unpinned notes
    When the widget is shown
    Then it lists at most 20 rows
    And every row is a pinned note that is neither archived nor binned
    And the rows are ordered by modification time, newest first
    And the widget is home-screen only and can be resized
    # Gap: no automated test covers the widget's note query or the 20-row cap.

  @INT-05 @gap
  Scenario Outline: A widget row shows a title, a preview and a date
    Given a pinned note with <state>
    When the widget draws its row
    Then the title is "<title>"
    And the preview is "<preview>"
    And the date is the modification day as "dd MMM"

    Examples:
      | state                    | title | preview       |
      | the title "Plan"         | Plan  | one-line text |
      | a blank title and "Milk" | Milk  | Milk          |
      | a task list, 1 of 3 done | Plan  | 1 / 3 tasks   |

    # Gap: no automated test covers the widget row contents.

  @INT-06 @gap
  Scenario: Tapping a widget row opens the note
    Given the widget lists the pinned note "Plan"
    When the user taps that row
    Then the editor opens on "Plan"
    # Gap: no automated test covers the widget's fill-in intent.

  @INT-06 @gap
  Scenario: Without pinned notes the widget shows the empty state
    Given no note is pinned
    When the widget is shown
    Then it says "No pinned notes yet. Pin a note to see it here."
    And tapping that message opens the app
    And the widget offers no control that creates a note
    # Gap: no automated test covers the widget's empty state.

  @INT-07 @gap
  Scenario: The widget is refreshed on start, on pin changes and by the system
    Given the widget is on the home screen
    When the app starts, or a note is pinned or unpinned, or 30 minutes pass
    Then every widget instance is rebuilt
    And the list is told that its data changed
    And placing the widget fills it without opening the app
    # Gap: no automated test covers WidgetUpdateHelper or the update period.

  @INT-07 @gap
  Scenario: An edit alone leaves the widget stale
    Given a pinned note "Plan" is shown in the widget
    When the user edits its text, bins it or hides it
    Then the widget keeps the old row until the next refresh
    # Gap: no automated test covers this refresh gap.

  @INT-08 @gap
  Scenario: About shows the version and opens the fixed pages
    Given the user opens About from the drawer
    When the screen is shown
    Then the version row shows the app's version name
    And Website, Developer, Contribute and Support each open their page in another app
    And Libraries shows the third-party licences in a dialog
    # Gap: no automated test covers AboutFragment.

  @INT-08 @gap
  Scenario: Send Error Logs prepares a report for the user to send
    Given the user taps Send Error Logs in About
    When the report is built
    Then a mail app opens with a JSON report attached
    And no report is sent unless the user sends that message
    # Gap: no automated test covers the ACRA hand-off.

  @INT-09 @test:WhatsNewTest.updateToShow_returnsLatest_whenItIsNewerThanLastShown @test:WhatsNewTest.updateToShow_returnsNull_whenLatestWasAlreadyShown @test:WhatsNewViewModelTest
  Scenario: A newer catalog entry is shown once
    Given the user last saw update 5 and the catalog's newest update is 7
    When the app starts
    Then the dialog shows update 7's title and bullets
    And starting the app again, or recreating the activity, does not show it again
    # Rotation while it is open also does not bring it back.

  @INT-09 @test:WhatsNewTest.updateToShow_returnsLatest_whenNothingWasShownYet @test:WhatsNewViewModelTest
  Scenario: A first run shows the newest update
    Given no update was ever shown, or the stored id is not a number
    When the app starts
    Then the dialog shows the newest catalog entry

  @INT-09 @gap
  Scenario: A TestLab build starts without the dialog
    Given a build made with the TESTLAB_BUILD flag
    When the app starts
    Then the What's new dialog is not shown
    # Gap: no automated test covers the TESTLAB_BUILD branch.

  @INT-10 @gap
  Scenario: The system offers the app's 18 languages
    Given the device runs Android 13 or later
    When the user opens Settings, Apps, AllNotes, Language
    Then the list holds en, ca, cs, de, el, es, fr, it, nb-NO, nl, pl, pt-BR, ru, tr, uk, vi, zh-CN and zh-TW
    And the app itself has no language picker
    # Gap: no automated test covers locales_config.xml.

  @INT-10 @gap
  Scenario: Choosing an app language changes the UI strings
    Given the user picks German for AllNotes in the system settings
    When the app is opened
    Then its strings are shown in German
    # Gap: no automated test covers per-app language selection.

  @INT-10 @gap
  Scenario: A translation outside the list still follows the system language
    Given the app has resources for Arabic, Persian and Romanian
    When the system language is one of them
    Then the app uses those strings
    But they are not offered in the app's language list
    # Gap: no automated test compares the resource folders with locales_config.xml.

  @INT-11 @gap
  Scenario: A private media file is shared with a temporary grant
    Given a note has the attachment stored under files/media/
    When the user shares the attachment
    Then the other app receives a content:// URI from io.github.allnotes.provider
    And the grant lasts only for that share
    # Gap: no automated test covers the provider contract.

  @INT-11 @gap
  Scenario: A path outside the shared folders is not reachable
    Given a file in the database, in the preferences or in a vault cache
    When another app asks the provider for it
    Then the provider refuses
    But the camera and the recorder can still write through the URIs the app issues them
    # Gap: no automated test covers file_provider_paths.xml.

  @INT-12 @gap
  Scenario: The three channels exist from the first start
    Given the device runs Android 8 or later
    When the app starts
    Then the channels Reminders, Backups and Media playback exist
    And no fourth channel is created
    And notification permission is asked only from the reminder flow
    # Gap: no automated test covers App.createNotificationChannels.

  @INT-12 @gap
  Scenario: Each job notifies on its own channel
    Given the user has a reminder, a backup and a playback session
    When each of them shows a notification
    Then the reminder uses Reminders
    And the backup uses Backups from its dataSync foreground service
    And playback uses Media playback from its mediaPlayback foreground service
    But the bound microphone recorder never enters the foreground
    # Gap: no automated test covers the channel wiring.

  @INT-13 @test:BackupRulesTest.fullBackupContent_excludesPrivateData @test:BackupRulesTest.dataExtractionRules_excludePrivateDataFromBothTransfers
  Scenario: Android backup leaves private caches and unrestorable keys out
    Given Auto Backup is on and the user allows it
    When Android backs up or transfers the app
    Then "encrypted_prefs.xml" is left out
    And "vault-scan-cache/", "vault-recovery/" and "vault-history/" are left out
    But the database and the private media copies stay eligible
    # Note files in the user's folder are outside app data, so Android never backs them up.

  @INT-13 @test:BackupRulesTest.manifestReferencesBothRules
  Scenario: The manifest points auto backup at both rule files
    Given the manifest declares a backup configuration
    When the app is built
    Then it references "backup_rules.xml" and "data_extraction_rules.xml"
    # API 30 and lower read the first file, API 31 and later the second.

  @INT-14 @test:VaultMarkwonImageLoaderTest.httpUrl_passesThrough @test:TaskListMarkwonBindingsTest.remoteHttps_stillMarkdownWithoutVaultContext
  Scenario: A remote image link is handed to the image loader
    Given a note body contains "![](https://example.com/logo.png)"
    When the preview resolves the image destination
    Then the URL is passed through unchanged instead of being treated as a local file
    # Coil, which bundles a network fetcher, then downloads and shows it. No test observes the actual request.

  @INT-14 @gap
  Scenario: Notes, sync and links need no network from the app
    Given the app declares INTERNET and ACCESS_NETWORK_STATE
    When the user writes notes, syncs the folder and follows a link in a note
    Then no request is made by the app itself
    And the link opens in another app
    # Gap: no automated test asserts the absence of network code.

  @INT-14 @gap
  Scenario: The network security config trusts the normal certificate stores
    Given the app has a network-security config
    When its trust anchors are read
    Then the system and user certificate stores are trusted
    And no cleartext exception is configured
    # Gap: no automated test covers network_security_config.xml.
