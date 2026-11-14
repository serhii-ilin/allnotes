# Drawer and Settings UI (UI)

Use cases: UC-01, UC-11, UC-13, UC-14. Gherkin: [ui.feature](../features/ui.feature).

### UI-01 The drawer shows the folder, not a provider
**Status:** Implemented · **Scope:** Core · **Tests:** none yet
With a folder configured, the drawer header shows the folder path (up to two lines, ellipsized) and no provider label.
Without one, it shows the prompt to choose a folder.

**Acceptance criteria**
- AC1: With a folder configured, the drawer header shows the folder path.
- AC2: The header shows no provider label such as "External storage" or "File storage".
- AC3: With no folder, the header asks the user to choose one.

**Screens:** SCR-13
**Gherkin:** [`ui.feature`](../features/ui.feature) — `@UI-01`

### UI-02 Folder actions give visible feedback
**Status:** Implemented (not verified on a device) · **Scope:** Core
The Settings section is **Notes folder**. **Rescan notes folder** and **Clear unfinished saves** are disabled and show
progress while running. Afterwards the row keeps the result (notes indexed, failure reason, items cleared, or nothing
to clear), and the result is announced once.

**Acceptance criteria**
- AC1: The action is disabled and shows progress while it runs.
- AC2: Afterwards the row keeps the result.
- AC3: The result is announced exactly once.
- AC4: A count in a result uses a plural resource, so "1 note" is correct.

**Screens:** SCR-01
**Gherkin:** [`ui.feature`](../features/ui.feature) — `@UI-02`

### UI-03 No background-sync switch
**Status:** Implemented (decision 2026-11-10) · **Scope:** Core · **Tests:** none yet
The app re-reads the folder on resume, hourly, on observer events and on request. A switch would only hide notes that
arrived while the app was closed, so there is none.

**Acceptance criteria**
- AC1: Settings has no switch that turns background scanning off.
- AC2: Notes that arrived while the app was closed appear on the next resume without user action.

**Gherkin:** [`ui.feature`](../features/ui.feature) — `@UI-03`

### UI-04 Sync conflicts are listed in Settings
See CON-02.

### UI-05 Drawer header layout does not overlap
**Status:** Implemented · **Scope:** Platform · **Tests:** none yet
When the drawer is open, the header stays opaque and lays out below the status bar. The vault path (or onboarding
prompt), sync-settings control, and app name each have a distinct place; nothing from the main screen toolbar shows
through the header.

**Acceptance criteria**
- AC1: The drawer header background is opaque (no main-toolbar bleed-through beside or behind the header).
- AC2: The vault path or onboarding text sits in the top header band with the sync-settings control aligned to that
  band only (not spanning the app-name row).
- AC3: The app name row sits below the vault band without overlapping vault text.
- AC4: Top padding respects the status bar under edge-to-edge display.

**Screens:** SCR-13
**Gherkin:** [`ui.feature`](../features/ui.feature) — `@UI-05`
