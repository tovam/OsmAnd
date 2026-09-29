#!/usr/bin/env python3
"""Exercise the patched native raster path with synthetic map/Skia adapters.

The selection, request transformation, LRU and pixel sampling are compiled from
the actual core source, not a duplicate implementation. No real map data is used.
This is not an Android/Skia ABI test or a substitute for a device rendering check.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


def extract_method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        if cursor >= len(source):
            raise ValueError(f"Unterminated production method: {signature}")
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    return source[start:cursor] + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-root", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    source = (args.core_root / "src/Map/MapRasterLayerProvider_P.cpp").read_text()
    anonymous_start = source.find("namespace\n{")
    anonymous = "" if anonymous_start < 0 else source[anonymous_start:source.index("OsmAnd::MapRasterLayerProvider_P::MapRasterLayerProvider_P(")]
    methods = source[source.index("bool OsmAnd::MapRasterLayerProvider_P::obtainRasterizedTile("):source.index("void OsmAnd::MapRasterLayerProvider_P::initialize()")]
    header = (args.core_root / "src/Map/MapRasterLayerProvider_P.h").read_text()
    header = re.sub(r"^#include.*$", "", header, flags=re.MULTILINE)
    # The derived static copy deliberately omits base fields. Using dst = src in
    # an adapter previously hid a null controller crash in the actual fallback.
    request_copies = ""
    for provider in ("IMapDataProvider", "IMapTiledDataProvider"):
        request_source = (args.core_root / f"src/Map/{provider}.cpp").read_text()
        request_copies += extract_method(request_source, f"void OsmAnd::{provider}::Request::copy(")
    test = Path(__file__).with_name("BasemapRasterPipelineTest.cpp").resolve()
    with tempfile.TemporaryDirectory(prefix="basemap-pipeline-", dir=args.work_dir.resolve()) as folder:
        work = Path(folder)
        (work / "BasemapRasterHeader.h").write_text(header)
        (work / "BasemapRasterMethods.h").write_text(anonymous + methods)
        (work / "BasemapRequestCopyMethods.h").write_text(request_copies)
        binary = work / "test"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++11", "-Wall", "-Wextra", "-Werror",
            "-fsanitize=address,undefined", "-fno-sanitize-recover=all",
            "-I", str(work), str(test), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True, timeout=30)


if __name__ == "__main__":
    main()
