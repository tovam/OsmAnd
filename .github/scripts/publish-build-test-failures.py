#!/usr/bin/env python3
"""Publish bounded failed-test context, not an entire CI log, as a public annotation."""

import re
import sys


def failure_context(lines):
    selected = set()
    for index, line in enumerate(lines):
        if re.search(r">\s+.*\sFAILED\b|\be: (?:file:|/)|Compilation error", line):
            selected.update(range(max(0, index - 1), min(len(lines), index + 16)))
    if not selected:
        selected.update(range(max(0, len(lines) - 60), len(lines)))
    return "\n".join(lines[index][:600] for index in sorted(selected))[:45000]


if __name__ == "__main__":
    details = failure_context(sys.stdin.read().splitlines())
    if not details:
        raise SystemExit("No failed-step log was returned")
    escaped = details.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Failed build test details::{escaped}")
