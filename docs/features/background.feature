@background
Feature: Background work and the sync schedule
  The app keeps the folder in step without the user asking: two periodic jobs, a folder observer and a sync on resume.
  This feature states the triggers, their cadence and what one run may do to another; the sync's own merging rules are
  in sync.feature.

  Background:
    Given the app is installed on the device

  @BGD-01 @gap
  Scenario: Two periodic jobs are registered at start-up
    Given the app has just started
    When it registers its periodic work
    Then one sync job with a one-hour interval and one bin-clean job with a five-hour interval exist
    And starting the app again keeps those jobs, without a duplicate or a new interval
    And neither job waits for a network connection
    # Gap: App.enqueueWorkers has no automated test.

  @BGD-02 @test:SyncWorkerResultTest.success_isSuccess
  Scenario: A successful hourly sync ends the run
    Given the hourly job has run its sync
    When the sync completed successfully
    Then the job reports success to WorkManager

  @BGD-02 @test:SyncWorkerResultTest.genericError_isRetriedSoAnIncompleteScanTriesAgain
  Scenario: A failed hourly sync is retried
    Given the hourly job has run its sync
    When the sync failed, the folder was unavailable or the scan was incomplete
    Then the job reports a retry, so WorkManager backs off and runs it again

  @BGD-02 @gap
  Scenario: The hourly job with no folder still ends successfully
    Given no notes folder is configured, or the folder is not confirmed yet
    When the hourly job runs
    Then no note changes
    And the job still reports success
    And reminder alarms are rescheduled anyway
    # Gap: SyncWorker.doWork and its reschedule call have no automated test.

  @BGD-03 @gap
  Scenario: Old bin entries are purged every five hours
    Given the bin retention period is one week
    And "Old" was binned eight days ago and "New" was binned yesterday
    When the bin cleaner runs
    Then "Old" is permanently deleted and "New" stays in the bin
    And no note.purge history event is recorded
    And private attachment copies that no note refers to are deleted
    # Gap: BinCleaningWorker has no automated test.

  @BGD-03 @gap
  Scenario: The bin cleaner leaves a never- or instantly-emptied bin alone
    Given the bin retention period is Never or Instantly
    When the bin cleaner runs
    Then it deletes nothing and reports success
    # Gap: BinCleaningWorker has no automated test.

  @BGD-04 @gap
  Scenario: The observer starts after the notebook-name pass
    Given a notebook whose name is not a valid folder name
    When the app starts
    Then the one-time rename finishes before the folder observer starts
    And a failed pass is logged, retried on the next start, and still lets the observer start
    # Gap: App.onCreate's ordering has no automated test.

  @BGD-04 @gap
  Scenario: The observer follows the configured folder
    Given the notes folder is "Notes"
    When the app starts and the user later switches to the folder "Archive"
    Then a recursive observer is registered on "Notes" and afterwards on "Archive"
    And the old registration is dropped
    And with no folder nothing is observed
    And a refused registration leaves the hourly job and resume as the only triggers
    # Gap: VaultFolderChangeMonitor.start and registerObserver have no automated test.

  @BGD-05 @gap
  Scenario: A resume syncs, but not twice within two minutes
    Given the app has been in the background
    When the main screen resumes with a folder set
    Then the app syncs if the last such sync was at least two minutes ago
    And a resume before that does not sync
    And a pull to refresh always syncs
    And with no folder the app asks for one and syncs nothing
    # Gap: MainActivity.onResume and VaultForegroundRescan have no automated test.

  @BGD-06 @test:VaultFolderChangeMonitorTest.eventDuringRunningSync_doesNotCancelIt_andRunsOneFollowUp
  Scenario: A change during a running sync is not lost
    Given a folder-triggered sync is running
    When the observer reports another change
    Then the running sync is not cancelled and finishes
    And exactly one more sync runs afterwards

  @BGD-06 @gap
  Scenario: Vault operations wait for each other
    Given a sync holds the vault lock
    When a queued note push or another sync starts
    Then it waits for the holder to finish and then runs in full
    And two waiting sync requests are not merged into one
    # Gap: the shared vault lock has no concurrency test.

  @BGD-07 @gap
  Scenario: A reboot brings the reminder alarms back
    Given the user has future reminders
    When the device finishes booting
    Then an alarm is set for every future reminder
    And reminders whose time has passed are dropped, not scheduled
    # Gap: ReminderReceiver has no automated test.

  @BGD-07 @gap
  Scenario: An exact-alarm permission change reschedules alarms
    Given the app is on Android 12 or newer
    When the exact-alarm permission changes
    Then every future reminder gets an alarm again
    And without the permission the alarm is still set, inexactly
    # Gap: ReminderReceiver has no automated test.

  @BGD-07 @gap
  Scenario: A fired alarm notifies and removes the reminder
    Given a reminder's alarm fires
    When the receiver handles it
    Then the reminder's notification is posted for its note
    And that reminder is deleted
    # Gap: ReminderReceiver and ReminderManager.sendNotification have no automated test.
