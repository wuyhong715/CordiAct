#!/usr/bin/env python3
"""Check the repository's architecture governance baseline.

This is a deterministic source check. It does not claim that Android, device,
or remote-runtime behavior has been tested.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_MODULES = {
    "app",
    "core",
    "interaction",
    "deepseek-harness",
    "model-providers",
    "search-providers",
    "mcp-client",
    "subagent",
}
JVM_MODULES = REQUIRED_MODULES - {"app"}
FORBIDDEN_IMPORTS = re.compile(
    r"^\s*import\s+(?:android\.|androidx\.|com\.google\.android\.|com\.google\.accompanist\.)",
    re.MULTILINE,
)
ALLOWED_STATUSES = {"implemented", "contract-only", "poc-only", "unavailable"}


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    raise SystemExit(1)


def check_file(path: Path) -> None:
    if not path.is_file():
        fail(f"missing required file: {path.relative_to(ROOT)}")


def main() -> int:
    settings = ROOT / "settings.gradle.kts"
    check_file(settings)
    settings_text = settings.read_text(encoding="utf-8")
    # The repository uses one include call with comma-separated module strings.
    include_match = re.search(r"include\(([^\n]+)\)", settings_text)
    if include_match is None:
        fail("root settings has no include declaration")
    declared = set(include_match.group(1).replace('"', '').split(','))
    declared = {item.strip().lstrip(":") for item in declared}
    missing = REQUIRED_MODULES - declared
    if missing:
        fail(f"root settings is missing modules: {sorted(missing)}")

    for path in (
        ROOT / "docs/architecture/governance-baseline.md",
        ROOT / "docs/architecture/capability-status.json",
        ROOT / "CordiAct-技术栈与架构治理方案.md",
    ):
        check_file(path)

    status_path = ROOT / "docs/architecture/capability-status.json"
    try:
        status = json.loads(status_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"invalid capability status JSON: {error}")
    if status.get("schemaVersion") != 1:
        fail("unsupported capability status schema")
    capabilities = status.get("capabilities")
    if not isinstance(capabilities, list) or not capabilities:
        fail("capability status must contain a non-empty list")
    for capability in capabilities:
        if capability.get("status") not in ALLOWED_STATUSES:
            fail(f"invalid capability status: {capability!r}")
        if not capability.get("executionTarget"):
            fail(f"capability has no execution target: {capability!r}")

    for module in sorted(JVM_MODULES):
        source_root = ROOT / module / "src"
        if not source_root.is_dir():
            fail(f"missing source directory for module {module}")
        for source in source_root.rglob("*.kt"):
            text = source.read_text(encoding="utf-8")
            if FORBIDDEN_IMPORTS.search(text):
                fail(f"Android import in JVM module: {source.relative_to(ROOT)}")

    print(f"PASS: architecture governance baseline, {len(REQUIRED_MODULES)} modules checked")
    print(f"PASS: {len(capabilities)} capability states checked")
    print("PASS: JVM module source boundaries checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
