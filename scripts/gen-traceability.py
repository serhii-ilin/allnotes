#!/usr/bin/env python3
"""Writes docs/traceability.md: code element -> requirement -> screen, and requirement -> use case -> screens ->
scenarios -> tests -> scope.

The code-element maps below are maintained by hand. `--check` fails when the code has an element that no map covers
(a new menu item, navigation destination, preference, manifest component, history op or front-matter key), so a
feature cannot land without being traced to a requirement.

Usage: python3 scripts/gen-traceability.py [--check]
"""
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/org/qosp/notes"
OUT = DOCS / "traceability.md"

# menu file -> item id -> (requirements, screen)
MENUS = {
    "main_top": {"action_search": "NTL-12", "action_sort_name_asc": "NTL-05", "action_sort_name_desc": "NTL-05",
                 "action_sort_created_asc": "NTL-05", "action_sort_created_desc": "NTL-05",
                 "action_sort_modified_asc": "NTL-05", "action_sort_modified_desc": "NTL-05",
                 "action_layout_mode": "NTL-04", "action_show_hidden_notes": "NTL-07", "action_select_all": "NTL-10"},
    "main_bottom": {"action_create_list": "NTL-14", "action_attach_file": "NTL-14, MED-01",
                    "action_take_photo": "NTL-14, MED-02", "action_record_audio": "NTL-14, MED-03"},
    "main_selected_notes": {"action_pin_selected": "NTL-11", "action_compact_preview_selected": "NTL-11",
                            "action_full_preview_selected": "NTL-11", "action_archive_selected": "NTL-11",
                            "action_delete_selected": "NTL-11, DEL-01", "action_hide_selected": "NTL-11",
                            "action_move_selected": "NTL-11", "action_duplicate_selected": "NTL-11",
                            "action_export_selected": "NTL-11, BAK-03", "action_select_all": "NTL-10"},
    "archive": {"action_search": "NTL-12", "action_show_hidden_notes": "NTL-07", "action_select_all": "NTL-10"},
    "archive_selected_notes": {"action_unarchive": "NTL-11", "action_delete_selected": "NTL-11",
                               "action_hide_selected": "NTL-11", "action_export_selected": "NTL-11, BAK-03",
                               "action_select_all": "NTL-10"},
    "deleted": {"action_search": "NTL-12", "action_empty_bin": "DEL-11", "action_show_hidden_notes": "NTL-07",
                "action_select_all": "NTL-10"},
    "deleted_selected_notes": {"action_restore_selected": "NTL-11, DEL-01",
                               "action_delete_permanently_selected": "NTL-11, DEL-02",
                               "action_hide_selected": "NTL-11", "action_export_selected": "NTL-11, BAK-03",
                               "action_select_all": "NTL-10"},
    "editor_top": {"action_change_mode": "EDT-02", "action_pin_note": "EDT-10", "action_view_reminders": "REM-06",
                   "action_restore_note": "EDT-10", "action_delete_permanently_note": "EDT-10",
                   "action_convert_note": "EDT-07", "action_archive_note": "EDT-10",
                   "action_uncheck_all_tasks": "EDT-08", "action_remove_all_checked_tasks": "EDT-08",
                   "action_delete_note": "EDT-10", "action_hide_note": "EDT-10",
                   "action_screen_always_on": "EDT-09", "action_change_color": "EDT-09",
                   "action_enable_disable_markdown": "EDT-03", "action_attach_file": "MED-01",
                   "action_record_audio": "MED-03", "action_take_photo": "MED-02", "action_view_tags": "ORG-12",
                   "action_export_note": "EDT-11, BAK-03", "action_share": "EDT-11"},
    "editor_bottom": {k: "EDT-04" for k in (
        "action_insert_bold action_insert_italics action_insert_strikethrough action_insert_heading "
        "action_insert_quote action_insert_highlight action_insert_code action_toggle_check_line "
        "action_scroll_to_top action_scroll_to_bottom action_undo action_redo").split()}
    | {"action_insert_link": "EDT-05", "action_insert_table": "EDT-05", "action_insert_image": "EDT-05"},
    "manage_notebooks": {"action_sort_navdrawer_notebook_name_asc": "ORG-06",
                         "action_sort_navdrawer_notebook_name_desc": "ORG-06",
                         "action_sort_navdrawer_notebook_created_asc": "ORG-06",
                         "action_sort_navdrawer_notebook_created_desc": "ORG-06",
                         "action_create_notebook": "ORG-01"},
    "manage_notebooks_selected": {"action_delete_selected": "ORG-04", "action_select_all": "ORG-05"},
    "tags": {"action_sort_tags_name_asc": "ORG-11", "action_sort_tags_name_desc": "ORG-11",
             "action_sort_tags_created_asc": "ORG-11", "action_sort_tags_created_desc": "ORG-11",
             "action_create_tag": "ORG-09"},
    "tags_selected": {"action_delete_selected": "ORG-10", "action_select_all": "ORG-10"},
    "nav_drawer": {"section_notes": "UI-01", "fragment_main": "NTL-01", "fragment_archive": "NTL-08",
                   "fragment_deleted": "NTL-09", "menu_notebooks": "ORG-07", "section_notebooks": "ORG-07",
                   "fragment_manage_notebooks": "ORG-05", "nav_default_notebook": "ORG-08",
                   "section_tags": "ORG-10", "fragment_tags": "ORG-10", "section_settings": "SET-01",
                   "fragment_settings": "SET-01", "fragment_about": "INT-08"},
}
MENU_SCREEN = {"main_top": "SCR-01", "main_bottom": "SCR-01", "main_selected_notes": "SCR-01",
               "archive": "SCR-03", "archive_selected_notes": "SCR-03", "deleted": "SCR-04",
               "deleted_selected_notes": "SCR-04", "editor_top": "SCR-06", "editor_bottom": "SCR-06",
               "manage_notebooks": "SCR-07", "manage_notebooks_selected": "SCR-07", "tags": "SCR-08",
               "tags_selected": "SCR-08", "nav_drawer": "SCR-13"}

