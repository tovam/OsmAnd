#!/usr/bin/env python3
"""Publish bounded failed-test context, not an entire CI log, as a public annotation."""

import re
import sys


def annotation_chunks(text, size=2500):
    # GitHub truncates long annotation messages even when the log keeps them.
    return [text[start:start + size] for start in range(0, len(text), size)]


def resource_revision(lines):
    revisions = []
    in_resources = False
    for line in lines:
        # `gh run view --log` may label every step UNKNOWN STEP. The checkout
        # payload still contains the repository name and its final `git log` SHA.
        if "Syncing repository: " in line:
            in_resources = "Syncing repository: osmandapp/OsmAnd-resources" in line
        if in_resources or "Checkout OsmAnd resources" in line:
            match = re.search(r"\b([0-9a-f]{40})\s*$", line)
            if match:
                revisions.append(match.group(1))
    if not revisions:
        raise ValueError("No resource checkout revision found in build log")
    return revisions[-1]


def escape_annotation(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def failure_context(lines):
    selected = set()
    for index, line in enumerate(lines):
        if re.search(r">\s+.*\sFAILED\b|\be: (?:file:|/)|Compilation error", line):
            selected.update(range(max(0, index - 1), min(len(lines), index + 16)))
    if not selected:
        selected.update(range(max(0, len(lines) - 60), len(lines)))
    return "\n".join(lines[index][:600] for index in sorted(selected))[:45000]


if __name__ == "__main__":
    lines = sys.stdin.read().splitlines()
    if sys.argv[1:] == ["--resource-revision"]:
        print(f"::notice title=OsmAnd resources revision::{resource_revision(lines)}")
    elif sys.argv[1:]:
        raise SystemExit("Only --resource-revision is supported")
    else:
        details = failure_context(lines)
        if not details:
            raise SystemExit("No failed-step log was returned")
        chunks = annotation_chunks(details)
        for index, chunk in enumerate(chunks, 1):
            print(f"::error title=Failed build test details {index}/{len(chunks)}::{escape_annotation(chunk)}")
