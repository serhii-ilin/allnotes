"""Writes the conformance fixtures in this folder. Edit the cases here, run `python3 docs/spec/fixtures/generate.py`,
then run VaultContractFixturesTest. Expected values are computed independently of the Kotlin code."""
import json, pathlib, sys

out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent
out.mkdir(parents=True, exist_ok=True)

def dump(name, data):
    (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

dump("ids.json", {
    "about": "derivedId(path, created) — algorithms.md §1.3. created null means the file has no readable created.",
    "cases": [
        {"path": "Note.md", "created": None, "expected": "f7773653-611c-3afd-88fa-7ad55161a4a8"},
        {"path": "Work/Meeting.md", "created": 1700000000, "expected": "9f30b33c-9062-3a2b-b8e6-523874c77f4f"},
        {"path": "Ideas.txt", "created": None, "expected": "5ff5e30f-ab88-3530-9853-34d7987f10eb"},
        {"path": "Notizen/Überblick.md", "created": 1731500000, "expected": "2360de4f-c7a0-3af6-9584-1aaa9a94ed1f"},
        {"path": "Shopping (conflict 1731500000).md", "created": 1700000000, "expected": "c2ff68d9-6fa9-3bc4-b7e5-ff15b9c77e2d"},
    ],
})

dump("names.json", {
    "about": "sanitize(name) — vault-format.md §2.2; conflictPath — §2.5; validateSegment — §2.4.",
    "sanitize": [
        {"input": "Shopping list.md", "expected": "Shopping list.md"},
        {"input": "a/b:c*d?.md", "expected": "a_b_c_d_.md"},
        {"input": "<Report>|2026.md", "expected": "_Report__2026.md"},
        {"input": "  .hidden.md  ", "expected": "_hidden.md"},
        {"input": "a\u0001b.md", "expected": "a_b.md"},
        {"input": "con.md", "expected": "con_.md"},
        {"input": "COM1", "expected": "COM1_"},
        {"input": "name. ", "expected": "name"},
        {"input": "   ", "expected": "file"},
        {"input": "Café.md", "expected": "Café.md"},
        {"input": "é" * 150 + ".md", "expected": "é" * 98 + ".md"},
    ],
    "conflictPath": [
        {"path": "Work/Meeting.md", "epochSeconds": 1731500000, "expected": "Work/Meeting (conflict 1731500000).md"},
        {"path": "Ideas.txt", "epochSeconds": 5, "expected": "Ideas (conflict 5).txt"},
    ],
    "validateSegment": [
        {"input": "Work", "expected": None},
        {"input": " ", "expected": "Blank"},
        {"input": "a:b", "expected": "IllegalChar"},
        {"input": ".git", "expected": "LeadingDot"},
        {"input": "Work.", "expected": "TrailingDotOrSpace"},
        {"input": "nul", "expected": "ReservedName"},
        {"input": "x" * 201, "expected": "TooLong"},
    ],
})

ID1 = "11111111-2222-3333-4444-555555555555"
ID3 = "aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa"
dump("render.json", {
    "about": "Writing a note file — vault-format.md §4.7. 'previous' is the file's text before the save (null for a new file).",
    "cases": [
        {
            "name": "new text note at the root",
            "path": "Shopping.md", "id": ID1, "notebook": None, "previous": None,
            "note": {"title": "Shopping", "content": "Milk\nEggs", "isPinned": True, "color": "Blue",
                     "tags": ["home", "Errands"], "creationDate": 1700000000, "modifiedDate": 1700000500},
            "expected": "---\nallnotes-id: \"" + ID1 + "\"\npinned: true\narchived: false\nhidden: false\nisList: false\n"
                        "markdown: true\ncolor: \"Blue\"\nnotebook: \"\"\ntags: [\"Errands\", \"home\"]\n"
                        "created: 1700000000\nmodified: 1700000500\n---\nMilk\nEggs\n",
        },
        {
            "name": "checklist in a notebook, title differs from the file name, a reminder",
            "path": "Work/Q3_ plan.md", "id": ID1, "notebook": "Work", "previous": None,
            "note": {"title": "Q3: plan", "isList": True,
                     "tasks": [{"content": "Draft", "isDone": True}, {"content": "Review", "isDone": False}],
                     "reminders": [{"name": "Say \"hi\"", "date": 4102444800}],
                     "creationDate": 1700000000, "modifiedDate": 1700000000},
            "expected": "---\nallnotes-id: \"" + ID1 + "\"\ntitle: \"Q3: plan\"\npinned: false\narchived: false\n"
                        "hidden: false\nisList: true\nmarkdown: true\ncolor: \"Default\"\nnotebook: \"Work\"\ntags: []\n"
                        "reminders:\n  - name: \"Say \\\"hi\\\"\"\n    at: 2100-01-01T00:00:00Z\n"
                        "created: 1700000000\nmodified: 1700000000\n---\n- [x] Draft\n- [ ] Review\n",
        },
        {
            "name": "an Obsidian file keeps its scalar tags, ISO date and foreign keys",
            "path": "Shopping.md", "id": ID3, "notebook": None,
            "previous": "---\naliases: [Groceries]\ntags: home\ncreated: 2024-06-15\ntitle: Shopping\n---\nMilk\n",
            "note": {"title": "Shopping", "content": "Milk\nEggs", "tags": ["home"],
                     "creationDate": 1718409600, "modifiedDate": 1718500000},
            "expected": "---\ntags: home\ncreated: 2024-06-15\nallnotes-id: \"" + ID3 + "\"\npinned: false\n"
                        "archived: false\nhidden: false\nisList: false\nmarkdown: true\ncolor: \"Default\"\n"
                        "notebook: \"\"\nmodified: 1718500000\naliases: [Groceries]\n---\nMilk\nEggs\n",
        },
    ],
})

dump("line-edits.json", {
    "about": "Single-line edits — vault-format.md §5.",
    "setId": [
        {"name": "no front matter", "input": "Body\n", "id": "X",
         "expected": "---\nallnotes-id: \"X\"\n---\nBody\n"},
        {"name": "empty block", "input": "---\n---\nBody\n", "id": "X",
         "expected": "---\nallnotes-id: \"X\"\n---\nBody\n"},
        {"name": "CRLF file keeps CRLF", "input": "---\r\ntitle: T\r\n---\r\nBody\r\n", "id": "X",
         "expected": "---\r\nallnotes-id: \"X\"\r\ntitle: T\r\n---\r\nBody\r\n"},
        {"name": "existing id replaced, foreign id and body untouched",
         "input": "---\ntitle: T\nallnotes-id: old\nid: 7\n---\nallnotes-id: in body\n", "id": "X",
         "expected": "---\ntitle: T\nallnotes-id: \"X\"\nid: 7\n---\nallnotes-id: in body\n"},
    ],
    "addMissingDates": [
        {"name": "no front matter", "input": "Body\n", "created": 100, "modified": 200,
         "expected": "---\ncreated: 100\nmodified: 200\n---\nBody\n"},
        {"name": "an unreadable created counts as present",
         "input": "---\ntitle: T\ncreated: yesterday\n---\nB\n", "created": 100, "modified": 200,
         "expected": "---\ntitle: T\ncreated: yesterday\nmodified: 200\n---\nB\n"},
    ],
})

dump("parse.json", {
    "about": "Reading front matter and body — vault-format.md §4.2–4.6, §4.8. Absent fields are null.",
    "cases": [
        {
            "name": "Obsidian-style front matter",
            "input": "---\ntitle: 'It''s mine'\ntags:\n  - a\n  - \"b c\"\npinned: True\ncolor: Teal\n"
                     "created: 2024-06-15T12:00:00+02:00\nreminders:\n  - at: 2030-01-01T10:00:00Z\n    name: \"Wake\"\n"
                     "  - name: no date\nfoo: bar\n---\nText\n",
            "expected": {"id": None, "title": "It's mine", "tags": ["a", "b c"], "pinned": None, "color": "Teal",
                         "created": 1718445600, "reminders": [{"name": "Wake", "date": 1893492000}],
                         "preservedYaml": "foo: bar", "body": "Text\n"},
        },
        {
            "name": "no front matter",
            "input": "Just text\n",
            "expected": {"id": None, "title": None, "tags": None, "pinned": None, "color": None, "created": None,
                         "reminders": None, "preservedYaml": "", "body": "Just text\n"},
        },
        {
            "name": "empty reminders and inline tags with a quoted comma",
            "input": "---\nallnotes-id: abc\ntags: [\"x, y\", z]\nreminders: []\ncreated: 1700000000\n---\n",
            "expected": {"id": "abc", "title": None, "tags": ["x, y", "z"], "pinned": None, "color": None,
                         "created": 1700000000, "reminders": [], "preservedYaml": "", "body": ""},
        },
    ],
    "checklists": [
        {"name": "markers and continuation lines (continuation is glued without a space: FC-05)",
         "body": "- [ ] one\n  continued\n* [X] two\nstray", "path": "List.md",
         "expected": [{"content": "onecontinued", "isDone": False}, {"content": "twostray", "isDone": True}]},
    ],
})

base = ("---\nallnotes-id: \"" + ID1 + "\"\npinned: true\narchived: false\nhidden: false\nisList: false\n"
        "markdown: true\ncolor: \"Blue\"\nnotebook: \"\"\ntags: [\"Errands\", \"home\"]\n"
        "created: 1700000000\nmodified: 1700000500\n---\nMilk\nEggs\n")
dump("canonical-hash.json", {
    "about": "canonicalOfFile — algorithms.md §1.2. Every variant of one note must give the same hash.",
    "groups": [
        {
            "name": "id line, blank lines before the body, trailing whitespace and bare scalars do not count",
            "path": "Shopping.md",
            "variants": [
                base,
                base.replace("allnotes-id: \"" + ID1 + "\"\n", ""),
                base.replace("---\nMilk", "---\n\n\nMilk").replace("Eggs\n", "Eggs   \n\n"),
                base.replace("color: \"Blue\"", "color: Blue").replace("notebook: \"\"\n", "notebook: \"\"\n"),
                "---\nmodified: 1700000500\ncreated: 1700000000\ntags: [Errands, home]\ncolor: Blue\npinned: true\n---\nMilk\nEggs",
            ],
            "expected": "816908abcb2cbad8f8ab3544b69fcc0be2a5017c29c033b9396c7718954e129e",
        },
    ],
})

dump("history.json", {
    "about": "Folder history — vault-format.md §8. 'files' is the content of .allnotes/; 'order' is the merged eid order.",
    "encode": [
        {"event": {"eid": "dev-a:1", "dev": "dev-a", "seq": 1, "lc": 5, "t": 1731500000000, "op": "note.bin",
                   "id": "X", "path": "Work/A.md", "to": ".trash/Work/A.md"},
         "expectedLine": "{\"v\":1,\"eid\":\"dev-a:1\",\"dev\":\"dev-a\",\"seq\":1,\"lc\":5,\"t\":1731500000000,"
                         "\"op\":\"note.bin\",\"id\":\"X\",\"path\":\"Work/A.md\",\"to\":\".trash/Work/A.md\"}",
         "expectedMonthFile": "events-2024-11.jsonl"},
    ],
    "read": [
        {
            "name": "merged order is (lc, dev, seq); duplicates collapse; bad lines are counted; no manifest = display only",
            "files": {
                ".allnotes/devices/dev-a/device.json": "{\"format\":1,\"deviceId\":\"dev-a\",\"name\":\"A\",\"appVersion\":\"1\",\"writes\":[],\"partialApplyOk\":false}\n",
                ".allnotes/devices/dev-a/events-2024-11.jsonl":
                    "{\"v\":1,\"eid\":\"dev-a:1\",\"dev\":\"dev-a\",\"seq\":1,\"lc\":2,\"t\":1,\"op\":\"tag.create\",\"name\":\"x\"}\n"
                    "not json\n"
                    "{\"v\":2,\"eid\":\"dev-a:2\",\"dev\":\"dev-a\",\"seq\":2,\"lc\":4,\"t\":2,\"op\":\"tag.delete\",\"name\":\"x\",\"future\":1}\n",
                ".allnotes/devices/dev-a/events-2024-11.sync-conflict-20241113-ABC.jsonl":
                    "{\"v\":1,\"eid\":\"dev-a:1\",\"dev\":\"dev-a\",\"seq\":1,\"lc\":2,\"t\":1,\"op\":\"tag.create\",\"name\":\"x\"}\n",
                ".allnotes/devices/dev-b/events-2024-11.jsonl":
                    "{\"v\":1,\"eid\":\"dev-b:1\",\"dev\":\"dev-b\",\"seq\":1,\"lc\":2,\"t\":9,\"op\":\"note.purge\",\"id\":\"N\",\"path\":\"N.md\"}\n"
                    "{\"v\":1,\"eid\":\"dev-b:2\",\"dev\":\"dev-b\",\"seq\":2,\"lc\":3,\"t\":3,\"op\":\"shape.morph\"}\n",
            },
            "expectedOrder": ["dev-a:1", "dev-b:1", "dev-b:2", "dev-a:2"],
            "expectedApplicable": {"dev-a:1": True, "dev-b:1": False, "dev-b:2": False, "dev-a:2": False},
            "expectedSkippedLines": 1,
        },
    ],
})
