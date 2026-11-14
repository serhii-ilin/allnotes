@conflicts
Feature: Conflicts
  When the app and another device or tool both changed the same note since the last agreed version, both versions
  are kept: the app's text stays in the note and the other version becomes a visible conflict copy. Everything that
  is not a real divergence is pulled without a copy.

  Background:
    Given phone A, phone B and a desktop share the folder "Notes" through Syncthing

  @CON-01 @SC-04 @test:VaultSyncOrchestrationTest.trueConflict_recordsOncePerRemoteHash @test:VaultFolderBlockerScenariosTest.blocker2_externalEditThenAppEdit_oneConflictCopy @test:StorageBackendPrePushConflictTest.diskEditBeforePush_hashDiffersFromBaseline
  Scenario: A true conflict keeps both versions
    Given "Plan" is synced
    When the desktop edits "Plan.md" while A has an unsynced edit of the same note
    Then A keeps its edit in the note
    And it saves the desktop text as "Plan (conflict <epoch>).md"
    And the user is told once per remote version
    And the same holds when a push finds the file changed underneath it

  @CON-02 @SC-04 @test:VaultDuplicateIdScenariosTest.conflictCopy_carriesItsOwnId_andIsListedInSettings @test:SettingsViewModelConflictsTest.conflictList_countsOnlyConflictNamedMappedNotes @test:SettingsViewModelConflictsTest.conflictList_skipsBinnedNotes
  Scenario: Conflict copies are visible in the app
    Given a conflict copy "Plan (conflict 1700000000).md" exists
    When the user opens Settings then Sync conflicts
    Then the copy is listed with its title and path
    And opening it opens the editor so the user can merge and delete it
    And a binned conflict copy is not listed
    And the copy carries its own id, so a shared id never hides it

  @CON-03 @test:VaultFolderBlockerScenariosTest.blocker2_quoteInTitle_fiveSaves_zeroConflictFiles @test:VaultForeignYamlMetadataTest.externalAliases_triggersPull_notConflict @test:VaultAttachmentDirtyRefreshTest.removedVaultAttachment_staysRemovedThroughRefreshAndPush @test:VaultRemindersScenariosTest.upgrade_noteWithExistingReminder_exportsOnceWithoutConflict @test:VaultRemoteRenameNoConflictTest.remoteTitleRename_pullsWithoutConflictCopy
  Scenario Outline: No false conflicts
    Given "Plan" is synced
    When <change>
    Then the change is pulled or pushed without a conflict copy

    Examples:
      | change                                              |
      | the app saves the same note five times              |
      | Obsidian edits a foreign "aliases" key              |
      | an attachment membership changes on one side        |
      | the app adds a "allnotes-id" during an upgrade      |
      | a title rename is made on another device            |

  @CON-03 @test:VaultExportStampScenariosTest.titleRenameElsewhere_rightAfterExport_isPulledWithoutAConflictCopy @test:VaultExportStampScenariosTest.bodyEditElsewhere_rightAfterExport_isPulledWithoutAConflictCopy @test:VaultExportStampScenariosTest.repeatedSyncsWithoutChanges_writeAndUpdateNothing
  Scenario: A note exported moments ago is edited on another device
    Given device A just exported "Ideas.md" with "modifiedDate = 1000"
    And the file's front matter still says "modified: 1000"
    When another device renames the note before A syncs again
    Then the rename is a pull, not a conflict
    # Partial (AUD-11): insertRemote stamps the file mtime into modifiedDate, so the note looks locally edited
    # until the creating device syncs again; an edit or rename meanwhile produces a conflict copy.

  @CON-04 @test:VaultHousekeepingScanTest.syncConflictFiles_areNotIndexed
  Scenario: The sync tool's conflict files are left alone
    Given Syncthing wrote "Plan.sync-conflict-20240101-120000.md"
    When the app scans
    Then the file is not imported, renamed or deleted
    And resolving it is up to the user
