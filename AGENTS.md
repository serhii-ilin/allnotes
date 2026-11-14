<!-- infigraph-instructions -->

# Infigraph — Code Intelligence

This project is indexed by Infigraph. Use Infigraph tools FIRST for all code tasks. Fall back to grep/read only if
Infigraph returns nothing or for non-code files.

## Rules

1. Check `list_projects` before indexing — don't re-index
2. **`search`** for ALL code search — hybrid BM25+vector+grep in one call, auto-escalates
3. **`get_doc_context`** before editing any function — returns source+callers+callees in one call
4. **`trace_callers`** / **`find_all_references`** before refactoring — never grep for callers
5. **`trace_callees`** / **`transitive_impact`** for blast radius — never manually trace call chains
6. Read files directly only for non-code files (configs, docs, manifests) or edit tool line-number context

## Workflows

- **Find code:** `search` → if need symbol detail: `get_code_snippet` or `symbol_context`
- **Before editing:** `get_doc_context`
- **Before refactoring:** `find_all_references` → `transitive_impact` → edit
- **Onboarding:** `index_project` → `get_architecture` → `get_stats`
- **Multi-repo:** `group_create` → `group_add` × N → `group_index` → `group_sync` → `group_link`

> Each tool description says what it replaces — check descriptions when unsure which tool to use.

## Session Continuity — MANDATORY

- **On session start:** MUST call `get_latest_session` to resume prior context
- **After context compaction:** if you see "continued from a previous conversation" or a compaction summary, IMMEDIATELY
  call `save_session` with whatever context survived before doing anything else
- **MUST call `save_session` IMMEDIATELY (before responding to the user)** when ANY of these occur. No session-end
  signal exists — if you don't save now, context is lost forever:
    1. **Finding** — root cause identified, discovered a bug, learned how something works
    2. **Milestone** — bug fixed and verified, feature committed, test passing, build green
    3. **Decision** — chose an approach, ruled something out, changed strategy
    4. **Task done** — any pending task from a prior session is completed
    5. **Periodic** — if you have NOT called `save_session` in the last 5 exchanges with the user, call it NOW
       regardless of whether anything dramatic happened. This is a hard rule, not a suggestion.
- Do NOT defer saves ("I'll save later"). Do NOT batch them. Do NOT wait for user to ask.
- "Later" does not exist — context compaction or session end can happen at any moment.
- Same-day saves merge: summary/pending_tasks overwrite, decisions append, files_touched union
- **Narrative dumps:** On every `save_session`, include `narrative` field with full session story — what was explored,
  found, reasoned, decided, and why. Chronological prose, not terse bullets. Written to
  `.infigraph/sessions/session_YYYY-MM-DD.md` and embedded for semantic search. On session start, if
  `get_latest_session` shows a narrative log path, read it when structured fields aren't enough context.

<!-- infigraph-instructions -->

# Product spec: requirements, use cases and scenarios (MANDATORY)

The product spec lives in `docs/`: [CONSTITUTION](docs/CONSTITUTION.md), [requirements/](docs/requirements/README.md)
(one file per feature area, IDs like `IDN-03`), [use-cases.md](docs/use-cases.md) and
[scenarios.md](docs/scenarios.md) (index of the Gherkin in `docs/features/`). The full rules are in [docs/README.md](docs/README.md).

- **Record before you build.** When the owner states a new or changed requirement, add or edit it in
  `docs/requirements/<area>.md` with status `Proposed` before writing code. Set it to `Implemented` (or `Uncommitted`)
  and list its tests when the code lands.
- **Same change, same docs.** Any change to user-visible behavior, the note file format or sync semantics updates, in
  the same change: the requirement text and status, the use case if a user flow changed, the scenario (with its test)
  if the behavior spans steps, devices or failures, and one changelog line in `docs/requirements/README.md`.
- **Never reuse IDs.** A dropped requirement stays with status `Retired` and a reason.
- **Track defects.** A known bug or audit finding sets the requirement to `Partial` or `Defect` and links the finding
  (`docs/audits/`). Closing it restores the status.
- **Escalate conflicts.** A request that contradicts the constitution or an `Implemented` requirement is raised with
  the owner before any code is written.
- **Acceptance criteria and Gherkin for every feature.** Each requirement lists its acceptance criteria in its
  requirements file. Each feature area has a Gherkin file in `docs/features/<area>.feature`. Every requirement is
  covered by at least one Scenario, and every Scenario is tagged with its requirement IDs (`@IDN-03`) and with
  `@test:<TestClass>.<method>` for each automated test that proves it, or `@gap` if none does yet. A new or changed
  requirement updates its acceptance criteria and Gherkin in the same change, and the tagged test is written first.
  A `@gap` scenario is a tracked defect. `docs/scenarios.md` is only an index; the Gherkin is the source.
- **Contract, screens and checks.** A change to the note file format, ids, names, hashes or folder history updates
  `docs/spec/` and its fixtures (`docs/spec/fixtures/`, proven by `VaultContractFixturesTest`) in the same change. A
  UI change updates its wireframe in `docs/ui/`. Every requirement carries `**Scope:** Core` or `Platform`
  (`docs/platform/android.md`). Before finishing a change, `python3 scripts/check-docs.py` and
  `python3 scripts/gen-traceability.py --check` must both pass.
