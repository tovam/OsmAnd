// The runner injects the width calculation extracted verbatim from patched VectorLine_P.cpp.
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

struct Utilities { static double getPowZoom(double z) { return std::pow(2.0, z); } };
struct AtlasMapRenderer { enum { TileSize3D = CORE_TILE_SIZE }; };
static double qSqrt(double value) { return std::sqrt(value); }
struct WidthFixture {
    bool _isTubular = true, _hasElevationDataProvider = true;
    // Match upstream's snapped geometry state, rather than the removed zoom() API.
    struct ZoomState {
        float geometryZoom = 8, surfaceGeometryZoom = 8, mapVisualZoomShift = 0;
    } _zoomState;
    double _lineWidth = 9.6;
    double thickness() const {
#include "FlightCoreWidthExtract.h"
    }
};

int main() {
    WidthFixture fixture;
    int checked = 0;
    for (float map : {3.f, 8.f, 8.125f, 14.f, 14.875f, 18.f}) {
        fixture._zoomState.geometryZoom = map;
        fixture._hasElevationDataProvider = false;
        const double expected = fixture.thickness();
        assert(std::isfinite(expected) && expected > 0);
        fixture._hasElevationDataProvider = true;
        for (float surface : {-1.f, 3.f, 8.f, 14.f, 18.f, std::numeric_limits<float>::quiet_NaN()}) {
            fixture._zoomState.surfaceGeometryZoom = surface;
            assert(std::abs(fixture.thickness() / expected - 1.0) < 1e-6);
            checked++;
        }
    }
    // Upstream ribbons intentionally keep their old surface-dependent formula.
    fixture._isTubular = false;
    fixture._zoomState.geometryZoom = 8;
    fixture._zoomState.surfaceGeometryZoom = 8;
    const double reference = fixture.thickness();
    fixture._zoomState.surfaceGeometryZoom = 16;
    const double ratio = fixture.thickness() / reference;
    assert(ratio < .006); // A nominal 10-pixel airborne tube can collapse below 0.06 pixel.
    std::cout << "PASS: " << checked << " native tube widths independent of DEM detail; old ratio="
        << ratio << '\n';
}
