#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
json_jar="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
classes="$(mktemp -d "$repo_root/.app-update-tests.XXXXXX")"
trap 'rm -rf "$classes"' EXIT
javac -cp "$json_jar" -d "$classes" \
  "$repo_root/OsmAnd/src/net/osmand/plus/settings/backend/GitHubAppRelease.java" \
  "$repo_root/OsmAnd/test/standalone/GitHubAppReleaseTest.java"
java -cp "$classes:$json_jar" GitHubAppReleaseTest
