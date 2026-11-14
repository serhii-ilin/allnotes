# Reminders (REM)

Reminders travel in the file, so every device shows and fires them. Use case: UC-06. Scenarios: SC-09, SC-10.
Gherkin: [reminders.feature](../features/reminders.feature).

### REM-01 Future reminders are stored in the file
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRemindersScenariosTest.futureReminder_isWrittenToTheFile_onceAndCleanly`, `VaultNoteDocumentTest.reminders_roundTrip_asIsoUtcList`
Future reminders are written as a `reminders:` list of `name` and `at` (ISO-8601 UTC). Expired reminders are neither
written nor imported. Epoch-second dates are also accepted on read.

**Acceptance criteria**
- AC1: A future reminder is written as a `reminders:` entry with `name` and an ISO-8601 UTC `at`.
- AC2: A second sync neither rewrites the file nor creates a conflict copy.
- AC3: An expired reminder is neither exported nor imported.
- AC4: An epoch-second `at` is accepted on read.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-01`

### REM-02 A file without the key never removes reminders
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRemindersScenariosTest.fileWithoutRemindersKey_keepsRoomReminders`, `VaultNoteDocumentTest.noteWithoutReminders_writesNoKey_andExpiredAreDropped`
A file with no `reminders` key, such as one written by another tool, leaves the note's reminders alone. A note that
never had reminders writes no key, so its file and hash are the same as before reminders were supported.

**Acceptance criteria**
- AC1: A file rewritten without a `reminders` key keeps the note's reminder rows.
- AC2: A note without reminders writes no `reminders` key.
- AC3: Such a note's canonical hash equals the pre-reminders hash.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-02`

### REM-03 Removing the last reminder reaches every device
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRemindersScenariosTest.clearingTheLastReminder_writesAnEmptyKey`, `VaultRemindersScenariosTest.removingReminderFromFile_deletesTheRow`
When the last reminder is removed from a note whose file has the key, the file gets `reminders: []`. Other devices then
delete their copies and drop the alarms.

**Acceptance criteria**
- AC1: Removing the last reminder from a note whose file had the key writes `reminders: []`.
- AC2: The removed reminder's name no longer appears in the file.
- AC3: A file changed to `reminders: []` by another tool deletes the local reminder row on sync.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-03`

### REM-04 Restoring a note brings its reminders back
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultRemindersScenariosTest.restore_putsRemindersFromTheFileBack`
Binning clears the note's reminder rows but the file keeps them. Restoring reads them back from the file before
anything is written, so the restored file still has them.

**Acceptance criteria**
- AC1: Binning clears the note's reminder rows.
- AC2: Restoring reads the reminders back from the file before anything is written.
- AC3: The restored note is live and its file still contains the reminder.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-04`

### REM-05 Alarms are rescheduled after sync
See SYN-08.

### REM-06 The note lists its reminders
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
The bell action in the note's menu opens a **Reminders** sheet. The bell is filled when the note has at least one
reminder and outlined when it has none, and it is not offered for a note in the bin. Each reminder is one row reading
`name (date, time)` in the app's date and time formats, and the last row is **New reminder**. Tapping a reminder row
closes the sheet and opens that reminder for editing.

**Acceptance criteria**
- AC1: The bell action is offered for every note outside the bin, filled when the note has reminders and outlined when
  it has none.
- AC2: Opening it shows a sheet titled "Reminders" with one row per reminder, each reading "<name> (<date>, <time>)"
  in the app's date and time formats.
- AC3: The last row of the sheet is "New reminder".
- AC4: Tapping a reminder row closes the sheet and opens that reminder in the edit dialog.

**Screens:** DLG-08, SCR-06
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-06`

### REM-07 Adding a reminder
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
**New reminder** opens a dialog titled *New reminder* with the name field focused and the keyboard open, **Set date**
and **Set time** buttons, and **Save**. The buttons show the value in the app's date and time formats and open the
system date and time pickers; any pick clears the seconds. A new reminder starts from the value the screen holds, which
is the time the screen was created rather than the time the dialog opens. **Save** with a time before now shows
**Cannot set a reminder for a past date** and saves nothing; a future time stores the reminder and sets its alarm at
once. The name may be left empty.

**Acceptance criteria**
- AC1: The dialog is titled "New reminder", focuses the name field with the keyboard open, and shows a date and a
  time in the app's formats.
- AC2: "Set date" opens a date picker and "Set time" a time picker, both starting at the shown value, and a picked
  value replaces the button text.
- AC3: Saving a date and time before now shows "Cannot set a reminder for a past date" and stores nothing.
- AC4: Saving a future date and time adds the reminder to the note and sets its alarm immediately; an empty name is
  accepted.

**Screens:** DLG-08
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-07`

### REM-08 Editing and deleting a reminder
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Tapping a reminder opens the same dialog titled *Reminder* with its name and time loaded, plus a **Delete** action
next to **Save**. **Save** updates that reminder in place and moves its alarm to the new time; the past-time check is
the same as for a new one. **Delete** removes the reminder from the note, cancels its alarm and closes the dialog, with
no confirmation and no undo. Editing never creates a second reminder.

