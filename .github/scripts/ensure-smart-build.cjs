'use strict';

// Recover a pushed revision that has no build run. Never duplicate a queued or completed run.
module.exports = async function ensureSmartBuild({github, context, core}) {
  const repo = context.repo;
  const workflow_id = 'build-osmand-smart.yml';
  const branch = 'master';
  const {data: head} = await github.rest.repos.getBranch({...repo, branch});
  const sha = head.commit.sha;
  const {data: matching} = await github.rest.actions.listWorkflowRuns({
    ...repo, workflow_id, head_sha: sha, per_page: 1,
  });
  if (matching.workflow_runs.length) {
    core.info(`Build already exists for ${sha}.`);
    return 'existing';
  }
  const {data: recent} = await github.rest.actions.listWorkflowRuns({
    ...repo, workflow_id, branch, per_page: 1,
  });
  const base = recent.workflow_runs[0]?.head_sha;
  if (base) {
    const {data: comparison} = await github.rest.repos.compareCommitsWithBasehead({
      ...repo, basehead: `${base}...${sha}`,
    });
    const files = comparison.files || [];
    // GitHub caps comparisons at 300 files; an incomplete list cannot prove a docs-only push.
    const needsBuild = files.length >= 300 || comparison.status === 'behind' ||
      comparison.status === 'diverged' || files.some(file => [file.filename, file.previous_filename]
        .filter(Boolean).some(name => /^(OsmAnd\/|OsmAnd-java\/|\.github\/workflows\/build-osmand-smart\.yml$)/.test(name)));
    if (!needsBuild) {
      core.info(`No application/build changes since ${base}.`);
      return 'unchanged';
    }
  }
  // workflow_dispatch is supported with the job token even when a token-authored push is suppressed.
  await github.rest.actions.createWorkflowDispatch({...repo, workflow_id, ref: branch});
  core.notice(`Dispatched the missing build for master (${sha}).`);
  return 'dispatched';
};
