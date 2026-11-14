# Attachments and media (MED)

What the user does with the files a note carries: adding them from the device, the camera or a recording, seeing them in
the note, opening them in the built-in viewer or another app, labelling them and removing them, and how media shared
from another app becomes a note. Where the files are stored, what extension they get and how they are linked are in
[FMT-05](file-format.md) and [FMT-10](file-format.md). Use cases: UC-05.
Gherkin: [media.feature](../features/media.feature).

### MED-01 Attaching files from the device
**Status:** Implemented · **Scope:** Platform · **Tests:** `MediaStorageManagerPersistUnitTest.persistFromUri_success_copiesVerified`,
`MediaStorageManagerPersistUnitTest.persistFromUri_missingSource_returnsNull`,
`MediaStorageManagerPersistUnitTest.persistFromUri_emptyFile_returnsNull_sourceNotCopied`,
`MediaStorageManagerPersistUnitTest.persistFromUri_hashMismatch_deletesDest`,
`MediaStorageManagerOriginalNameTest.uniqueMediaFileName_preservesSanitizedOriginal`, `AttachmentUtilsTest`
**Attach files** in the editor — and on the main screen, where it starts a new note — opens the system file picker for
several image, video, audio, PDF or text files. Each chosen file is copied into the app's private media folder and added
to the note, and the note counts as modified. The copy is verified against the source: a file that cannot be read, is
empty or comes back different adds no attachment and the app reports the failure. The type comes from the MIME type
(image, video, audio, otherwise generic) and the description from the file name, which is kept, with a `-2` suffix when
that name is already taken. The attach actions are hidden for a binned note.

**Acceptance criteria**
- AC1: The picker offers image, video, audio, PDF and text files and allows more than one file at a time.
- AC2: Each chosen file becomes one attachment of the note, copied into private storage, and the note's modified date
  changes.
- AC3: A file that cannot be read, is empty, or does not match byte for byte adds no attachment and the failure is shown.
- AC4: The attachment's type follows its MIME type, an unknown or missing MIME type being generic, and its description is
  the original file name, kept unchanged unless that name is taken.
- AC5: A binned note offers no **Attach files**, **Take a photo** or **Record audio** action.

**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-01`

### MED-02 Taking a photo
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
**Take a photo** in the note (or on the main screen for a new note) opens the device's camera app, which writes into a
file the app has already created in its private media folder. A saved photo is then imported like any other attachment
and added to the note; a cancelled capture adds nothing. With no camera app installed the failure is only logged, and
the user sees no message.

**Acceptance criteria**
- AC1: A saved photo becomes an attachment of the note, with the same copy, type and naming rules as a picked file.
- AC2: Cancelling the camera adds nothing to the note.
- AC3: With no camera app installed the note is unchanged.

**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-02`

### MED-03 Recording audio
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
**Record audio** asks for the microphone before anything else and keeps its dialog hidden until the permission is
granted; a refusal closes it. Recording starts when the recorder is connected and writes AAC inside an MP4 file to the
private media folder. The title shows the elapsed time as `h:mm:ss`. Stopping returns the clip to the note as an audio
attachment described by its file name, or "Recorded Clip" when there is none. Recording stops by itself after two hours;
tapping outside the dialog does not dismiss it while recording; **Cancel** stops the recorder and adds no attachment.

**Acceptance criteria**
- AC1: The microphone permission is requested first, the dialog stays hidden until it is granted, and a refusal closes it
  without recording.
- AC2: Recording starts when the dialog opens and the title shows the elapsed time.
- AC3: Stopping adds one audio attachment, in an `.m4a` file, to the note; it is described by its file name or
  "Recorded Clip".
- AC4: Recording stops by itself after two hours, and **Cancel** adds no attachment.
- AC5: While recording, a tap outside the dialog does not dismiss it.