NAV = {"fragment_main": ("SCR-01", "NTL-01"), "fragment_archive": ("SCR-03", "NTL-08"),
       "fragment_deleted": ("SCR-04", "NTL-09"), "fragment_notebook": ("SCR-02", "NTL-13"),
       "fragment_editor": ("SCR-06", "EDT-02"), "fragment_tags": ("SCR-08", "ORG-10"),
       "fragment_manage_notebooks": ("SCR-07", "ORG-05"), "fragment_about": ("SCR-11", "INT-08"),
       "fragment_settings": ("SCR-09", "SET-01"), "fragment_history": ("SCR-10", "HST-07"),
       "fragment_search": ("SCR-05", "NTL-12")}

PREFS = {"layout_mode": "SET-04", "theme_mode": "SET-02", "dark_theme_mode": "SET-02", "color_scheme": "SET-03",
         "sort_method": "SET-05", "sort_tags_method": "SET-05", "sort_navdrawer_notebooks_method": "SET-05",
         "backup_strategy": "SET-13", "note_deletion_time": "SET-11", "history_retention": "SET-12",
         "date_format": "SET-07", "time_format": "SET-07", "open_media": "SET-09", "show_date": "SET-07",
         "editor_font_size": "SET-06", "show_fab_change_mode": "SET-08",
         "group_notes_without_notebook": "SET-10", "move_checked_items": "SET-06",
         "default_editor_mode": "SET-06", "cloud_service": "FLD-01"}

DEVICE_KEYS = {"STORAGE_LOCATION": "FLD-01", "VAULT_CONFIGURED_AT": "FLD-03", "VAULT_CLOUD_WARNED_TREE": "FLD-10",
               "VAULT_GENERATION": "WRS-03", "HISTORY_DEVICE_ID": "HST-02", "HISTORY_LAST_SEQ": "HST-02",
               "HISTORY_APPLY": "HST-11", "HISTORY_LAST_PRUNE_DAY": "HST-13", "NOTEBOOK_NAMES_MIGRATED": "FMT-11"}

MANIFEST = {".ui.MainActivity": ("SCR-01", "INT-01, INT-02, INT-03"),
            ".ui.launcher.LauncherActivity": ("SCR-16", "INT-04"),
            "org.qosp.notes.ui.media.MediaActivity": ("SCR-12", "MED-06, MED-07, MED-08"),
            ".ui.widget.NotesWidgetProvider": ("SCR-14", "INT-05, INT-07"),
            ".ui.widget.NotesWidgetService": ("SCR-14", "INT-05"),
            "androidx.core.content.FileProvider": ("", "INT-11"),
            "androidx.startup.InitializationProvider": ("", "BGD-01"),
            ".ui.reminders.ReminderReceiver": ("SCR-15", "REM-11, REM-12, BGD-07"),
            "org.qosp.notes.ui.media.MusicService": ("SCR-15", "MED-08"),
            "org.qosp.notes.ui.recorder.RecorderService": ("DLG-13", "MED-03"),
            "org.qosp.notes.components.backup.BackupService": ("SCR-15", "BAK-05")}

