#!/usr/bin/env python3
"""Test the flight tube with the checked-out core's real grid-cutting algorithm.

Only Qt/platform includes and unused declarations are omitted. Math and clipping
are compiled verbatim, avoiding a passing replica that differs from the APK.
This does not replace a rendering check on a phone.
"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-root", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    core_map = args.core_root.resolve() / "src" / "Map"
    vector_line = (core_map / "VectorLine_P.cpp").read_text()
    assert re.search(
        r"const auto cellsPerTileSize\s*=\s*_isTubular\s*\?\s*"
        r"FlightTubeMesh::gridCellsPerTile\(_flatEarth, static_cast<int>\(_mapZoomLevel\)\)",
        vector_line,
    ), "The actual native tube must use the bounded grid policy being tested"
    header = (core_map / "GeometryModifiers.h").read_text()
    implementation = (core_map / "GeometryModifiers.cpp").read_text()
    header_end = header.index("\t// Create vertical faces for path")
    implementation_start = implementation.index("// Cut the mesh by tiles")
    implementation_end = implementation.index("bool OsmAnd::GeometryModifiers::getTesselatedPlane")
    header = re.sub(r"^#include.*$", "", header[:header_end], flags=re.MULTILINE)
    test_source = Path(__file__).with_name("FlightTubeGridPipelineTest.cpp").resolve()
    with tempfile.TemporaryDirectory(prefix="flight-tube-pipeline-", dir=args.work_dir.resolve()) as folder:
        work = Path(folder)
        (work / "FlightCoreGridExtract.h").write_text(header + "};\n}\n#endif\n")
        (work / "FlightCoreGridExtract.cpp").write_text(
            implementation[implementation_start:implementation_end]
        )
        binary = work / "flight-tube-pipeline-test"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++11", "-O1", "-fsanitize=undefined",
            "-I", str(work), str(test_source), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary)], check=True, timeout=120)


if __name__ == "__main__":
    main()
