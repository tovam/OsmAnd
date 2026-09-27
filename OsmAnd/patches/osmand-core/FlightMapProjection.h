#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>

namespace FlightMapProjection {
inline std::atomic<bool>& enabled() {
    static std::atomic<bool> value{false};
    return value;
}
inline bool useOrthographic(bool flatEarth, float elevation) {
    return enabled().load(std::memory_order_relaxed) && flatEarth && elevation >= 89.999f;
}
struct Extents { float halfWidth, halfHeight, nearPlane, farPlane; };
inline Extents extents(float distance, float tangent, float aspect, float metersPerUnit, float farPlane) {
    const float units = std::max(0.000001f, metersPerUnit);
    return {distance * tangent * aspect, distance * tangent,
        -std::max(1000.0f, 50000.0f / units),
        std::max(farPlane, distance + 20000.0f / units)};
}
}
