# Background work and the sync schedule (BGD)

What the app does without the user acting: the periodic jobs, the folder observer, the sync on resume and the alarms
that are rebuilt. The sync itself, its merging rules and its results are [SYN](sync.md); this area states the triggers,
their cadence and what one run may do to another. Use cases: UC-06, UC-08, UC-09, UC-13.
Gherkin: [background.feature](../features/background.feature).

### BGD-01 Two periodic jobs are registered at start-up
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Every app start registers two unique periodic jobs with WorkManager: a sync every hour and a bin clean every five hours.
Both are enqueued with `KEEP`, so a job that already exists is left alone: a new start neither duplicates it nor resets
its interval. Neither carries a network constraint, so both run offline and while the app is closed. The sync job is
BGD-02 and the bin job BGD-03; registering the jobs does not itself sync or delete anything.

**Acceptance criteria**
- AC1: After a start there is one periodic sync job with a one-hour interval.
- AC2: After a start there is one periodic bin-clean job with a five-hour interval.
- AC3: Further starts keep those jobs: no duplicate is created and their intervals are not reset.
- AC4: Neither job requires a network connection.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-01`

### BGD-02 The hourly job runs a sync and reports its result
**Status:** Implemented · **Scope:** Platform · **Tests:** `SyncWorkerResultTest.success_isSuccess`,
`SyncWorkerResultTest.genericError_isRetriedSoAnIncompleteScanTriesAgain`
The hourly worker runs the same sync as every other trigger (SYN-02) off the main thread and then reschedules the
reminder alarms (SYN-08). A successful sync ends the run as success; an incomplete scan, an unavailable folder or an
error ends it as a retry, so WorkManager runs it again with its backoff. With no folder, or a folder the user has not
confirmed yet, the sync changes nothing and still reports success, so the job does not retry forever. Reminder alarms
are rescheduled even when the sync failed.

**Acceptance criteria**
- AC1: A successful sync is reported to WorkManager as success.
- AC2: Any other sync result is reported as a retry, so the job backs off and runs again.
- AC3: With no folder, or an unconfirmed folder, the job changes nothing and reports success.
- AC4: After the worker's sync, reminder alarms are rescheduled even when the sync failed.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-02`

### BGD-03 The five-hourly job empties the bin and reclaims media
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Every five hours the bin cleaner reads the device's bin retention period. With a period set (one week, two weeks or a
month) it permanently deletes binned notes whose deletion is older than that period, then deletes private attachment
copies that no note refers to (MED-12). Its deletions are device-local: they record no `note.purge`, so they do not
empty another device's bin (HST-04). With **Never** or **Instantly** selected it does nothing and reports success, and
no media cleanup runs from it. How a permanent delete treats the note's files is DEL-02.

**Acceptance criteria**
- AC1: A binned note older than the retention period is permanently deleted; a younger one stays.
- AC2: The purge records no history event, so other devices keep their copies (HST-04).
- AC3: After a purge, private attachment copies that no note refers to are deleted (MED-12).
- AC4: With **Never** or **Instantly** the run deletes nothing and reports success.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-03`

### BGD-04 The folder observer follows the configured folder
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
At start-up the app starts the folder observer only after the one-time notebook-name pass (FMT-11) has finished. A
failed pass is logged, retried on the next start, and does not stop the observer. The observer is registered again
whenever the stored folder changes: the old registration is dropped and a recursive `ContentObserver` is put on the new
tree URI. With no folder nothing is observed, and a registration the provider refuses is logged, leaving the hourly job
and resume sync as the fallback (BGD-02, BGD-05). What a reported change does is SYN-02.

**Acceptance criteria**
- AC1: The observer starts only after the notebook-name pass has finished, whether it succeeded or failed.
- AC2: With a folder set, a change anywhere under its tree leads to a sync.
- AC3: Changing the folder unregisters the old observer and registers the new tree.
- AC4: With no folder nothing is observed; a refused registration leaves the hourly job and resume as the only triggers.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-04`

### BGD-05 Returning to the app syncs at most every two minutes
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Each time the main screen resumes with a folder set, the app syncs if at least two minutes have passed since the last
such sync in this process; the first resume after a start always syncs. The throttle is in memory only, so a new
process syncs again. Pull-to-refresh on any note list syncs immediately, whatever the throttle says (SYN-02). A pending
"use this folder?" prompt for an upgraded install is shown instead of syncing. With no folder the app asks for one and
syncs nothing.

**Acceptance criteria**
- AC1: The first resume after an app start with a folder set syncs.
- AC2: A resume less than two minutes after the last foreground sync does not sync.
- AC3: Pull-to-refresh always syncs, regardless of the throttle.
- AC4: With no folder, resume asks the user to choose one and syncs nothing.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-05`

### BGD-06 A sync in flight is never cancelled, and a change queues one more
**Status:** Implemented · **Scope:** Platform · **Tests:**
`VaultFolderChangeMonitorTest.eventDuringRunningSync_doesNotCancelIt_andRunsOneFollowUp`
Once a folder-triggered sync has started it runs to completion even if another change arrives. That change is not
dropped: it schedules exactly one follow-up sync after the running one and the debounce (SYN-02). A sync, a queued note
push and a folder change all take one vault lock, so a second operation waits for the first to finish and then runs in
full; overlapping syncs are serialized, not merged into one. The periodic sync job exists once, so WorkManager does not
run a second copy of it (BGD-01).

**Acceptance criteria**
- AC1: A change that arrives during a running sync does not cancel it.
- AC2: That change causes exactly one more sync after the running one finishes.
- AC3: An operation that needs the vault lock waits until the current holder releases it.
- AC4: Two waiting sync requests each run; they are not merged into one.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-06`

### BGD-07 Alarms come back after a reboot or an exact-alarm change
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
The reminder receiver reschedules every reminder alarm when the device finishes booting and, on Android 12 and newer,
when the exact-alarm permission changes. Without the exact-alarm permission the alarm is still set, inexactly, so it
may fire late. A fired alarm posts the reminder notification for its note and deletes that reminder; a reminder whose
time has passed is dropped instead of being scheduled. What a reminder is and how it travels in the file is REM.

**Acceptance criteria**
- AC1: After a reboot every future reminder has an alarm again.
- AC2: On Android 12+, a change to the exact-alarm permission reschedules them.
- AC3: Rescheduling drops reminders whose time has passed instead of scheduling them.
- AC4: A fired alarm posts the reminder's notification and removes that reminder.

**Gherkin:** [`background.feature`](../features/background.feature) — `@BGD-07`

### BGD-08 What the app does not do in the background
There is no switch that turns background scanning off — see UI-03. Beyond the two jobs in BGD-01 the app starts no
periodic work: attachment cleanup runs only as part of the bin purge (BGD-03, MED-12) and folder-history retention only
inside a sync (HST-13). No note data is sent over the network; the folder is the only sync path (SYN-01).

### BGD-09 User-started services are not background jobs
See MED-03 and MED-08.

Playback (MED-08) runs in a foreground service, so it continues with the screen off; recording (MED-03) runs in a
started and bound service. Both start because the user started them and belong to the media area; this area only
records that they are not scheduled work.

### BGD-10 Background scans ignore housekeeping paths
See FMT-07, CON-04 and HST-01.

What a scan never treats as a note is FMT-07 (dot-folders at any depth, Android `.trashed-` files, anything that is not
`.md` or `.txt`), CON-04 (Syncthing `*.sync-conflict-*` files) and HST-01 (`.allnotes/`). The rules are the same whether
the scan comes from the hourly job (BGD-02), the observer (BGD-04) or a resume (BGD-05).
