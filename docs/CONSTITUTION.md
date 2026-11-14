# AllNotes Constitution

These are the principles every change must respect. Requirements, use cases and code are judged against them. If a
requested change conflicts with an article, stop and ask the project owner before implementing it. Changing an
article needs the owner's explicit approval and an entry in the [requirements changelog](requirements/README.md#changelog).

## Article 1: The user's files are the product

1. A note is a Markdown (or plain-text) file in a folder the user chose. That folder is the source of truth for note
   text, attachments and portable metadata (notebook, tags, reminders, flags).
2. The app database (Room) is an index and cache. Anything in it that matters to the user must be recoverable from
   the folder, or be explicitly device-local (for example editor view settings).
3. AllNotes does not sync. Synchronization, backup and versioning are delegated to Syncthing or any tool that copies
   files. The app must behave correctly when files are added, changed, renamed, moved or deleted by something else,
   at any time, including while the app is running.

## Article 2: Never lose user data

1. Uncertainty means keep. A file missing from one scan, a failed or partial listing, an empty listing while notes are
   mapped, or many files vanishing at once is never treated as a deletion.
2. Deleting a file from the user's folder requires positive proof that the user asked for it (a user bin followed by
   permanent delete) and that the file is the note's own file and no other note's.
3. Binning moves a file into `.trash/` rather than deleting it. Permanent delete is the only path that removes note
   bytes.
4. When two versions disagree, keep both. Keep the app's version in the note and save the other as a visible conflict
   copy. Never silently overwrite text written elsewhere.
5. Writes are crash-safe. An interrupted write is recovered or quarantined on the next start, never read as the note's
   content.
6. Never act on a folder the user has not confirmed. Never let work queued for one folder land in another.

## Article 3: Interoperate, don't own

1. Files must stay readable and editable by other tools (Obsidian, text editors, desktop apps).
2. Front-matter keys the app does not own are preserved verbatim. Keys the app adds must not collide with keys other
   tools commonly use.
3. The app must not rewrite files it has no reason to change. Every rewrite is propagated by the sync tool to every
   device and can cause conflicts there.
4. Housekeeping files of other tools (`.obsidian/`, `.sync-conflict-*`, Android `.trashed-*`) are never imported and
   never modified.

## Article 4: Many devices, one truth

1. The same folder may be open on several devices running different app versions. Every behavior is designed for that
   case, not only for a single phone.
2. A note's identity survives renames and moves made anywhere, because the identity lives in the file.
3. Operations converge: running sync twice, or on two devices in any order, ends in the same state without duplicates,
   spurious conflicts or ping-pong rewrites.

## Article 5: Offline, private, honest

1. The app works fully offline. Sync needs no network and no account.
2. No note text in logs, analytics or crash reports. App-private caches and journals are excluded from Android backup.
3. The user can always see what happened: progress while an action runs, a result afterwards, and a clear message
   when something was kept or skipped for safety.

## Article 6: Engineering discipline

1. Every behavior change ships with a test that fails without it. Multi-step and multi-device behavior gets a scenario
   test on the real repository harness (`NoteRepositoryVaultHarness`), not only unit tests of helpers.
2. Dependency-injection wiring is covered by a test that builds the whole graph.
3. Requirements, use cases and scenarios are kept current as part of the change (see
   [docs/README.md](README.md)). Code that disagrees with a requirement is a defect in one of them, and the
   disagreement must be resolved, not left in place.
4. Audit findings are recorded under `docs/audits/` and linked from the requirements they affect until they are
   closed.
5. Every requirement has acceptance criteria in its requirements file: 2–5 observable, testable statements derived
   from the prose and from what the linked tests assert.
6. Every feature area has a Gherkin file in `docs/features/`. Every requirement is covered by at least one Scenario,
   and every Scenario is tagged with its requirement IDs and with `@test:<Class>.<method>` for each automated test
   that proves it, or `@gap`.
7. A new or changed requirement updates its acceptance criteria and Gherkin in the same change. A `@gap` Scenario is
   a tracked defect, not an exception.
8. `docs/scenarios.md` is an index; the Gherkin is the source.
