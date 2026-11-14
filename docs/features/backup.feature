@backup
Feature: Backup and restore
  A zip backup carries notes, notebooks, tags, reminders and attachments. Restoring it drops the folder mappings
  stored in the file, because they describe an older folder state, and reconciles with the current folder in one sync.

  Background:
    Given the user has notes with notebooks, tags, reminders and attachments

  @BAK-01 @test:BackupTest.serializer @test:BackupRestoreVaultTest.restoreWithOldVaultMappings_syncTwice_noDuplicatesNoBinned
  Scenario: Notes, notebooks, tags, reminders and attachments go into a zip and come back
    Given the user exports a zip backup
    When the user restores it later
    Then the notes, notebooks, tags, reminders and attachments are back
    And the backup format round-trips them without loss

  @BAK-02 @test:BackupRestoreVaultTest.restoreWithOldVaultMappings_syncTwice_noDuplicatesNoBinned
  Scenario: A restore does not fight the folder
    Given a backup stores mappings that point at an old folder state
    When the user restores it and syncs twice
    Then the result has one file per note in the current folder
    And there are no duplicate notes, duplicate suffix files or binned notes

  @BAK-03 @gap
  Scenario: A backup's json carries every note and what it uses
    Given notes in a notebook with tags, reminders and an attachment
    When the user backs up all notes
    Then the zip has backup.json with the notes, notebooks, tags, note-tag links and reminders

  @BAK-03 @gap
  Scenario Outline: The backup strategy decides what happens to attachments
    Given a note with an attachment stored in the notes folder
    And the backup strategy is "<strategy>"
    When the user backs up all notes
    Then <result>

    Examples:
      | strategy                                 | result                                                     |
      | Include files                            | the attachment's bytes are in media/ and its entry names them |
      | Only backup description and local path   | the entry keeps its path and description and no media/ is written |
      | Don't backup attachments                 | the note is written without attachments                   |

  @BAK-04 @gap
  Scenario: A restore adds notes and reuses matching notebooks and tags
    Given the app has notebook "Work" and tag "home"
    And a backup with notebook "work", tag "home" and a future and an expired reminder
    When the user restores it
    Then its notes are added as new notes in notebook "Work" with tag "home"
    And the future reminder is scheduled and the expired one is not restored
    And one sync runs

  @BAK-05 @gap
  Scenario: Backup and restore report progress and the result
    When the user backs up all notes
    Then a notification shows "Backing up your notes…" with progress
    And it ends with "Backup complete!" or "Backup failed"
    When the user restores a backup
    Then a notification shows "Restoring your notes…" and ends with "Restore completed" or "Restore failed"
