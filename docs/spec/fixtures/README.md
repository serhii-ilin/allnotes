# Conformance fixtures

Language-neutral test vectors for the notes folder contract. Each JSON file states inputs and the exact expected
output. The Android build proves every case with
[`VaultContractFixturesTest`](../../../app/src/test/java/org/qosp/notes/data/sync/fs/vault/VaultContractFixturesTest.kt);
any other implementation should run the same files as its own conformance suite.

| File | What it pins | Spec |
|---|---|---|
| `ids.json` | Derived note ids (UUIDv3 of path and `created`) | [algorithms §1.3](../algorithms.md#13-derived-note-id-idn-04-fmt-09) |
| `names.json` | File-name sanitizing, segment validation, conflict-copy names | [vault-format §2](../vault-format.md#2-names) |
| `render.json` | The bytes of a written note file, including an Obsidian file's verbatim keys | [vault-format §4.7](../vault-format.md#47-writing-the-block--must-match) |
| `line-edits.json` | Setting the id and adding missing dates without touching other bytes | [vault-format §5](../vault-format.md#5-single-line-edits-fmt-09-idn-04) |
| `parse.json` | Reading front matter (quoting, dates, tags, reminders) and checklist bodies | [vault-format §4](../vault-format.md#4-front-matter) |
| `canonical-hash.json` | Formatting differences that must not count as edits | [algorithms §1.2](../algorithms.md#12-canonical-hash-of-a-note-fmt-08) |
| `history.json` | Event line encoding, month files, merged order, applicability | [vault-format §8](../vault-format.md#8-folder-history-hst) |

To change a case, edit [`generate.py`](generate.py) (the JSON escapes are easier to get right there), run
`python3 docs/spec/fixtures/generate.py`, then
`./gradlew :app:testDebugUnitTest --tests '*VaultContractFixturesTest*'`.

A case that documents current behavior that is a known defect names the finding (for example `FC-05`). When the
defect is fixed, the case changes in the same commit.
