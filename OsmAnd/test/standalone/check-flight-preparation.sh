#!/usr/bin/env bash
set -euo pipefail
# Accept an explicit directory of test dependencies; never inspect credentials or user data.
test_libs=$(cd "${1:?Pass the test dependency directory}" && pwd -P)
repo_root=$(cd "$(dirname "$0")/../../.." && pwd -P)
test -f "$repo_root/OsmAnd/src/net/osmand/plus/plugins/flightmode/FlightPreparation.kt"
for lib in ktfmt json coroutines junit hamcrest gson; do test -s "$test_libs/$lib.jar"; done
output_parent="$repo_root/OsmAnd/build"
mkdir -p "$output_parent"
test_output=$(mktemp -d "$output_parent/flight-logic.XXXXXXXX")
sources="$repo_root/OsmAnd/src/net/osmand/plus/plugins/flightmode"
classpath="$test_output:$test_libs/ktfmt.jar:$test_libs/json.jar:$test_libs/coroutines.jar:$test_libs/junit.jar:$test_libs/hamcrest.jar:$test_libs/gson.jar"
javac -d "$test_output" "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPoseSolver.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPlaneGeometry.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPoseDiagnostics.java"
javac -d "$test_output" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/Log.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/PlatformUtil.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/NetworkUtils.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/Algorithms.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/LIFOBlockingDeque.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/map/MapTileDownloader.java"
java -cp "$test_libs/ktfmt.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -classpath "$classpath" -d "$test_output" \
  "$sources/FlightModels.kt" "$sources/FlightPreparation.kt" "$sources/FlightRecordingData.kt" \
  "$sources/FlightPhotoCalibration.kt" "$sources/FlightTerrainCoordinates.kt" \
  "$sources/FlightPhotoFitDiagnostics.kt" \
  "$sources/FlightPhotoDepth.kt" "$sources/FlightPhotoTreatmentJson.kt" \
  "$sources/FlightDownloadCancellation.kt" \
  "$sources/FlightNetworkAccess.kt" "$sources/FlightRasterDownloadAccess.kt" "$sources/FlightJournalSummaries.kt" "$sources/FlightPreparationSave.kt" \
  "$sources/FlightCloudArchive.kt" "$sources/FlightCloudClient.kt" "$sources/FlightJourneyNaming.kt" "$sources/FlightCloudVersions.kt" \
  "$sources/FlightOfflinePreparation.kt" "$sources/FlightLivePredictor.kt" \
  "$sources/FlightRouteHypothesis.kt" "$sources/FlightTerrainTilePlanner.kt" \
  "$sources/FlightTerrainModels.kt" "$sources/FlightProfilePlanner.kt" "$sources/FlightTrackMath.kt" \
  "$sources/FlightRecordingLines.kt" \
  "$sources/FlightWorkspaceNavigation.kt" "$sources/FlightLocalNavigation.kt" "$sources/FlightLiveTimeline.kt" "$sources/FlightPhotoCaptureJson.kt" \
  "$sources/FlightSampleInterpolator.kt" "$sources/FlightReplayEngine.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightPreparationNonSubjectFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudNonSubjectFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudClockFixture.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightJournalAndroidFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudArchiveTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightRecordingLinesTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPreparationLogicTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoCalibrationPersistenceTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoDepthTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoFitDiagnosticsTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightDownloadCancellationTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightWorkspaceTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightLocalNavigationTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightOfflineJourneyTest.kt"
cd "$repo_root"
java -cp "$test_output:$classpath" org.junit.runner.JUnitCore \
  net.osmand.test.junit.FlightPreparationLogicTest net.osmand.test.junit.FlightRecordingLinesTest net.osmand.test.junit.FlightWorkspaceTest net.osmand.test.junit.FlightLocalNavigationTest net.osmand.test.junit.FlightPhotoCalibrationPersistenceTest net.osmand.test.junit.FlightPhotoDepthTest net.osmand.test.junit.FlightDownloadCancellationTest net.osmand.test.junit.FlightPhotoFitDiagnosticsTest net.osmand.test.junit.FlightCloudArchiveTest net.osmand.test.junit.FlightOfflineJourneyTest
echo "Flight logic check classes: $test_output"
