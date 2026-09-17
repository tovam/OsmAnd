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
echo "Flight logic check classes: $test_output"
sources="$repo_root/OsmAnd/src/net/osmand/plus/plugins/flightmode"
classpath="$test_output:$test_libs/ktfmt.jar:$test_libs/json.jar:$test_libs/coroutines.jar:$test_libs/junit.jar:$test_libs/hamcrest.jar:$test_libs/gson.jar"
javac -d "$test_output" "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPoseSolver.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPlaneGeometry.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PreparedResourceQueue.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/PhotoPoseDiagnostics.java"
javac -d "$test_output" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/Log.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/PlatformUtil.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/NetworkUtils.java" \
  "$repo_root/OsmAnd/test/standalone/java/flight-download/Algorithms.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/util/LIFOBlockingDeque.java" \
  "$repo_root/OsmAnd-java/src/main/java/net/osmand/map/MapTileDownloader.java"
java -Djava.io.tmpdir="$test_output" -cp "$test_libs/ktfmt.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -classpath "$classpath" -d "$test_output" \
  "$sources/FlightModels.kt" "$sources/FlightPreparation.kt" "$sources/FlightRecordingData.kt" "$sources/FlightCameraOptics.kt" \
  "$sources/FlightPhotoCalibration.kt" "$sources/FlightTerrainCoordinates.kt" \
  "$sources/FlightPhotoFitDiagnostics.kt" \
  "$sources/FlightPhotoDepth.kt" "$sources/FlightPhotoTreatmentJson.kt" \
  "$sources/FlightDownloadCancellation.kt" \
  "$sources/FlightArchiveInventory.kt" \
  "$sources/FlightJournalOperations.kt" \
  "$sources/FlightPhotoOrientation.kt" \
  "$sources/FlightTerrainMeshBuilder.kt" "$sources/FlightTerrainCpuScheduler.kt" \
  "$sources/FlightTerrainLodPolicy.kt" "$sources/FlightTerrainResidency.kt" \
  "$sources/FlightNetworkAccess.kt" "$sources/FlightRasterDownloadAccess.kt" "$sources/FlightJournalSummaries.kt" "$sources/FlightPreparationSave.kt" \
  "$sources/FlightCloudArchive.kt" "$sources/FlightCloudClient.kt" "$sources/FlightJourneyNaming.kt" "$sources/FlightCloudVersions.kt" "$sources/FlightLibraryPresentation.kt" \
  "$sources/FlightOfflinePreparation.kt" "$sources/FlightLivePredictor.kt" "$sources/FlightLiveSimulation.kt" \
  "$sources/FlightWorkPolicy.kt" "$sources/FlightAssetScheduler.kt" "$sources/FlightDisplaySafety.kt" \
  "$sources/FlightSceneStreamingEngine.kt" \
  "$sources/FlightRouteHypothesis.kt" "$sources/FlightTerrainTilePlanner.kt" \
  "$sources/FlightTerrainModels.kt" "$sources/FlightProfilePlanner.kt" "$sources/FlightTrackMath.kt" \
  "$sources/FlightRecordingLines.kt" \
  "$sources/FlightLiveMonitoring.kt" "$sources/FlightOfflineCoverage.kt" \
  "$sources/FlightNativeTrackUpdatePlan.kt" \
  "$sources/FlightCameraKeyPolicy.kt" \
  "$sources/FlightWorkspaceNavigation.kt" "$sources/FlightLocalNavigation.kt" "$sources/FlightLiveTimeline.kt" "$sources/FlightPhotoCaptureJson.kt" \
  "$sources/FlightSampleInterpolator.kt" "$sources/FlightReplayEngine.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightPreparationNonSubjectFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudNonSubjectFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudClockFixture.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightJournalAndroidFixtures.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightCloudArchiveTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightArchiveInventoryTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightJournalOperationsTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightPhotoOrientationTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightTerrainResidencyTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightRecordingLinesTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightStreamingVisibilityTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightJournalSummaryMetadataTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightLibraryPresentationTest.kt" \
  "$repo_root/OsmAnd/test/standalone/kotlin/FlightMonitoringTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightNativeTrackUpdatePlanTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightCameraKeyPolicyTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightCameraWorkPolicyTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPreparationLogicTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoCalibrationPersistenceTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoDepthTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightPhotoFitDiagnosticsTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightDownloadCancellationTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightWorkspaceTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightLocalNavigationTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightOfflineJourneyTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightLiveSimulationTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightWorkPolicyTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightDisplaySafetyTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightAssetSchedulerTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightCameraOpticsTest.kt" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightRecordingPolicyTest.kt"
java -Djava.io.tmpdir="$test_output" -cp "$test_libs/ktfmt.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -classpath "$classpath" -Xfriend-paths="$test_output" -d "$test_output" \
  "$repo_root/OsmAnd/test/java/net/osmand/test/junit/FlightRecordingMetadataPersistenceTest.kt"
java -Djava.io.tmpdir="$test_output" -cp "$classpath" org.junit.runner.JUnitCore \
  net.osmand.test.junit.FlightRecordingMetadataPersistenceTest \
  net.osmand.test.junit.FlightNativeTrackUpdatePlanTest \
  net.osmand.test.junit.FlightCameraKeyPolicyTest \
  net.osmand.test.junit.FlightCameraWorkPolicyTest
cd "$repo_root"
java -Djava.io.tmpdir="$test_output" -cp "$test_output:$classpath" org.junit.runner.JUnitCore \
  net.osmand.test.junit.FlightPreparationLogicTest net.osmand.test.junit.FlightRecordingLinesTest net.osmand.test.junit.FlightWorkspaceTest net.osmand.test.junit.FlightLocalNavigationTest net.osmand.test.junit.FlightPhotoCalibrationPersistenceTest net.osmand.test.junit.FlightPhotoDepthTest net.osmand.test.junit.FlightDownloadCancellationTest net.osmand.test.junit.FlightPhotoFitDiagnosticsTest net.osmand.test.junit.FlightCloudArchiveTest net.osmand.test.junit.FlightOfflineJourneyTest net.osmand.test.junit.FlightJournalSummaryMetadataTest net.osmand.test.junit.FlightLibraryPresentationTest
java -Djava.io.tmpdir="$test_output" -cp "$test_output:$classpath" org.junit.runner.JUnitCore net.osmand.test.junit.FlightLiveSimulationTest net.osmand.test.junit.FlightCameraOpticsTest net.osmand.test.junit.FlightWorkPolicyTest net.osmand.test.junit.FlightAssetSchedulerTest net.osmand.test.junit.FlightStreamingVisibilityTest net.osmand.test.junit.FlightDisplaySafetyTest net.osmand.test.junit.FlightRecordingPolicyTest net.osmand.test.junit.FlightArchiveInventoryTest net.osmand.test.junit.FlightJournalOperationsTest net.osmand.test.junit.FlightPhotoOrientationTest net.osmand.test.junit.FlightTerrainResidencyTest net.osmand.test.junit.FlightMonitoringTest
echo "Flight logic check classes: $test_output"
