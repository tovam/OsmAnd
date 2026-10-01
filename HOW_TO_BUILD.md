# Build on GitHub — use Git, NOT gh

- Do not run `gh`, request `gh auth login`, inspect credentials, or build locally: the existing `git push` works.
- Work in `cd /Users/tommy/dev/osmand-fork/OsmAnd`; check `git status --short` and `git branch --show-current` (must be `master`).
- Before building application changes, update `BUILD_CHANGES.md` with 1–3 short bullets describing the features added, changed, or fixed since the previous published APK. Keep unchanged notes when rebuilding the same application code. Releases publish this file from the exact built revision instead of technical build boilerplate.
- Stage only the intended changes: `git add <actual-file-paths>`; review with `git diff --cached`.
- Launch: `git diff --cached --check && git commit -m "Describe the change" && git push` (skip the commit if already committed).
- The push triggers the APK build for changes in `OsmAnd/**`, `OsmAnd-java/**`, or `.github/workflows/build-osmand-smart.yml`.
- To rebuild unchanged code without gh: update ONLY the existing `# Manual rebuild requested:` timestamp comment in that workflow, then stage that file, commit and push. Empty/documentation-only commits do not trigger it.
- Find the launched run: `build_sha=$(git rev-parse HEAD) && curl -fsS "https://api.github.com/repos/tovam/OsmAnd/actions/workflows/build-osmand-smart.yml/runs?head_sha=${build_sha}&event=push&per_page=3" | jq '.workflow_runs[] | {run_number,run_attempt,status,conclusion,html_url}'`.
- Report the returned run URL; success automatically publishes the APK at [Releases](https://github.com/tovam/OsmAnd/releases), under `smart-build-N-A`. If compilation fails, fix, commit and push again.
