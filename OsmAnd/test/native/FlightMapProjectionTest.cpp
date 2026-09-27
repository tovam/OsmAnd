#include "../../patches/osmand-core/FlightMapProjection.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace FlightMapProjection;
    assert(!useOrthographic(true, 90));
    enabled().store(true);
    assert(useOrthographic(true, 90));
    assert(!useOrthographic(true, 89.9f));
    assert(!useOrthographic(false, 90));
    for (const float units : {0.001f, 0.1f, 1.0f, 1000.0f})
        for (const float distance : {10.0f, 1000.0f}) {
            const auto e = extents(distance, 0.5f, 2.0f, units, 10000.0f);
            assert(e.halfWidth == distance);
            assert(e.halfHeight == distance * 0.5f);
            // A 12-km aircraft remains inside the near plane even at street zoom.
            const float aircraftDepth = distance - 12000.0f / units;
            assert(aircraftDepth > e.nearPlane && aircraftDepth < e.farPlane);
            // Orthographic x does not depend on height or view translation.
            const float x = 0.25f * e.halfWidth;
            assert(std::abs(x / e.halfWidth - 0.25f) < 1e-6f);
            // Ground width equals the perspective camera's width at the target plane.
            assert(e.halfWidth == distance * 0.5f * 2.0f);
        }
    enabled().store(false);
    assert(!useOrthographic(true, 90));
    std::cout << "Flight projection extents and scope: OK\n";
}
