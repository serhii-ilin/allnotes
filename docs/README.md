# AllNotes documentation

| Document | What it holds |
|---|---|
| [CONSTITUTION.md](CONSTITUTION.md) | Non-negotiable principles. Everything else must respect them. |
| [requirements/](requirements/README.md) | What the app must do, one file per feature area, each requirement with an ID, a status, its acceptance criteria, its Gherkin pointer and its tests. Includes the changelog. |
| [features/](features/) | The Gherkin: one `.feature` file per feature area, every requirement covered by at least one Scenario, every Scenario tagged with its tests or `@gap`. |
| [use-cases.md](use-cases.md) | What a user does with the app: actor, goal, main flow, alternatives, linked requirements. |
| [scenarios.md](scenarios.md) | Index of the concrete Given/When/Then stories; the Gherkin itself lives in [features/](features/). |
| [audits/](audits/) | Dated review reports. Findings are linked from the requirements they affect until they are closed. |
| [folder-vault-sync.md](folder-vault-sync.md) | Architecture notes for the folder vault. |
| [spec/](spec/) | The notes folder contract, byte for byte: [vault-format.md](spec/vault-format.md), [algorithms.md](spec/algorithms.md), [data-model.md](spec/data-model.md) and the conformance [fixtures](spec/fixtures/README.md). What any implementation must match to share a folder. |
| [ui/](ui/README.md) | Screen and dialog catalogue (`SCR-xx`, `DLG-xx`) with ASCII wireframes, the PlantUML navigation map and state diagrams. |
| [platform/android.md](platform/android.md) | Core versus Platform requirements, and the capability contract a port to another platform implements. |
| [traceability.md](traceability.md) | Generated: every requirement with its use cases, screens, scenarios and tests; every menu item, destination, setting, component, history op and front-matter key with its requirement. |

## Keeping these documents current (mandatory)

The rule below is also in `AGENTS.md`, `GEMINI.md`, `.claude/CLAUDE.md` and `.cursor/rules/requirements.mdc`, so every
coding agent follows it.

1. **A new or changed requirement is written down before it is implemented.** When the owner asks for new behavior,
   add or edit the requirement in `docs/requirements/<area>.md` with status `Proposed`. When code and tests land, move
   it to `Implemented` and list the tests.
2. **Every change to user-visible behavior, the file format or sync semantics updates the docs in the same change:**
   - the requirement text and status (never reuse an ID; a dropped requirement becomes `Retired` with a reason);
   - the use case, if a user flow changed;
   - the scenario, if the behavior spans steps, devices or failures, with a link to its test;
   - one line in the [changelog](requirements/README.md#changelog): date, IDs, what changed.
3. **Defects are tracked against requirements.** An audit finding or a bug that breaks a requirement sets its status to
   `Partial` or `Defect` and links the finding. Closing the finding restores the status.
4. **Conflicts are escalated.** A request that contradicts the [constitution](CONSTITUTION.md) or an `Implemented`
   requirement is raised with the owner before code is written.
5. **Every requirement has acceptance criteria.** In its requirements file, right after the prose, it lists 2–5
   observable, testable criteria (`AC1`, `AC2`, …) derived from the prose and from what the linked tests assert.
   Pointer requirements ("See SYN-08") have no block.
6. **Every feature area has Gherkin in [features/](features/).** Every non-pointer requirement is covered by at least
   one Scenario. Every Scenario is tagged with its requirement IDs and with `@test:<Class>.<method>` for each
   automated test that proves it, or `@gap` when no test proves it yet. Never tag a test that does not assert the
   Scenario's `Then` steps.
7. **A new or changed requirement updates its acceptance criteria and Gherkin in the same change.** A `@gap`
   Scenario is a tracked defect, not an exception.
8. **[scenarios.md](scenarios.md) is an index; the Gherkin is the source.** The `SC-xx` IDs live on the Scenarios in
   the feature files.
9. **The folder contract is normative.** A change to the note file format, ids, names, hashes or folder history updates
   [spec/](spec/) and its [fixtures](spec/fixtures/README.md) in the same change; `VaultContractFixturesTest` must
   pass. A change to a screen updates its wireframe in [ui/](ui/README.md).
10. **Every requirement has a scope.** Its status line says `**Scope:** Core` (identical on every platform) or
    `**Scope:** Platform` (same intent, platform mechanism; see [platform/android.md](platform/android.md)).
11. **Check before finishing.** `python3 scripts/check-docs.py` (IDs, ACs, scenarios, test names, screen references)
    and `python3 scripts/gen-traceability.py --check` (nothing in the code is untraced; the matrix is current) must
    both pass.
