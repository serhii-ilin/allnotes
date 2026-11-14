#!/usr/bin/env python3
"""Consistency check for the product spec in docs/.

Read-only. Checks the rules in docs/README.md that a machine can check:
  - every requirement has a status and a known scope; every non-pointer, non-retired one has acceptance criteria and
    at least one Gherkin Scenario tagged with its ID;
  - every Scenario names only known requirement IDs and is tagged with @test:... or @gap;
  - every @test:Class[.method] and every `Class.method` on a **Tests:** line exists under app/src/test or
    app/src/androidTest;
  - every SCR-xx / DLG-xx reference points at a screen or dialog listed in docs/ui/README.md;
  - every SC-xx in docs/scenarios.md is tagged on a Scenario.
Prints @gap counts per area. Exits 1 when any error is found.

Usage: python3 scripts/check-docs.py [--quiet]
"""
import pathlib
import re
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
REQ_DIR = DOCS / "requirements"
FEATURE_DIR = DOCS / "features"
UI_INDEX = DOCS / "ui" / "README.md"
TEST_DIRS = [ROOT / "app" / "src" / "test", ROOT / "app" / "src" / "androidTest"]

REQ_HEADING = re.compile(r"^### ([A-Z]{2,4}-\d{2}) ")
STATUS = re.compile(r"\*\*Status:\*\*\s*([A-Za-z]+)")
SCOPE = re.compile(r"\*\*Scope:\*\*\s*([A-Za-z]+)")
POINTER = re.compile(r"\bSee \[?[A-Z]{2,4}-\d{2}|none of its own", re.IGNORECASE)
REQ_TAG = re.compile(r"@([A-Z]{2,4}-\d{2})\b")
TEST_TAG = re.compile(r"@test:([A-Za-z0-9_]+)(?:\.([A-Za-z0-9_]+))?")
SCREEN_REF = re.compile(r"\b((?:SCR|DLG)-\d{2})\b")
TESTS_LINE_REF = re.compile(r"`([A-Z][A-Za-z0-9_]+)(?:\.([A-Za-z0-9_]+))?`")

STATUSES = {"Proposed", "Implemented", "Uncommitted", "Partial", "Defect", "Retired"}
SCOPES = {"Core", "Platform"}

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def load_requirements():
    reqs = {}
    for path in sorted(REQ_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        current = None
        for line in path.read_text(encoding="utf-8").splitlines():
            m = REQ_HEADING.match(line)
            if m:
                current = {"id": m.group(1), "file": path.name, "lines": []}
                if current["id"] in reqs:
                    err(f"{path.name}: duplicate requirement {current['id']}")
                reqs[current["id"]] = current
                continue
            if line.startswith("## ") or line.startswith("# "):
                current = None
            if current is not None:
                current["lines"].append(line)
    for req in reqs.values():
        body = "\n".join(req["lines"])
        status = STATUS.search(body)
        scope = SCOPE.search(body)
        req["status"] = status.group(1) if status else None
        req["scope"] = scope.group(1) if scope else None
        req["has_ac"] = "**Acceptance criteria**" in body
        req["pointer"] = not req["has_ac"] and bool(POINTER.search(body))
        req["body"] = body
    return reqs


def load_scenarios():
    scenarios = []
    for path in sorted(FEATURE_DIR.glob("*.feature")):
        pending = []
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            s = line.strip()
            if s.startswith("@"):
                pending.extend(s.split())
            elif s.startswith("Scenario"):
                scenarios.append({"file": path.name, "line": n, "title": s, "tags": pending})
                pending = []
            elif s.startswith("Feature:"):
                pending = []
            elif s and not s.startswith("#"):
                if pending and not s.startswith("Feature"):
                    pending = []
    return scenarios


def index_tests():
    classes = defaultdict(str)
    for d in TEST_DIRS:
        if not d.exists():
            continue
        for path in d.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r"\b(?:class|object)\s+([A-Za-z0-9_]+)", text):
                classes[m.group(1)] += text
            # Top-level test files without a class still count by file name.
            classes[path.stem] += text
    return classes


