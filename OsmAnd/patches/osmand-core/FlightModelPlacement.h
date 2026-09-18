#pragma once

namespace FlightModelPlacement
{
    // ObjParser grounds models at minY = 0. An airborne marker's absolute
    // altitude denotes its centre instead, independent of its on-screen scale.
    // Ordinary terrain-following models must keep their grounded origin.
    inline float verticalOffset(bool hasAbsoluteElevation, float minY, float maxY)
    {
        return hasAbsoluteElevation ? -(minY + (maxY - minY) * 0.5f) : 0.0f;
    }
}