**Acceptance criteria**
- AC1: Tapping a reminder opens the dialog with its name and date and time, and offers Delete.
- AC2: Save updates the same reminder and reschedules its alarm to the new time.
- AC3: Delete removes the reminder from the note, cancels its alarm and closes the dialog without asking for
  confirmation.
- AC4: Saving a past date and time is refused and leaves the stored reminder unchanged.

**Screens:** DLG-08
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-08`

### REM-09 Alarms are exact when allowed and inexact otherwise
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Each reminder owns one alarm, identified by its id and armed for its stored time; setting or changing the reminder
replaces the old alarm rather than adding a second. The alarm is an RTC wake-up alarm that also fires in Doze. On
Android 12 and later it is exact while the app holds the exact-alarm permission and inexact, so possibly late, when it
does not; older versions always get exact alarms. The manifest asks for `SCHEDULE_EXACT_ALARM`.

**Acceptance criteria**
- AC1: A reminder for time T has an alarm for T that wakes the device and fires in Doze.
- AC2: The alarm is exact when exact alarms are permitted and inexact otherwise.
- AC3: Setting or moving a reminder replaces its earlier alarm, so one reminder never has two.
- AC4: Before Android 12 the alarm is always exact.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-09`

### REM-10 The exact-alarm permission gates the reminder dialogs
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
On Android 12 and later, opening a reminder or **New reminder** first checks whether the app may schedule exact
alarms. If it may not, the app opens the system *Alarms & reminders* screen for the app and shows no dialog; the user
has to come back and tap again. When the permission is granted, the system broadcasts the change and every stored
reminder is scheduled again. After it is revoked the app is not told, so the next reschedule, on boot or sync, falls
back to inexact alarms.

**Acceptance criteria**
- AC1: Without the permission, tapping a reminder or "New reminder" opens the system exact-alarm settings for the app
  and no dialog.
- AC2: With the permission, the dialog opens as usual.
- AC3: On the permission-state-changed broadcast every stored reminder is scheduled again.
- AC4: A reschedule after the permission is revoked uses inexact alarms.

**Screens:** DLG-08
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-10`

### REM-11 A reminder fires a notification
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
When an alarm fires and its reminder still exists, the app posts one notification on the high-importance **Reminders**
channel: title **You have a reminder.** and the reminder's name as the body, or the note's title when the name is
empty. It has no action buttons; tapping it opens that note in the editor and clears it. The reminder is deleted as it
fires, so it cannot fire twice. If the reminder was removed since the alarm was set, nothing is posted.

**Acceptance criteria**
- AC1: Firing posts one notification on the Reminders channel with title "You have a reminder." and the reminder's
  name as its text.
- AC2: An empty name shows the note's title instead.
- AC3: Tapping the notification opens the note's editor and clears it; there are no action buttons.
- AC4: The reminder is deleted when it fires, so it never fires twice.
- AC5: If the reminder no longer exists, no notification is posted.

**Screens:** SCR-15
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-11`

### REM-12 Reminders survive a reboot and missed ones are dropped
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Alarms are lost when the device restarts. On boot the app reschedules every stored reminder, so a future reminder
still fires; a reminder whose time passed while the device was off, or any reminder already expired, is deleted in
that pass and never notifies. The app shows no summary of missed reminders. The same sweep runs after a sync.

**Acceptance criteria**
- AC1: After a reboot every reminder whose time is still in the future has an alarm again.
- AC2: A reminder that expired is deleted during the boot pass.
- AC3: A reminder that came due while the device was off produces no notification.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-12`

### REM-13 Binning, deleting or removing a note's reminders cancels its alarms
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Binning a note cancels all of its alarms before the note leaves the list; with deletion set to instant the note is
deleted instead, and permanently deleting a note also cancels them. Deleting one reminder in its dialog cancels only
that alarm. Archiving, unarchiving, hiding, pinning or editing a note leaves its alarms alone, so an archived note
still reminds. Restoring a binned note does not re-arm its alarms by itself; the next sync does.

**Acceptance criteria**
- AC1: Binning or permanently deleting a note cancels every alarm it had.
- AC2: Deleting one reminder in its dialog cancels that alarm only.
- AC3: Archiving a note leaves its alarms set.
- AC4: Restoring a note sets no alarm until the next sync.

**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-13`

### REM-14 Android 13 asks for the notification permission
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
On Android 13 and later, opening the reminder dialog while the app's notifications are disabled launches the system
`POST_NOTIFICATIONS` request. If the user refuses, the dialog closes and nothing is saved; if the user allows, the
dialog stays and can be saved. The app asks again each time the dialog opens while notifications are still disabled,
and it explains nothing first.

**Acceptance criteria**
- AC1: On Android 13 or later, with notifications disabled, opening the reminder dialog shows the system
  notification-permission request.
- AC2: Refusing closes the dialog and saves nothing.
- AC3: Allowing leaves the dialog open and the reminder can be saved.
- AC4: The request is made again on each opening while notifications remain disabled.

**Screens:** DLG-08
**Gherkin:** [`reminders.feature`](../features/reminders.feature) — `@REM-14`
