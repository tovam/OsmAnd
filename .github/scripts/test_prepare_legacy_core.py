import importlib.util
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

UPSTREAM = '''import subprocess
import threading
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


if __name__ == "__main__":
    unittest.main()
