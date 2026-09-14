#!/usr/bin/env bash
set -euo pipefail
# Accept an explicit directory of test dependencies; never inspect credentials or user data.
test_libs=$(cd "${1:?Pass the test dependency directory}" && pwd -P)
repo_root=$(cd "$(dirname "$0")/../../.." && pwd -P)
test -f "$repo_root/OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightPreparation.kt"
for lib in ktfmt json coroutines junit hamcrest; do test -s "$test_libs/$lib.jar"; done
output_parent="$repo_root/OsmAnd/build"
mkdir -p "$output_parent"
test_output=$(mktemp -d "$output_parent/flight-logic.XXXXXXXX")
sources="$repo_root/OsmAnd/src/net/osmand/plus/plugins/flightmode"
classpath="$test_libs/ktfmt.jar:$test_libs/json.jar:$test_libs/coroutines.jar:$test_libs/junit.jar:$test_libs/hamcrest.jar"
java -cp "$test_libs/ktfmt.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -classpath "$classpath" -d "$test_output" \
  "$sources/FlightModels.kt" "$sources/FlightPreparation.kt" "$sources/FlightRecordingData.kt" \
  "$sources/FlightOfflinePreparation.kt" "$sources/FlightLivePredictor.kt" \
  "$sources/FlightRouteHypothesis.kt" "$sources/FlightTerrainTilePlanner.kt" \
  "$sources/FlightTerrainModels.kt" "$sources/FlightProfilePlanner.kt" "$sources/FlightTrackMath.kt" \
  "$sources/FlightRecordingLines.kt" \
  "$sources/FlightWorkspaceNavigation.kt" "$sources/FlightLiveTimeline.kt" "$sources/FlightPhotoCaptureJson.kt" \
  "$sources/FlightSampleInterpolator.kt" "$sources/FlightReplayEngine.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightPreparationNonSubjectFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightRecordingLinesTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPreparationLogicTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightWorkspaceTest.kt"
cd "$repo_root"
java -cp "$test_output:$classpath" org.junit.runner.JUnitCore \
  net.osmand.test.junit.FlightPreparationLogicTest net.osmand.test.junit.FlightRecordingLinesTest net.osmand.test.junit.FlightWorkspaceTest
echo "Flight logic check classes: $test_output"