HISTORY_OPS = {"note.bin": "HST-04, HST-09", "note.restore": "HST-04, HST-09", "note.purge": "HST-04, HST-09",
               "note.move": "HST-04", "notebook.create": "HST-04, HST-10", "notebook.rename": "HST-04, HST-10",
               "notebook.delete": "HST-04, HST-10", "tag.create": "HST-04, HST-10", "tag.rename": "HST-04, HST-10",
               "tag.delete": "HST-04, HST-10", "device.reid": "HST-02"}

FRONT_MATTER = {"allnotes-id": "IDN-01, FMT-02", "title": "FMT-02", "pinned": "FMT-02, NTL-06",
                "archived": "FMT-02, NTL-08", "hidden": "FMT-02, NTL-07", "isList": "FMT-02, FMT-06",
                "markdown": "FMT-02, EDT-03", "color": "FMT-02, EDT-09", "notebook": "FMT-02, FMT-04",
                "created": "FMT-02, FMT-09", "modified": "FMT-02, FMT-09", "tags": "FMT-02, FMT-03",
                "reminders": "FMT-02, REM-01, REM-03"}

WORKERS = {"SyncWorker": "BGD-02", "BinCleaningWorker": "BGD-03, DEL-08"}

RANGE = re.compile(r"([A-Z]{2,4})-(\d{2})…(?:[A-Z]{2,4}-)?(\d{2})")
ID = re.compile(r"[A-Z]{2,4}-\d{2}")


def expand(text):
    ids = []
    for m in RANGE.finditer(text):
        ids += [f"{m.group(1)}-{i:02d}" for i in range(int(m.group(2)), int(m.group(3)) + 1)]
    ids += ID.findall(RANGE.sub("", text))
    return ids


def code_inventory():
    menus = {}
    for path in sorted((RES / "menu").glob("*.xml")):
        menus[path.stem] = re.findall(r'android:id="@\+id/(\w+)"', path.read_text())
    nav = re.findall(r'<fragment\b[^>]*?android:id="@\+id/(\w+)"', (RES / "navigation/nav_graph.xml").read_text(), re.S)
    prefs = re.findall(r'key\("(\w+)"\)', (SRC / "preferences/PreferenceEnums.kt").read_text())
    keys = re.findall(r'const val (\w+) = "\w+"', (SRC / "preferences/PreferenceRepository.kt").read_text())
    manifest = [m.group(2) for m in re.finditer(
        r'<(activity-alias|activity|service|receiver|provider)\b[^>]*?android:name="([^"]+)"',
        (ROOT / "app/src/main/AndroidManifest.xml").read_text(), re.S)]
    ops = re.findall(r'const val \w+ = "([a-z]+\.[a-z]+)"', (SRC / "data/sync/fs/vault/history/HistoryEvent.kt").read_text())
    doc = (SRC / "data/sync/fs/vault/VaultNoteDocument.kt").read_text()
    block = re.search(r"knownRootKeys = setOf\((.*?)\)", doc, re.S).group(1)
    fm = re.findall(r'"([\w-]+)"', block) + ["allnotes-id"]
    workers = [p.stem for p in (SRC / "components/workers").glob("*.kt")]
    return menus, nav, prefs, keys, manifest, ops, fm, workers


def check():
    menus, nav, prefs, keys, manifest, ops, fm, workers = code_inventory()
    missing = []
    for menu, ids in menus.items():
        for i in ids:
            if i not in MENUS.get(menu, {}):
                missing.append(f"menu {menu}/{i}")
    missing += [f"nav {n}" for n in nav if n not in NAV]
    missing += [f"preference {p}" for p in prefs if p not in PREFS]
    missing += [f"device key {k}" for k in keys if k not in DEVICE_KEYS]
    missing += [f"manifest {m}" for m in manifest if m not in MANIFEST]
    missing += [f"history op {o}" for o in ops if o not in HISTORY_OPS]
    missing += [f"front-matter key {k}" for k in set(fm) if k not in FRONT_MATTER]
    missing += [f"worker {w}" for w in workers if w not in WORKERS]
    return missing


def requirements():
    reqs = {}
    for path in sorted((DOCS / "requirements").glob("*.md")):
        if path.name == "README.md":
            continue
        current = None
        for line in path.read_text().splitlines():
            m = re.match(r"### ([A-Z]{2,4}-\d{2}) (.*)", line)
            if m:
                current = m.group(1)
                reqs[current] = {"title": m.group(2), "file": path.name, "status": "pointer", "scope": "",
                                 "screens": ""}
            elif current and line.startswith("**Status:**"):
                s = re.match(r"\*\*Status:\*\*\s*([A-Za-z]+)", line)
                reqs[current]["status"] = s.group(1) if s else ""
                sc = re.search(r"\*\*Scope:\*\*\s*(\w+)", line)
                reqs[current]["scope"] = sc.group(1) if sc else ""
            elif current and line.startswith("**Screens:**"):
                reqs[current]["screens"] = line.split("**Screens:**", 1)[1].strip()
    return reqs


