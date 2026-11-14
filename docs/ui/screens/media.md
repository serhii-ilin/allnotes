# Media

Back to [screens](../README.md). Behavior: [media.md](../../requirements/media.md).

## SCR-12 Media viewer
Opens from an attachment tile when "Open media in" is the internal player and the type is image, video or audio
(MED-05, SET-09); other files open in another app.

```text
Image (MED-06)                       Video (MED-07)                       Audio (MED-08)
┌────────────────────────────┐       ┌────────────────────────────┐       ┌────────────────────────────┐
│ ←  photo.jpg               │       │ ←  clip.mp4                │       │ ←  recording.m4a           │
│▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│       │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│       │   background from album art│
│▓▓▓▓▓  zoom / pan  ▓▓▓▓▓▓▓▓▓│       │▓▓▓▓▓▓  video  ▓▓▓▓▓▓▓▓▓▓▓▓▓│       │        ┌──────┐            │
│▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│       │▓▓  ⏮   ▶   ⏭   ─────●── ▓│       │        │  ♪   │  art / icon│
│                            │       │                            │       │        └──────┘            │
│ top bar shows on tap, hides│       │ top bar follows controls;  │       │   ▶/❚❚   ───────●──────    │
│ after 3 s; dark background │       │ screen stays on            │       │ plays on with screen off   │
└────────────────────────────┘       └────────────────────────────┘       └────────────────────────────┘
```

## DLG-13 Record audio
```text
┌──────────────────────────────┐
│ Recording (0:01:23)          │  elapsed h:mm:ss; asks for the microphone first (MED-03)
│                              │
│            ( ■ )             │  stop → the clip is attached to the note
│                   [Cancel]   │  Cancel stops and attaches nothing; outside taps do not dismiss
└──────────────────────────────┘
```

## DLG-14 Attachment actions
Long press on a tile of a live note (MED-04).
```text
Sheet                                   Edit description (MED-09)
┌──────────────────────────────┐        ┌──────────────────────────────┐
│ photo.jpg                    │        │ Edit description             │
│ ✎  Edit description          │──tap──▶│ [ Attachment description  ]  │
│ 🗑  Delete                    │        │            [Cancel] [Save]   │
│ ↗  Share                     │        └──────────────────────────────┘
└──────────────────────────────┘
```
Delete removes the attachment from the note (MED-10; non-image links stay in the body — open finding).
