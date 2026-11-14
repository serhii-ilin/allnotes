@reminders
Feature: Reminders
  Reminders travel in the note's front matter, so every device that has the file shows and fires them. Removing the
  last reminder writes an empty list, and a file without the key never removes a reminder.

  Background:
    Given phone A and phone B share the folder "Notes" through Syncthing

  @REM-01 @test:VaultRemindersScenariosTest.futureReminder_isWrittenToTheFile_onceAndCleanly @test:VaultNoteDocumentTest.reminders_roundTrip_asIsoUtcList @test:VaultRemindersScenariosTest.expiredReminder_isNotExported @test:VaultRemindersScenariosTest.freshDatabase_importsReminderFromFile_skippingExpired
  Scenario: Future reminders are stored in the file
    Given "Call" has a future reminder "Call Bob"
    When the app syncs
    Then the file holds a "reminders:" list with "name: \"Call Bob\"" and an ISO-8601 UTC "at"
    And a second sync rewrites neither the file nor a conflict copy
    And an expired reminder is neither written nor imported
    And epoch seconds are accepted on read

  @REM-02 @test:VaultRemindersScenariosTest.fileWithoutRemindersKey_keepsRoomReminders @test:VaultNoteDocumentTest.noteWithoutReminders_writesNoKey_andExpiredAreDropped
  Scenario: A file without the key never removes reminders
    Given "Call" has the reminder "Keep me"
    When another tool rewrites the file without a "reminders" key
    Then the note's reminders are untouched
    And a note that never had reminders writes no key, so its file and hash are unchanged

  @REM-03 @SC-09 @test:VaultRemindersScenariosTest.clearingTheLastReminder_writesAnEmptyKey @test:VaultRemindersScenariosTest.removingReminderFromFile_deletesTheRow
  Scenario: Removing the last reminder reaches every device
    Given "Plan" has one future reminder and is synced on A and B
    When A removes it
    Then the file shows "reminders: []"
    And no reminder name is left in the file
    And B deletes its reminder row and drops the alarm on the next sync

  @REM-04 @SC-10 @test:VaultRemindersScenariosTest.restore_putsRemindersFromTheFileBack
  Scenario: Restoring a note brings its reminders back
    Given "Plan" has a reminder and is synced
    When the user bins it, clearing its reminder rows, and later restores it
    Then the reminder is read back from the file before anything is written
    And the restored file still contains it
    And the note is live again

  @REM-06 @gap
  Scenario: The note lists its reminders
    Given "Plan" has the reminders "Call Bob" and "Buy milk"
    When the user taps the bell action
    Then a sheet titled "Reminders" lists one row per reminder as "<name> (<date>, <time>)"
    And the bell is filled because the note has reminders
    And the last row is "New reminder"
    And tapping a reminder row closes the sheet and opens it for editing
    # Gap: the reminders sheet is built in EditorFragment; no test covers it.

  @REM-06 @gap
  Scenario: A binned note offers no reminder action
    Given the note is in the bin
    When the user opens its menu
    Then the bell action is not offered
    # Gap: hiding the reminder action for a binned note has no automated test.

  @REM-07 @gap
  Scenario: Adding a reminder
    Given the user taps "New reminder"
    When the dialog opens
    Then it is titled "New reminder", the name field has the focus and the keyboard is open
    And the date and time buttons show the value in the app's date and time formats
    When the user picks a future date and time and taps Save
    Then the reminder is stored on the note and its alarm is set at once
    And an empty name is accepted
    # Gap: the reminder dialog is UI-only; no automated test covers it.

  @REM-07 @gap
  Scenario: A past time is refused
    Given the user has opened "New reminder"
    When the chosen date and time is before now and the user taps Save
    Then "Cannot set a reminder for a past date" is shown
    And the dialog stays open and nothing is saved
    # Gap: the past-time check has no automated test.

  @REM-08 @gap
  Scenario: Editing a reminder moves its alarm
    Given "Plan" has the reminder "Call Bob" at 09:00
    When the user taps it, changes the time to 10:00 and taps Save
    Then the same reminder now reads 10:00 and its alarm fires at 10:00
    # Gap: the reminder dialog is UI-only; no automated test covers it.

  @REM-08 @gap
  Scenario: Deleting a reminder from its dialog
    Given "Plan" has the reminder "Call Bob"
    When the user opens it and taps Delete
    Then the reminder is gone from the note and its alarm is cancelled
    And the dialog closes without asking for confirmation
    # Gap: delete-in-dialog has no automated test.

  @REM-09 @gap
  Scenario: Alarms are exact only when the app may use them
    Given a reminder is set for a future time
    When the app schedules it
    Then the alarm wakes the device and fires in Doze
    And it is exact when exact alarms are allowed and inexact otherwise
    And setting it again replaces the earlier alarm
    # Gap: ReminderManager has no test in app/src/test.

  @REM-10 @gap
  Scenario: Without the exact-alarm permission the dialog does not open
    Given the app may not schedule exact alarms
    When the user taps "New reminder" or an existing reminder
    Then the system's alarms-and-reminders settings for the app open
    And no reminder dialog opens
    # Gap: the permission gate lives in EditorFragment; no test covers it.

  @REM-10 @gap
  Scenario: Granting the exact-alarm permission reschedules everything
    Given the reminder alarms were lost
    When the user grants the exact-alarm permission and the system broadcasts it
    Then every stored reminder is scheduled again
    # Gap: the receiver's reschedule path has no test in app/src/test.

  @REM-11 @gap
  Scenario: A reminder fires a notification
    Given "Call Bob" is due and still stored
    When its alarm fires
    Then a "Reminders"-channel notification says "You have a reminder." and "Call Bob"
    And the reminder is deleted
    # Gap: ReminderManager.sendNotification has no test in app/src/test.

  @REM-11 @gap
  Scenario: A nameless reminder falls back to the note title
    Given the reminder has an empty name
    When its alarm fires
    Then the notification shows the note's title instead
    # Gap: notification content has no automated test.

  @REM-11 @gap
  Scenario: Tapping the notification opens the note
    Given a reminder notification is showing
    When the user taps it
    Then the editor for that note opens
    And the notification is cleared
    # Gap: the notification's intent has no automated test.

  @REM-11 @gap
  Scenario: A reminder removed before its alarm posts nothing
    Given a reminder alarm is set
    When the reminder is removed by a sync before the alarm fires
    Then no notification is posted
    # Gap: the "reminder gone" guard has no automated test.

  @REM-12 @gap
  Scenario: Reminders survive a reboot
    Given the device restarts with future reminders stored
    When the app receives the boot broadcast
    Then every future reminder has its alarm again
    # Gap: rescheduling on boot has no test in app/src/test.

  @REM-12 @gap
  Scenario: A reminder missed while the device was off is dropped
    Given the device is off when a reminder falls due
    When the device starts
    Then the reminder is deleted without a notification
    # Gap: dropping expired reminders on boot has no automated test.

  @REM-13 @gap
  Scenario: Binning a note silences its reminders
    Given "Plan" has a reminder
    When the user bins it
    Then its alarm is cancelled before the note leaves the list
    # Gap: cancelling alarms on bin has no test in app/src/test.

  @REM-13 @gap
  Scenario: Deleting one reminder cancels only its alarm
    Given "Plan" has two reminders
    When the user deletes one in its dialog
    Then only that reminder's alarm is cancelled
    # Gap: single-reminder cancel has no automated test.

  @REM-13 @gap
  Scenario: Archiving leaves the alarm set
    Given "Plan" has a reminder
    When the user archives it
    Then the reminder still fires at its time
    # Gap: archive leaving alarms set has no automated test.

  @REM-13 @gap
  Scenario: Restoring a note schedules nothing until the next sync
    Given "Plan" is in the bin with its reminders in the file
    When the user restores it
    Then no alarm is set at that moment
    And the next sync reschedules its reminders
    # Gap: restore-time scheduling is untested in app/src/test.

  @REM-14 @gap
  Scenario: Android 13 asks for the notification permission
    Given notifications are disabled on Android 13 or later
    When the reminder dialog opens
    Then the system asks for permission to post notifications
    And a refusal closes the dialog and saves nothing
    And an approval leaves it open so the reminder can be saved
    # Gap: the POST_NOTIFICATIONS request lives in EditReminderDialog; no test covers it.
