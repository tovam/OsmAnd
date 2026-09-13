package net.osmand.plus.plugins.flightmode

// Standalone logic checks do not exercise the photo editor or renderer. The production data model
// references these two components; the tested recorder, planner and geometry algorithms are real.
class FlightPhotoCalibration

object FlightTerrainMeshBuilder {
    const val DEFAULT_GRID_QUADS = 32
}
