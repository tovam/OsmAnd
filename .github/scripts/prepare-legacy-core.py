#!/usr/bin/env python3
"""Make legacy Skia dependency failures visible and limit network concurrency."""

import argparse
from pathlib import Path
import shlex


SYNC_WORKERS = '''def multithread(function, list_of_arg_lists):
  # OsmAnd: bounded dependency sync; worker failures must reach the caller.
  from concurrent.futures import ThreadPoolExecutor
  import time

  def sync(args):
    for attempt in range(3):
      try:
        return function(*args)
      except subprocess.CalledProcessError:
        if attempt == 2:
          raise
        time.sleep(5 * (attempt + 1))

  with ThreadPoolExecutor(max_workers=4) as executor:
    futures = [executor.submit(sync, args) for args in list_of_arg_lists]
    for future in futures:
      future.result()

'''


def patch_sync(path):
    source = path.read_text()
    if "# OsmAnd: bounded dependency sync" in source:
        return
    start = source.index("def multithread(function, list_of_arg_lists):")
    end = source.index("\ndef main(", start)
    original = source[start:end]
    if "threading.Thread(None, function, None, args)" not in original or "thread.join()" not in original:
        raise ValueError("Unsupported Skia dependency worker implementation")
    patched = source[:start] + SYNC_WORKERS + source[end:]
    compile(patched, str(path), "exec")
    path.write_text(patched)


def fail_fast(source):
    if not source.startswith("#!/bin/bash\n"):
        raise ValueError("Unsupported legacy configure script")
    if not source.startswith("#!/bin/bash\nset -e\n"):
        source = source.replace("#!/bin/bash\n", "#!/bin/bash\nset -e\n", 1)
    return source


def prepare(core):
    configure = core / "externals/configure.sh"
    configure.write_text(fail_fast(configure.read_text()))
    skia = core / "externals/skia/configure.sh"
    source = fail_fast(skia.read_text())
    command = "python3 " + shlex.quote(str(Path(__file__).resolve())) + ' patch-sync "$SRCLOC/upstream.patched/tools/git-sync-deps"'
    if command not in source:
        if source.count("\n./git-sync-deps\n") != 1:
            raise ValueError("Unsupported legacy Skia configure script")
        source = source.replace("\n./git-sync-deps\n", "\n" + command + "\n./git-sync-deps\n")
    skia.write_text(source)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["prepare", "patch-sync"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.operation == "prepare":
        prepare(args.path)
    else:
        patch_sync(args.path)
