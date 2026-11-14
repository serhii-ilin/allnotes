<!-- BEGIN INFIGRAPH v2 -->

## Infigraph — Code Intelligence (auto-generated)

This project is indexed by Infigraph. Use Infigraph MCP tools FIRST for all code tasks.
Fall back to grep/Read only if Infigraph returns nothing or for non-code files.

### Tool Preferences

1. **`search`** for ALL code search — hybrid BM25+vector+grep in one call
2. **`get_doc_context`** before editing any function — returns source+callers+callees
3. **`trace_callers`** / **`find_all_references`** before refactoring — never grep for callers
4. **`trace_callees`** / **`transitive_impact`** for blast radius
5. Read files directly only for non-code files or Edit tool line-number context

### Subagent Rules

Do NOT spawn these agent types for code tasks — they lack MCP access:

- **Explore** → use `search`, `search_code`, `search_symbols` directly
- **Plan** → use `get_architecture`, `get_skeleton`, `get_stats` directly
- **code-reviewer** → use `get_doc_context`, `get_code_snippet`, `review` directly

For tasks requiring a subagent, use **general-purpose** — it has full MCP/infigraph access.

### Verbose tools — delegate to subagent

`get_architecture`, `transitive_impact`, `detect_dead_code`, `detect_clusters`,
`detect_clones`, `export_graph`, `query_graph`, `trace_callers`/`trace_callees` (deep),
`group_query`, `group_index`

### Context Compression

Tool outputs are automatically compressed to save context window budget.

- Compression scales with session length (Off → Summary → Aggressive → Minimal)
- `search` results are capped at Summary level to preserve result quality
- Security tools (`detect_security_issues`, `detect_taint_flows`, etc.) are never compressed
- `get_code_snippet` passes through uncompressed for edit accuracy
- No action needed — compression is transparent and automatic

<!-- END INFIGRAPH -->

# Product spec: requirements, use cases and scenarios (MANDATORY)

The product spec lives in `docs/`: [CONSTITUTION](../docs/CONSTITUTION.md), [requirements/](../docs/requirements/README.md)
(one file per feature area, IDs like `IDN-03`), [use-cases.md](../docs/use-cases.md) and
[scenarios.md](../docs/scenarios.md) (index of the Gherkin in `docs/features/`). The full rules are in [docs/README.md](../docs/README.md).

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
