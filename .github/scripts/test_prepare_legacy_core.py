import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("prepare-legacy-core.py")
spec = importlib.util.spec_from_file_location("legacy_preparation", SCRIPT)
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)

UPSTREAM = '''import os
import sys
import subprocess
import threading
def git_checkout_to_directory(git, repo, commithash, directory, verbose):
  if not os.path.isdir(directory):
    subprocess.check_call([git, 'clone', '--quiet', '--no-checkout', repo, directory])
    subprocess.check_call([git, 'checkout', '--quiet', commithash], cwd=directory)
    return
  subprocess.check_call([git, 'fetch', '--quiet'], cwd=directory)
  subprocess.check_call([git, 'checkout', '--quiet', commithash], cwd=directory)
def parse_file_to_dict(path):
  return {}
def multithread(function, list_of_arg_lists):
  threads = []
  for args in list_of_arg_lists:
    thread = threading.Thread(None, function, None, args)
    thread.start()
    threads.append(thread)
  for thread in threads:
    thread.join()
def main(argv):
  return 0
'''


class LegacyDependencyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=SCRIPT.parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sync = self.root / "git-sync-deps"
        self.sync.write_text(UPSTREAM)
        legacy.patch_sync(self.sync)
        self.namespace = {}
        exec(compile(self.sync.read_text(), str(self.sync), "exec"), self.namespace)

    def test_only_failed_dependency_is_retried(self):
        calls = {"good": 0, "flaky": 0}
        def download(name):
            calls[name] += 1
            if name == "flaky" and calls[name] < 3:
                raise subprocess.CalledProcessError(128, "synthetic fetch")
        with patch("time.sleep") as sleep:
            self.namespace["multithread"](download, [("good",), ("flaky",)])
        self.assertEqual(calls, {"good": 1, "flaky": 3})
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [5, 10])

    def test_permanent_failure_reaches_caller_after_three_attempts(self):
        calls = []
        def download():
            calls.append(1)
            raise subprocess.CalledProcessError(128, "synthetic fetch")
        with patch("time.sleep"), self.assertRaises(subprocess.CalledProcessError):
            self.namespace["multithread"](download, [()])
        self.assertEqual(len(calls), 3)

    def test_stalled_fetch_is_retried_and_timeout_remains_visible(self):
        calls = []
        def download():
            calls.append(1)
            raise subprocess.TimeoutExpired("synthetic fetch", 180)
        with patch("time.sleep"), self.assertRaises(subprocess.TimeoutExpired):
            self.namespace["multithread"](download, [()])
        self.assertEqual(len(calls), 3)

    def test_exact_older_commit_is_shallow_and_reused_without_network(self):
        environment = {"PATH": os.environ["PATH"], "GIT_CONFIG_NOSYSTEM": "1",
                       "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull,
                       "GIT_AUTHOR_NAME": "Synthetic", "GIT_COMMITTER_NAME": "Synthetic",
                       "GIT_AUTHOR_EMAIL": "synthetic@example.invalid",
                       "GIT_COMMITTER_EMAIL": "synthetic@example.invalid"}
        with patch.dict(os.environ, environment, clear=True):
            source = self.root / "synthetic-upstream"
            subprocess.run(["git", "init", "--quiet", str(source)], check=True)
            def git(*args, cwd=source):
                return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()
            for value in ["first", "pinned", "newer"]:
                source.joinpath("proof.txt").write_text(value)
                git("add", "proof.txt")
                git("commit", "--quiet", "-m", value)
                if value == "pinned":
                    pinned = git("rev-parse", "HEAD")
            target = self.root / "shallow-dependency"
            self.namespace["is_git_toplevel"] = lambda executable, directory: git("rev-parse", "--show-toplevel", cwd=directory) == directory
            self.namespace["git_repository_sync_is_disabled"] = lambda *args: False
            self.namespace["status"] = lambda *args: None
            checkout = self.namespace["git_checkout_to_directory"]
            checkout("git", source.as_uri(), pinned, str(target), False)
            self.assertEqual(git("rev-parse", "HEAD", cwd=target), pinned)
            self.assertEqual(git("rev-list", "--count", "HEAD", cwd=target), "1")
            self.assertEqual(target.joinpath("proof.txt").read_text(), "pinned")
            self.assertEqual(git("config", "--get", "remote.origin.url", cwd=target), source.as_uri())
            # Successful dependencies need no repeated transfer, even when upstream disappears.
            source.rename(self.root / "removed-synthetic-upstream")
            checkout("git", source.as_uri(), pinned, str(target), False)
            self.assertEqual(git("rev-parse", "HEAD", cwd=target), pinned)

    def test_failed_transfer_leaves_a_repo_that_can_resume_at_the_same_pin(self):
        target = self.root / "synthetic-dependency"
        commands = []
        fetched = False
        def run(command, **kwargs):
            nonlocal fetched
            commands.append(command)
            if "init" in command:
                target.mkdir()
            if "fetch" in command:
                if not fetched:
                    fetched = True
                    raise subprocess.CalledProcessError(128, command)
                self.assertIn("--depth=1", command)
                self.assertEqual(command[-1], "a" * 40)
                self.assertEqual(kwargs["timeout"], 180)
        self.namespace["is_git_toplevel"] = lambda *args: True
        self.namespace["git_repository_sync_is_disabled"] = lambda *args: False
        with patch("subprocess.check_call", side_effect=run), patch("subprocess.call", return_value=1), patch("time.sleep"):
            self.namespace["multithread"](self.namespace["git_checkout_to_directory"],
                [("git", "https://source.invalid/synthetic.git", "a" * 40, str(target), False)])
        self.assertEqual(sum("init" in c for c in commands), 1)
        self.assertEqual(sum("fetch" in c for c in commands), 2)
        self.assertFalse(any("clone" in c for c in commands))

    def test_dependency_directory_cannot_modify_its_parent_repository(self):
        target = self.root / "not-a-repo"
        target.mkdir()
        self.namespace["is_git_toplevel"] = lambda *args: False
        with patch("subprocess.check_call") as command, self.assertRaises(RuntimeError):
            self.namespace["git_checkout_to_directory"]("git", "https://source.invalid/synthetic.git", "a" * 40, str(target), False)
        command.assert_not_called()

    def test_shallow_harfbuzz_keeps_the_existing_legacy_override(self):
        target = self.root / "third_party/externals/harfbuzz"
        self.namespace["is_git_toplevel"] = lambda *args: True
        self.namespace["git_repository_sync_is_disabled"] = lambda *args: False
        with patch("subprocess.check_call") as command:
            self.namespace["git_checkout_to_directory"]("git", "https://source.invalid/harfbuzz.git",
                "3a74ee528255cc027d84b204a87b5c25e47bff79", str(target), False)
        fetch = next(call.args[0] for call in command.call_args_list if "fetch" in call.args[0])
        checkout = next(call.args[0] for call in command.call_args_list if "checkout" in call.args[0])
        pinned = "b37f03f16b39d397a626f097858e9ae550234ca0"
        self.assertEqual(fetch[-1], pinned)
        self.assertEqual(checkout[-1], pinned)

    def test_patch_upgrades_already_prepared_workers_and_rejects_unknown_checkout(self):
        already_prepared = self.sync.read_text().replace(
            "except (subprocess.CalledProcessError, subprocess.TimeoutExpired):",
            "except subprocess.CalledProcessError:")
        self.sync.write_text(already_prepared)
        legacy.patch_sync(self.sync)
        self.assertIn("except (subprocess.CalledProcessError, subprocess.TimeoutExpired):", self.sync.read_text())
        unknown = UPSTREAM.replace("[git, 'fetch', '--quiet']", "[git, 'pull']")
        self.sync.write_text(unknown)
        with self.assertRaises(ValueError):
            legacy.patch_sync(self.sync)
        self.assertEqual(self.sync.read_text(), unknown)

    def test_at_most_four_downloads_run_at_once(self):
        active = maximum = 0
        lock = threading.Lock()
        barrier = threading.Barrier(4)
        def download():
            nonlocal active, maximum
            with lock:
                active += 1
                maximum = max(maximum, active)
            barrier.wait(timeout=5)
            with lock:
                active -= 1
        self.namespace["multithread"](download, [()] * 16)
        self.assertEqual(maximum, 4)

    def test_patch_is_idempotent_and_rejects_unknown_worker(self):
        before = self.sync.read_text()
        legacy.patch_sync(self.sync)
        self.assertEqual(self.sync.read_text(), before)
        self.sync.write_text(UPSTREAM.replace("thread.join()", "pass"))
        with self.assertRaises(ValueError):
            legacy.patch_sync(self.sync)

    def test_configure_propagates_failure_before_native_build(self):
        core = self.root / "core-legacy"
        (core / "externals/skia").mkdir(parents=True)
        configure = core / "externals/configure.sh"
        configure.write_text('#!/bin/bash\nexit 19\n')
        skia = core / "externals/skia/configure.sh"
        skia.write_text('#!/bin/bash\nSRCLOC="unused"\n./git-sync-deps\n')
        legacy.prepare(core)
        first = skia.read_text()
        legacy.prepare(core)
        self.assertEqual(first, skia.read_text())
        subprocess.run(["bash", "-n", str(skia)], check=True)
        app = self.root / "android/OsmAnd"
        app.mkdir(parents=True)
        script = app / "old-ndk-build.sh"
        script.write_text((SCRIPT.parents[2] / "OsmAnd/old-ndk-build.sh").read_text())
        sdk = self.root / "sdk"
        ndk = self.root / "ndk"
        sdk.mkdir()
        ndk.mkdir()
        marker = self.root / "compiled"
        build = ndk / "ndk-build"
        build.write_text('#!/bin/bash\ntouch "' + str(marker) + '"\n')
        build.chmod(0o755)
        configure.chmod(0o755)
        result = subprocess.run(["bash", str(script)], env={"PATH": "/usr/bin:/bin", "ANDROID_SDK_ROOT": str(sdk), "ANDROID_NDK_ROOT": str(ndk)})
        self.assertEqual(result.returncode, 19)
        self.assertFalse(marker.exists())

    def test_patch_filename_read_at_eof_is_not_a_preparation_failure(self):
        core = self.root / "core-legacy"
        skia = core / "externals/skia"
        tools = skia / "upstream.patched/tools"
        tools.mkdir(parents=True)
        (core / "externals/configure.sh").write_text('#!/bin/bash\nexit 0\n')
        sync = tools / "git-sync-deps"
        sync.write_text('#!/usr/bin/env python3\n' + UPSTREAM)
        sync.chmod(0o755)
        marker = skia / "patched"
        script = skia / "configure.sh"
        script.write_text('#!/bin/bash\nSRCLOC="' + str(skia) + '"\n'
            'cd "$SRCLOC/upstream.patched/tools"\n./git-sync-deps\n'
            'PATCH="$SRCLOC/a patch.patch"\n'
            'read  -rd \'\' PATCH <<< "$PATCH"\n'
            'printf \'%s\' "$PATCH" > "' + str(marker) + '"\n')
        legacy.prepare(core)
        result = subprocess.run(["bash", str(script)])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), str(skia / "a patch.patch"))

    def test_complete_pinned_skia_configure_applies_patch(self):
        # Execute the real upstream shell flow; Git downloads use synthetic sources.
        # Fixture from OsmAnd-core-legacy at 2d46e11ff329e53eb89dca2856ecb5fbb7856b4f (trailing whitespace normalized).
        core = self.root / "core-legacy"
        skia = core / "externals/skia"
        patches = skia / "patches"
        patches.mkdir(parents=True)
        configure = core / "externals/configure.sh"
        configure.write_text('#!/bin/bash\nexit 0\n')
        script = skia / "configure.sh"
        script.write_text((SCRIPT.parent / "fixtures/legacy-skia-configure.sh").read_text())
        patches.joinpath("fixture.patch").write_text(
            '--- a/proof.txt\n+++ b/proof.txt\n@@ -1 +1 @@\n-before\n+after\n')
        fixture = self.root / "synthetic-sync"
        fixture.write_text('#!/usr/bin/env python3\n' + UPSTREAM)
        binaries = self.root / "bin"
        binaries.mkdir()
        git = binaries / "git"
        git.write_text('''#!/bin/bash
if [[ "$1" == checkout && "$PWD" == */upstream.original ]]; then
  mkdir -p tools third_party/externals/harfbuzz
  cp "$SKIA_SYNC_FIXTURE" tools/git-sync-deps
  chmod +x tools/git-sync-deps
  printf 'before\\n' > proof.txt
fi
''')
        git.chmod(0o755)
        legacy.prepare(core)
        prepared = script.read_text()
        legacy.prepare(core)
        self.assertEqual(script.read_text(), prepared)
        result = subprocess.run(["bash", str(script)], capture_output=True, text=True,
            env={"PATH": str(binaries) + ":/usr/bin:/bin", "SKIA_SYNC_FIXTURE": str(fixture)})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((skia / "upstream.patched/proof.txt").read_text(), "after\n")


if __name__ == "__main__":
    unittest.main()