def use_cases():
    ucs = defaultdict(list)
    current = None
    for line in (DOCS / "use-cases.md").read_text().splitlines():
        m = re.match(r"## (UC-\d{2})", line)
        if m:
            current = m.group(1)
        elif current and "**Requirements:**" in line:
            for rid in expand(line.split("**Requirements:**", 1)[1]):
                if current not in ucs[rid]:
                    ucs[rid].append(current)
    return ucs


def scenarios():
    tagged = defaultdict(lambda: [0, 0, 0])  # scenarios, gaps, with tests
    for path in sorted((DOCS / "features").glob("*.feature")):
        tags = []
        for line in path.read_text().splitlines():
            s = line.strip()
            if s.startswith("@"):
                tags += s.split()
            elif s.startswith("Scenario"):
                for t in tags:
                    if re.fullmatch(r"@[A-Z]{2,4}-\d{2}", t) and not t.startswith("@SC-"):
                        rec = tagged[t[1:]]
                        rec[0] += 1
                        rec[1] += "@gap" in tags
                        rec[2] += any(x.startswith("@test:") for x in tags)
                tags = []
            elif s and not s.startswith("#"):
                tags = []
    return tagged


def screen_of(reqs_text):
    return ""


def write():
    reqs = requirements()
    ucs = use_cases()
    scs = scenarios()
    out = ["# Traceability", "",
           "Generated by `python3 scripts/gen-traceability.py`; do not edit by hand. `--check` fails when the code has a",
           "menu item, navigation destination, preference, device key, manifest component, worker, history op or",
           "front-matter key that is not mapped to a requirement. Screens are in [ui/](ui/README.md).", "",
           "## Requirements", "",
           "| ID | Requirement | Status | Scope | Use cases | Screens | Scenarios | @gap | With tests |",
           "|---|---|---|---|---|---|---|---|---|"]
    for rid in sorted(reqs, key=lambda r: (r.split("-")[0], int(r.split("-")[1]))):
        r = reqs[rid]
        n, gap, tested = scs.get(rid, [0, 0, 0])
        link = f"[{rid}](requirements/{r['file']})"
        out.append(f"| {link} | {r['title']} | {r['status']} | {r['scope']} | {', '.join(ucs.get(rid, []))} | "
                   f"{r['screens']} | {n} | {gap} | {tested} |")
    out += ["", "## Menus", "", "| Menu | Item | Screen | Requirements |", "|---|---|---|---|"]
    for menu, items in MENUS.items():
        for item, rids in items.items():
            out.append(f"| `{menu}` | `{item}` | {MENU_SCREEN[menu]} | {rids} |")
    out += ["", "## Navigation destinations", "", "| Destination | Screen | Requirement |", "|---|---|---|"]
    out += [f"| `{k}` | {s} | {r} |" for k, (s, r) in NAV.items()]
    out += ["", "## Settings (device-local)", "", "| Key | Requirement |", "|---|---|"]
    out += [f"| `{k}` | {r} |" for k, r in PREFS.items()]
    out += ["", "## Device-local state", "", "| Key | Requirement |", "|---|---|"]
    out += [f"| `{k}` | {r} |" for k, r in DEVICE_KEYS.items()]
    out += ["", "## Platform components (Android manifest)", "", "| Component | Screen | Requirements |",
            "|---|---|---|"]
    out += [f"| `{k}` | {s} | {r} |" for k, (s, r) in MANIFEST.items()]
    out += ["", "## Background workers", "", "| Worker | Requirements |", "|---|---|"]
    out += [f"| `{k}` | {r} |" for k, r in WORKERS.items()]
    out += ["", "## Folder contract", "", "| Front-matter key | Requirements |", "|---|---|"]
    out += [f"| `{k}` | {r} |" for k, r in FRONT_MATTER.items()]
    out += ["", "| History op | Requirements |", "|---|---|"]
    out += [f"| `{k}` | {r} |" for k, r in HISTORY_OPS.items()]
    OUT.write_text("\n".join(out) + "\n")


def main():
    missing = check()
    for m in missing:
        print(f"unmapped: {m}")
    if "--check" in sys.argv:
        if not OUT.exists():
            print("docs/traceability.md is missing")
            return 1
        before = OUT.read_text()
        write()
        stale = OUT.read_text() != before
        if stale:
            print("docs/traceability.md was stale and has been regenerated")
        return 1 if missing or stale else 0
    write()
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
