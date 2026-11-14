@media
Feature: Attachments and media
  A note can carry photos, videos, audio recordings and other files. The app copies them into its own storage, links them
  from the note, and opens them in a built-in viewer or another app. Where the files live and how they are linked is the
  file format's business (FMT-05, FMT-10); this is what the user does with them.

  Background:
    Given the app keeps its own copies of attachments in a private media folder
    And the user's note "Plan" is open in the editor

  @MED-01 @test:MediaStorageManagerPersistUnitTest.persistFromUri_success_copiesVerified @test:MediaStorageManagerPersistUnitTest.persistFromUri_hashMismatch_deletesDest @test:MediaStorageManagerPersistUnitTest.persistFromUri_emptyFile_returnsNull_sourceNotCopied @test:MediaStorageManagerPersistUnitTest.persistFromUri_missingSource_returnsNull @test:MediaStorageManagerOriginalNameTest.uniqueMediaFileName_preservesSanitizedOriginal @test:AttachmentUtilsTest
  Scenario: Chosen files are copied into the app and attached to the note
    Given the user picks "Holiday Photo.jpg" and "clip.mp4"
    When the app imports them
    Then each file is copied into the private media folder and verified byte for byte
    And an empty, unreadable or mismatched copy is deleted again and adds no attachment
    And the image and the video get their type from the MIME type, an unknown type being generic
    And "Holiday Photo.jpg" is kept as the description, and a second file with that name becomes "Holiday Photo-2.jpg"

  @MED-01 @gap
  Scenario: The file picker offers images, video, audio, PDFs and text
    Given the user taps "Attach files"
    When the system picker opens
    Then it offers image, video, audio, PDF and text files
    And more than one file can be selected at once
    # Gap: the picker's MIME filter and multiple selection have no automated test.

  @MED-01 @gap
  Scenario: A binned note offers no attach actions
    Given the note is in the bin
    When the user opens its menu
    Then "Attach files", "Take a photo" and "Record audio" are not offered
    # Gap: hiding the attach actions for a binned note has no automated test.

  @MED-02 @gap
  Scenario: Taking a photo adds it to the note
    Given the user taps "Take a photo"
    When the camera app saves the picture into the file the app prepared
    Then the picture is copied into private storage and added to the note
    And cancelling the camera adds nothing
    And with no camera app installed the note is unchanged
    # Gap: the camera result is handled in the fragment only; no test covers it.

  @MED-03 @gap
  Scenario: The microphone is asked for before recording starts
    Given the user taps "Record audio"
    When the app does not have the microphone permission
    Then the dialog stays hidden until the permission is granted
    And a refusal closes the dialog and records nothing
    # Gap: the permission flow is handled in the dialog only; no test covers it.

  @MED-03 @gap
  Scenario: A recording becomes an audio attachment
    Given the microphone permission is granted
    When the dialog opens
    Then recording starts and the title shows the elapsed time as "h:mm:ss"
    And a tap outside the dialog does not dismiss it
    When the user stops the recording
    Then the note gains one ".m4a" audio attachment, described by its file name or "Recorded Clip"
    But cancelling stops the recorder and adds no attachment
    And a recording stops by itself after two hours
    # Gap: RecorderService and the record dialog have no automated test.

  @MED-04 @gap
  Scenario: The note shows a tile for each attachment
    Given the note has a photo, a video, a recording and a PDF
    When the user opens the note
    Then each one has a tile with a picture or an icon for its type
    And the description is printed under the tile
    And a long press offers "Edit description", "Delete" and "Share"
    # Gap: tiles are built in the recycler view; no test renders them.

  @MED-04 @gap
  Scenario: A note in a list shows at most four tiles
    Given the note has six attachments
    When it is listed on the main screen
    Then four tiles are shown and the last one says "+2"
    And a compact-preview note shows no tiles
    # Gap: the note list preview has no automated test for attachments.

  @MED-05 @test:AttachmentUtilsFailSafeTest.vaultrel_missingOnDisk_returnsNull @test:AttachmentUtilsFailSafeTest.malformedVaultrel_returnsNull @test:AttachmentUtilsFailSafeTest.rawRelativePath_notPassedToResolver_returnsNull
  Scenario: An attachment whose file is gone opens nothing
    Given the note references "Plan/photo.jpg" and that file is not on this device
    When the user taps its tile
    Then the attachment resolves to no address
    And no other app is started for it

  @MED-05 @gap
  Scenario: "Open media in" chooses the player
    Given "Open media in" is "External player"
    When the user taps a video attachment
    Then another app opens it, through the system chooser
    But with "Internal player" the built-in viewer opens instead
    And a PDF is opened by another app in either setting
    # Gap: the tile click has no automated test.

  @MED-06 @gap
  Scenario: An image opens full screen and can be zoomed
    Given the note has the photo "Holiday Photo.jpg"
    When the user taps its tile with "Internal player" set
    Then the photo fills the screen with its description in the toolbar and a back arrow
    And it can be zoomed and panned
    And the toolbar hides three seconds after a tap and the next tap brings it back
    And an unreadable photo shows a file icon
    # Gap: MediaActivity has no automated test.

  @MED-07 @gap
  Scenario: A video plays inside the app
    Given the note has a video
    When the user opens it
    Then it plays with the built-in controls and the screen stays on
    And a rotation resumes it at the same position and play state
    And audio the app was playing stops
    # Gap: MediaActivity's video player has no automated test.

  @MED-08 @gap
  Scenario: Audio keeps playing in the background
    Given the user plays a recording with "Internal player" set
    When the user turns the screen off or leaves the app
    Then playback continues
    And the notification shows the file name and the album art, with play/pause and stop
    And stop in the notification also closes the built-in player
    And the player shows play/pause and a seek bar, with a music icon when there is no album art
    And another app taking the audio focus pauses playback until it is given back, unless the user paused it
    And with the notification permission refused the notification is not shown
    # Gap: MusicService and its notification have no automated test.

  @MED-09 @gap
  Scenario Outline: An attachment's description can be edited
    Given the note has the audio attachment "clip.m4a" described as "<description>"
    When the user long-presses the tile and chooses "Edit description"
    Then the dialog shows "<description>"
    And the keyboard is opened only when the description is empty
    When the user saves "<new>"
    Then the description of that attachment and the note's modified date change
    # Gap: the edit dialog has no automated test.

    Examples:
      | description | new       |
      | clip.m4a    | Interview |
      |             | Recorded  |

  @MED-09 @test:VaultAttachmentsTest.mergeAttachmentLinks_nonImageUsesFileLink
  Scenario: A non-image attachment's description is its link label
    Given the note gains the audio attachment "clip.m4a"
    When the note is exported and its link is written
    Then the body contains "[clip.m4a](Plan/clip.m4a)"
    And an image is embedded as "![…](…)" instead

  @MED-10 @gap
  Scenario: Deleting a tile removes the attachment from the note
    Given the note has a photo and a recording
    When the user long-presses the photo and chooses "Delete"
    Then the photo's tile is gone, the recording stays, and the note's modified date changes
    # Gap: deleting an attachment through the bottom sheet has no automated test.

  @MED-10 @test:VaultAttachmentMembershipTest.exportStripsRemovedLinkWithoutBodyRewrite @test:VaultAttachmentMembershipTest.exportSkipsFencedCodeBlock @test:VaultAttachmentMembershipTest.exportStripsInlineLink_keepsSurroundingProse
  Scenario: The removed image's embed goes and the prose stays
    Given the note body embeds the photo and shows the same text in a code block
    When the photo is no longer in the note and the note is exported
    Then only the embed is removed
    And the surrounding prose and the code block are unchanged

  @MED-10 @test:VaultAttachmentMembershipTest.export_doesNotStripNonImageFileLinks
  Scenario: A removed audio or PDF keeps its file link in the body
    Given the note body links "doc.pdf" with a file link
    When the PDF is no longer in the note and the note is exported
    Then the "[doc](Note/doc.pdf)" link is still in the body
    # Today's behavior: only image embeds are stripped from the body when an attachment is removed.

  @MED-11 @gap
  Scenario: Media shared from another app becomes a new note
    Given the user shares "photo.jpg" from the gallery to AllNotes
    When AllNotes opens
    Then the editor opens a new note holding "photo.jpg" as an attachment
    And the shared title and text become its title and body
    And another share arriving while AllNotes is open opens a new note too
    # Gap: the share-intent handling in MainActivity has no automated test.

  @MED-11 @gap
  Scenario: Several shared files land on one note
    Given the user shares three videos to AllNotes at once
    When AllNotes opens
    Then the new note holds all three as attachments
    And a file whose copy fails is dropped
    # Gap: the share-intent handling in MainActivity has no automated test.

  @MED-12 @gap
  Scenario: Copies no note refers to are deleted
    Given the private media folder holds "img_1.jpg" that no note refers to and "img_2.jpg" that it does
    When the user deletes a note instantly, or empties the bin
    Then "img_1.jpg" is deleted and "img_2.jpg" is kept
    And nothing is deleted while the bin keeps notes forever
    # Gap: MediaStorageManager.cleanUpStorage has no automated test.
