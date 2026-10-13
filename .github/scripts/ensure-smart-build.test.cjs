'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const ensure = require('./ensure-smart-build.cjs');

function fixture({existing = false, base = 'old', files = ['OsmAnd/src/Synthetic.kt'], status = 'ahead'} = {}) {
  const calls = [];
  return {calls, input: {
    context: {repo: {owner: 'synthetic', repo: 'fixture'}},
    core: {info() {}, notice() {}},
    github: {rest: {
      repos: {
        async getBranch() { return {data: {commit: {sha: 'new'}}}; },
        async compareCommitsWithBasehead() { return {data: {files: files.map(filename => ({filename})), status}}; },
      },
      actions: {
        async listWorkflowRuns(args) {
          return {data: {workflow_runs: args.head_sha ? (existing ? [{head_sha: 'new'}] : []) : (base ? [{head_sha: base}] : [])}};
        },
        async createWorkflowDispatch(args) { calls.push(args); },
      },
    }},
  }};
}

test('a missing source build is dispatched automatically', async () => {
  const {input, calls} = fixture();
  assert.equal(await ensure(input), 'dispatched');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].ref, 'master');
});
test('queued, running or completed builds are never duplicated', async () => {
  const {input, calls} = fixture({existing: true});
  assert.equal(await ensure(input), 'existing');
  assert.equal(calls.length, 0);
});
test('documentation-only pushes do not build the APK', async () => {
  const {input, calls} = fixture({files: ['BUILD_CHANGES.md', 'HOW_TO_BUILD.md']});
  assert.equal(await ensure(input), 'unchanged');
  assert.equal(calls.length, 0);
});
test('manual rebuild marker and core source changes do build', async () => {
  for (const path of ['.github/workflows/build-osmand-smart.yml', 'OsmAnd-java/src/Synthetic.java']) {
    const {input} = fixture({files: [path]});
    assert.equal(await ensure(input), 'dispatched');
  }
});
test('an incomplete comparison or rewritten branch cannot silently skip source', async () => {
  for (const options of [{files: Array(300).fill('README.md')}, {status: 'diverged', files: []}, {status: 'behind', files: []}, {base: null}]) {
    const {input} = fixture(options);
    assert.equal(await ensure(input), 'dispatched');
  }
});
