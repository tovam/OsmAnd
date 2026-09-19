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
    float _surfaceZoomLevel = 8, _surfaceVisualZoom = 1, _mapVisualZoomShift = 0;
    double _lineWidth = 9.6;
    float mapZoom = 8;
    float zoom() const { return mapZoom; }
    double thickness() const {
#include "FlightCoreWidthExtract.h"
    }
};

int main() {
    WidthFixture fixture;
    int checked = 0;
    for (float map : {3.f, 8.f, 14.f, 18.f}) {
        fixture.mapZoom = map;
        fixture._hasElevationDataProvider = false;
        const double expected = fixture.thickness();
        assert(std::isfinite(expected) && expected > 0);
        fixture._hasElevationDataProvider = true;
        for (float surface : {-1.f, 3.f, 8.f, 14.f, 18.f, std::numeric_limits<float>::quiet_NaN()}) {
            fixture._surfaceZoomLevel = surface;
            for (float visual : {.75f, 1.f, 1.5f}) {
                fixture._surfaceVisualZoom = visual;
                assert(std::abs(fixture.thickness() / expected - 1.0) < 1e-6);
                checked++;
            }
        }
    }
    // Upstream ribbons intentionally keep their old surface-dependent formula.
    fixture._isTubular = false;
    fixture.mapZoom = 8;
    fixture._surfaceVisualZoom = 1;
    fixture._surfaceZoomLevel = 8;
    const double reference = fixture.thickness();
    fixture._surfaceZoomLevel = 16;
    const double ratio = fixture.thickness() / reference;
    assert(ratio < .006); // A nominal 10-pixel airborne tube can collapse below 0.06 pixel.
    std::cout << "PASS: " << checked << " native tube widths independent of DEM detail; old ratio="
        << ratio << '\n';
}