**Screens:** DLG-13
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-03`

### MED-04 The note shows a tile per attachment
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
Each attachment has a tile: a thumbnail for an image, a video frame with a film icon for video, the album art with a
music icon for audio, and a file icon otherwise. In the note the description is printed under the tile. A note in a list
shows at most four tiles, the last one marked `+N` for the rest, and a compact-preview note shows none. A long press on a
tile offers **Edit description**, **Delete** and **Share**; a binned note offers none.

**Acceptance criteria**
- AC1: Each tile shows a picture or an icon that matches the attachment's type.
- AC2: The description is shown under the tile in the note.
- AC3: A note in a list shows at most four tiles, the fourth marked `+N` when more exist.
- AC4: A compact-preview note shows no tiles.
- AC5: A long press offers **Edit description**, **Delete** and **Share**, and nothing on a binned note.

**Screens:** DLG-14, SCR-06
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-04`

### MED-05 Opening an attachment
**Status:** Implemented · **Scope:** Core · **Tests:** `AttachmentUtilsFailSafeTest.vaultrel_missingOnDisk_returnsNull`,
`AttachmentUtilsFailSafeTest.malformedVaultrel_returnsNull`,
`AttachmentUtilsFailSafeTest.rawRelativePath_notPassedToResolver_returnsNull`
Tapping a tile opens the attachment. With **Open media in** set to *Internal player* (the default) images, video and
audio open in the built-in viewer; with *External player* they are handed to another app through the system chooser. A
generic attachment always goes to another app, in either setting. An attachment whose file cannot be found resolves to
no address: no other app is started for it, and the built-in viewer has only a placeholder to show.

**Acceptance criteria**
- AC1: With *Internal player*, tapping an image, video or audio attachment opens the built-in viewer.
- AC2: With *External player*, that attachment is opened by another app through the system chooser.
- AC3: A generic attachment is opened by another app in either setting.
- AC4: An attachment whose file cannot be resolved has no address to open and starts no other app.

**Screens:** SCR-06
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-05`

### MED-06 The built-in image viewer
**Status:** Implemented · **Scope:** Core · **Tests:** none yet (@gap)
An image opens full screen on a dark background, with its description in the toolbar and a back arrow to close it. The
image can be zoomed and panned. The toolbar shows on a tap and hides again after three seconds. An image that cannot be
loaded shows a generic file icon instead of a broken picture.

**Acceptance criteria**
- AC1: An image opens full screen with the description in the toolbar and a back arrow that closes it.
- AC2: The toolbar hides three seconds after a tap, and a new tap shows it again.
- AC3: The image can be zoomed and panned.
- AC4: An unreadable image shows a file icon.

**Screens:** SCR-12
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-06`

### MED-07 The built-in video player
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
A video plays inside the app with a player and its own controls; the toolbar follows the controls' visibility. The
position and the play state survive rotation and leaving the screen, and the screen stays on while the video is open.
Starting a video stops the audio the app is playing.

**Acceptance criteria**
- AC1: A video plays inside the app with playback controls.
- AC2: After a rotation the video resumes at the same position and in the same play state.
- AC3: The screen stays on while the video is open.
- AC4: Starting a video stops audio playback in the app.

**Screens:** SCR-12
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-07`

### MED-08 Audio playback and its notification
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Audio plays through a foreground service, so it continues when the screen is off or the app is in the background. The
notification in the playback channel shows the file name and the album art, with play/pause and stop; stopping there also
closes the built-in player. The player has play/pause and a seek bar, and takes its background colour from the album art,
with a music icon when there is none. Playback pauses when another app takes the audio focus and resumes when it is given
back, unless the user paused it. The app does not ask for the notification permission for playback, so a user who never
granted it sees no notification and cannot stop playback from there.

**Acceptance criteria**
- AC1: Audio keeps playing with the screen off or the app in the background.
- AC2: The playback notification shows the file name and the album art with play/pause and stop; stop closes the in-app
  player.
- AC3: The player shows play/pause and a seek bar, and a music icon replaces missing album art.
- AC4: Another app taking the audio focus pauses playback, which resumes when the focus returns unless the user paused it.

**Screens:** SCR-12, SCR-15
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-08`

