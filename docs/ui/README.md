# Screens and dialogs

Every screen, dialog and system surface of AllNotes, with a stable ID, its wireframes and the requirements that define
its behavior. Wireframes are ASCII in Markdown (readable by people and agents without a renderer); navigation and
state diagrams are PlantUML.

- Wording is platform-neutral: *top bar* (title and actions), *overflow* (the "⋮" menu), *bottom bar*, *FAB* (the
  floating new-note / mode button), *sheet* (a panel sliding up), *snackbar* (a short message at the bottom).
- The wireframes show layout and content, not visual design. Labels are the English strings the app shows today.
- Behavior is defined by the linked requirements; a wireframe never overrides a requirement. When the UI changes,
  update the wireframe in the same change (see [docs/README.md](../README.md)).
- [navigation.puml](navigation.puml) is the map of how the screens connect; [states.puml](states.puml) holds the
  editor-mode and notes-folder lifecycles.

## Screens

| ID | Screen | Wireframes | Requirements |
|---|---|---|---|
| SCR-01 | All Notes | [notes-lists.md](screens/notes-lists.md#scr-01-all-notes) | NTL-01…NTL-11, NTL-14, SYN-02, UI-02 |
| SCR-02 | Notebook | [notes-lists.md](screens/notes-lists.md#scr-02-notebook) | NTL-13, ORG-08 |
| SCR-03 | Archive | [notes-lists.md](screens/notes-lists.md#scr-03-archive) | NTL-08, NTL-10, NTL-11 |
| SCR-04 | Bin (Deleted) | [notes-lists.md](screens/notes-lists.md#scr-04-bin) | NTL-09, DEL-01, DEL-02, DEL-11 |
| SCR-05 | Search | [notes-lists.md](screens/notes-lists.md#scr-05-search) | NTL-12, ORG-13 |
| SCR-06 | Editor | [editor.md](screens/editor.md) | EDT-01…EDT-14, MED-04, MED-05, REM-06, ORG-12 |
| SCR-07 | Manage notebooks | [organization.md](screens/organization.md#scr-07-manage-notebooks) | ORG-01…ORG-06 |
| SCR-08 | Tags (manage and select) | [organization.md](screens/organization.md#scr-08-tags) | ORG-09…ORG-12 |
| SCR-09 | Settings | [settings.md](screens/settings.md#scr-09-settings) | SET-01…SET-14, FLD-01, SYN-03, WRS-04, CON-02, BAK-03, BAK-04 |
| SCR-10 | Folder history | [settings.md](screens/settings.md#scr-10-folder-history) | HST-07, HST-11 |
| SCR-11 | About | [settings.md](screens/settings.md#scr-11-about) | INT-08 |
| SCR-12 | Media viewer | [media.md](screens/media.md#scr-12-media-viewer) | MED-06, MED-07, MED-08 |
| SCR-13 | Navigation drawer | [notes-lists.md](screens/notes-lists.md#scr-13-navigation-drawer) | UI-01, UI-05, ORG-07, ORG-08, FLD-02 |
| SCR-14 | Home-screen widget | [system.md](screens/system.md#scr-14-home-screen-widget) | INT-05…INT-07 |
| SCR-15 | Notifications | [system.md](screens/system.md#scr-15-notifications) | REM-11, MED-08, BAK-05, INT-12 |
| SCR-16 | Entry points from the system | [system.md](screens/system.md#scr-16-entry-points-from-the-system) | INT-01…INT-04, MED-11 |

## Dialogs and sheets

| ID | Dialog | Wireframes | Requirements |
|---|---|---|---|
| DLG-01 | Choose a notes folder (onboarding) | [settings.md](screens/settings.md#dlg-01-choose-a-notes-folder) | FLD-02, UC-01 |
| DLG-02 | Keep using the old folder? (upgrade) | [settings.md](screens/settings.md#dlg-02-keep-using-the-folder-from-the-old-version) | FLD-04, FLD-05 |
| DLG-03 | Switch folder: move or open as-is | [settings.md](screens/settings.md#dlg-03-switch-the-notes-folder) | FLD-07, FLD-08, FLD-09 |
| DLG-04 | Folder from another app (warning) | [settings.md](screens/settings.md#dlg-04-folder-provided-by-another-app) | FLD-10 |
| DLG-05 | Create or rename a notebook | [organization.md](screens/organization.md#dlg-05-notebook-name) | ORG-01…ORG-03, FMT-11 |
| DLG-06 | Create or rename a tag | [organization.md](screens/organization.md#dlg-06-tag-name) | ORG-09 |
| DLG-07 | Move to notebook | [organization.md](screens/organization.md#dlg-07-move-to-notebook) | NTL-11, EDT-10 |
| DLG-08 | Reminders sheet and reminder dialog | [editor.md](screens/editor.md#dlg-08-reminders) | REM-06…REM-08, REM-10, REM-14 |
| DLG-09 | Note colour | [editor.md](screens/editor.md#dlg-09-note-colour) | EDT-09 |
| DLG-10 | Insert link | [editor.md](screens/editor.md#dlg-10-dlg-11-dlg-12-insert-link-image-table) | EDT-05 |
| DLG-11 | Insert image | [editor.md](screens/editor.md#dlg-10-dlg-11-dlg-12-insert-link-image-table) | EDT-05 |
| DLG-12 | Insert table | [editor.md](screens/editor.md#dlg-10-dlg-11-dlg-12-insert-link-image-table) | EDT-05 |
| DLG-13 | Record audio | [media.md](screens/media.md#dlg-13-record-audio) | MED-03 |
| DLG-14 | Attachment actions and description | [media.md](screens/media.md#dlg-14-attachment-actions) | MED-04, MED-09, MED-10 |
| DLG-15 | Empty bin | [notes-lists.md](screens/notes-lists.md#dlg-15-empty-bin) | DEL-11 |
| DLG-16 | Sync conflicts | [settings.md](screens/settings.md#dlg-16-sync-conflicts) | CON-02 |
| DLG-17 | Clear unfinished saves | [settings.md](screens/settings.md#dlg-17-clear-unfinished-saves) | WRS-04 |
| DLG-18 | Preference picker | [settings.md](screens/settings.md#dlg-18-preference-picker) | SET-02…SET-13 |
| DLG-19 | What's new | [settings.md](screens/settings.md#dlg-19-whats-new) | INT-09 |
| DLG-20 | Open-source libraries | [settings.md](screens/settings.md#dlg-20-libraries) | INT-08 |
| DLG-21 | Single-note actions (long press) | [notes-lists.md](screens/notes-lists.md#dlg-21-single-note-actions) | NTL-11, NTL-12 |

## Shared patterns
- **Note card** — [notes-lists.md](screens/notes-lists.md#note-card) (NTL-02, NTL-03, MED-04).
- **Selection mode** — every note list, Manage notebooks and Tags switch their top bar to a selection bar with a count
  and the bulk actions; the FAB and bottom bar disappear (NTL-10, ORG-05, ORG-10).
- **Pull to refresh** — note lists and Folder history run a sync or reload (NTL-01, SYN-02).
- **Snackbars** — results of folder actions and kept-for-safety messages (UI-02, DEL-04, Article 5.3).