def method_exists(source, method):
    # Kotlin test names in backticks are written with underscores for spaces in tags.
    spaced = re.escape(method.replace("_", " "))
    pattern = r"fun\s+(?:`(?:" + re.escape(method) + "|" + spaced + r")`|" + re.escape(method) + r")\s*\("
    return bool(re.search(pattern, source))


def check_test_ref(classes, cls, method, where):
    if cls not in classes:
        err(f"{where}: test class {cls} not found")
    elif method and not method_exists(classes[cls], method):
        err(f"{where}: test method {cls}.{method} not found")


def main():
    quiet = "--quiet" in sys.argv
    reqs = load_requirements()
    scenarios = load_scenarios()
    classes = index_tests()

    covered = Counter()
    gaps = Counter()
    for sc in scenarios:
        where = f"{sc['file']}:{sc['line']}"
        ids = [m.group(1) for t in sc["tags"] for m in [REQ_TAG.fullmatch(t) or REQ_TAG.match(t)] if m]
        ids = [i for i in ids if not i.startswith("SC-")]
        tests = [TEST_TAG.match(t) for t in sc["tags"] if t.startswith("@test:")]
        is_gap = "@gap" in sc["tags"]
        for i in ids:
            if i not in reqs:
                err(f"{where}: unknown requirement tag @{i}")
            covered[i] += 1
        if not tests and not is_gap:
            err(f"{where}: scenario has neither @test nor @gap ({sc['title']})")
        if tests and is_gap:
            warn(f"{where}: scenario has both @test and @gap")
        if is_gap:
            area = sc["file"].replace(".feature", "")
            gaps[area] += 1
        for t in tests:
            if t:
                check_test_ref(classes, t.group(1), t.group(2), where)

    for rid, req in sorted(reqs.items()):
        where = f"{req['file']} {rid}"
        if req["status"] is None and req["pointer"]:
            continue
        if req["status"] not in STATUSES:
            err(f"{where}: missing or unknown status ({req['status']})")
        if req["scope"] is not None and req["scope"] not in SCOPES:
            err(f"{where}: unknown scope {req['scope']}")
        if req["scope"] is None and not req["pointer"]:
            warn(f"{where}: no **Scope:**")
        if req["pointer"] or req["status"] == "Retired":
            continue
        if not req["has_ac"]:
            err(f"{where}: no acceptance criteria")
        if covered[rid] == 0:
            err(f"{where}: no Gherkin scenario")
        for line in req["lines"]:
            if line.startswith("**Status:**") and "**Tests:**" in line:
                tests_part = line.split("**Tests:**", 1)[1]
                for m in TESTS_LINE_REF.finditer(tests_part):
                    cls, method = m.group(1), m.group(2)
                    if cls.endswith("Test") or cls.endswith("Tests"):
                        check_test_ref(classes, cls, method, where)

    screens = set()
    if UI_INDEX.exists():
        for line in UI_INDEX.read_text(encoding="utf-8").splitlines():
            m = re.match(r"\|\s*((?:SCR|DLG)-\d{2})\s*\|", line)
            if m:
                screens.add(m.group(1))
        for path in list(DOCS.rglob("*.md")) + list(DOCS.rglob("*.puml")) + list(DOCS.rglob("*.feature")):
            for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for m in SCREEN_REF.finditer(line):
                    if m.group(1) not in screens:
                        err(f"{path.relative_to(DOCS)}:{n}: unknown screen {m.group(1)}")

    sc_tags = {t[1:] for sc in scenarios for t in sc["tags"] if re.fullmatch(r"@SC-\d{2}", t)}
    for line in (DOCS / "scenarios.md").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\|\s*(SC-\d{2})\s*\|", line)
        if m and m.group(1) not in sc_tags:
            err(f"scenarios.md: {m.group(1)} is not tagged on any Scenario")

    if not quiet:
        for w in warnings:
            print(f"warning: {w}")
    for e in errors:
        print(f"error: {e}")
    total_gap = sum(gaps.values())
    print(f"\n{len(reqs)} requirements, {len(scenarios)} scenarios, {total_gap} @gap, "
          f"{len(screens)} screens/dialogs, {len(errors)} errors, {len(warnings)} warnings")
    for area, n in sorted(gaps.items(), key=lambda kv: -kv[1]):
        print(f"  @gap {area}: {n}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