### MED-09 Editing an attachment's description
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultAttachmentsTest.mergeAttachmentLinks_nonImageUsesFileLink`
**Edit description** opens a dialog pre-filled with the current text, and the keyboard opens only when the description is
empty. Editing it does not rewrite a link that already exists in the note body: the description is the label the app shows
on the tile and in the viewer, and for audio, video and generic attachments it is also the text of the Markdown link
written when a link is created. Saving changes that one attachment and the note's modified date. A blank description is
accepted; wherever a label is needed the file name (or "image") is used instead.

**Acceptance criteria**
- AC1: The dialog shows the current description, and the keyboard opens only when it is empty.
- AC2: Saving changes the description of that attachment only and updates the note's modified date.
- AC3: A blank description is accepted and the file name (or "image") is used as the label instead.
- AC4: For a non-image attachment the description is the label of the Markdown link written for it.

**Screens:** DLG-14
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-09`

### MED-10 Removing an attachment from a note
**Status:** Implemented · **Scope:** Core · **Tests:** `VaultAttachmentMembershipTest.exportStripsRemovedLinkWithoutBodyRewrite`,
`VaultAttachmentMembershipTest.exportSkipsFencedCodeBlock`,
`VaultAttachmentMembershipTest.exportStripsInlineLink_keepsSurroundingProse`,
`VaultAttachmentMembershipTest.export_doesNotStripNonImageFileLinks`
**Delete** removes the attachment from the note and updates the note's modified date. On the next export the image embed
of a removed image is stripped from the body while the surrounding prose and code blocks are left alone; a file link of a
removed audio, video or generic attachment stays in the body. The stored file is not deleted by this action: a copy in
the app's private storage is reclaimed later by the storage cleanup (MED-12), and a copy in the notes folder stays there.

**Acceptance criteria**
- AC1: Deleting a tile removes only that attachment and updates the note's modified date.
- AC2: On export only the `![…](…)` embed of the removed image is stripped; the surrounding text and code blocks are
  unchanged.
- AC3: A `[description](path)` link of a removed audio, video or generic attachment is left in the body.
- AC4: The private copy that no note references any more is reclaimed by the storage cleanup, and the copy in the notes
  folder is kept.

**Screens:** DLG-14
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-10`

### MED-11 Sharing media into the app
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
Another app can share one image, video or audio file (`SEND`) or several of them (`SEND_MULTIPLE`); the app declares a
filter for each of those types. A shared file is copied into private storage first and the editor then opens a new note
holding it; several files become several attachments of that one note. `EXTRA_TITLE` becomes the note's title and
`EXTRA_TEXT` its body. When `EXTRA_STREAM` is missing, the first `ClipData` item is used, and every item for a multiple
share. A share that arrives while the app is already open is handled the same way, and a file whose copy fails is
dropped.

**Acceptance criteria**
- AC1: A single shared image, video or audio file opens a new note that holds it as an attachment.
- AC2: A multiple share puts every shared file on that one new note.
- AC3: The shared title and text become the note's title and body.
- AC4: Each file is copied into private storage before the note refers to it; a failed copy adds no attachment.
- AC5: A share received while the app is open still opens the new note.

**Screens:** SCR-16
**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-11`

### MED-12 Unused attachment copies are cleaned up
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet (@gap)
A file in the app's private media folder is kept only while a note refers to it. Deleting a note instantly, emptying the
bin, or the periodic bin purge deletes the copies no note refers to any more. A copy that was made but never attached — a
cancelled capture, an abandoned recording, a save that failed — is reclaimed the same way. The periodic purge only runs
the cleanup when a bin retention period is set, so with the bin keeping notes forever it never runs on its own.

**Acceptance criteria**
- AC1: A private copy that no note references is deleted by the cleanup.
- AC2: The cleanup runs after an instant deletion and after the bin is emptied.
- AC3: The periodic purge runs the cleanup only when a bin retention period is set.
- AC4: A copy that a note still references is never deleted.

**Gherkin:** [`media.feature`](../features/media.feature) — `@MED-12`
