#!/usr/bin/env python3
"""Fetch pinned legacy Skia dependencies with bounded transfers and visible failures."""

import argparse
from pathlib import Path
import shlex


PINNED_CHECKOUT = '''def git_checkout_to_directory(git, repo, commithash, directory, verbose):
  # OsmAnd: shallow pinned dependency checkout, not every branch and historical blob.
  # Legacy configure explicitly overrides Skia's older HarfBuzz revision with 2.8.1.
  # Fetch that existing override directly so its later checkout survives shallow history.
  if os.path.normpath(directory).endswith(os.path.join('third_party', 'externals', 'harfbuzz')):
    commithash = 'b37f03f16b39d397a626f097858e9ae550234ca0'
  created = not os.path.isdir(directory)
  if created:
    subprocess.check_call([git, 'init', '--quiet', directory])
    subprocess.check_call([git, 'remote', 'add', 'origin', repo], cwd=directory)

  if not is_git_toplevel(git, directory):
    raise RuntimeError('Dependency is not a top-level Git directory: ' + directory)
  if git_repository_sync_is_disabled(git, directory):
    sys.stdout.write('%s\\n  SYNC IS DISABLED.\\n' % directory)
    return
  with open(os.devnull, 'w') as devnull:
    if not created and 0 == subprocess.call([git, 'checkout', '--quiet', commithash],
                                            cwd=directory, stderr=devnull):
      if verbose:
        status(directory, commithash, False)
      return
  subprocess.check_call([git, 'remote', 'set-url', 'origin', repo], cwd=directory)
  # Fetch the exact unchanged DEPS revision. A failed fetch leaves a valid repo for retry.
  subprocess.check_call([git, '-c', 'http.version=HTTP/1.1', 'fetch', '--quiet',
                         '--depth=1', 'origin', commithash], cwd=directory, timeout=180)
  subprocess.check_call([git, 'checkout', '--quiet', commithash], cwd=directory)
  if verbose:
    status(directory, commithash, True)

'''


SYNC_WORKERS = '''def multithread(function, list_of_arg_lists):
  # OsmAnd: bounded dependency sync; worker failures must reach the caller.
  from concurrent.futures import ThreadPoolExecutor
  import time

  def sync(args):
    for attempt in range(3):
      try:
        return function(*args)
      except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
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
    start = source.index("def git_checkout_to_directory(")
    end = source.index("\ndef parse_file_to_dict(", start)
    original = source[start:end]
    if "# OsmAnd: shallow pinned dependency checkout" not in original and (
        "[git, 'clone', '--quiet', '--no-checkout', repo, directory]" not in original
        or "[git, 'fetch', '--quiet']" not in original
    ):
        raise ValueError("Unsupported Skia dependency checkout implementation")
    source = source[:start] + PINNED_CHECKOUT + source[end:]
    start = source.index("def multithread(function, list_of_arg_lists):")
    end = source.index("\ndef main(", start)
    original = source[start:end]
    if "# OsmAnd: bounded dependency sync" not in original and (
        "threading.Thread(None, function, None, args)" not in original or "thread.join()" not in original
    ):
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
    if 'VERSION_HARFBUZZ=' in source and 'VERSION_HARFBUZZ="b37f03f"' not in source:
        raise ValueError("Unsupported legacy HarfBuzz override")
    # Upstream read intentionally reaches EOF after assigning the patch filename.
    # Its status is 1 even on success; preserve that behavior under set -e.
    source = source.replace('read  -rd \'\' PATCH <<< "$PATCH"\n',
                            'read  -rd \'\' PATCH <<< "$PATCH" || true\n')
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
