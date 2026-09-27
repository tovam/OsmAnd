#pragma once

#include <cstdint>

// A regional map and a routing/contour overlay are not interchangeable. Keep the
// world map underneath overlays when the regional cartography is unavailable.
// Source/style zoom can be clamped, but that alone does not reuse a rendered
// parent tile. The raster fallback below magnifies the geographically correct
// part of that tile, keeping requested coordinates and projection unchanged.
namespace BasemapOverzoom
{
    inline bool needsFallback(int requestedZoom, int lastBasemapZoom, bool hasRegionalMap)
    {
        return requestedZoom > lastBasemapZoom && !hasRegionalMap;
    }

    inline int sourceZoom(bool isBasemap, int requestedZoom, int lastBasemapZoom)
    {
        return isBasemap && requestedZoom > lastBasemapZoom ? lastBasemapZoom : requestedZoom;
    }

    // Nearest-neighbour sampling at pixel centres, entirely in integer space.
    // A floating-point source rectangle can collapse to zero width at deep zoom.
    // Inputs: valid XYZ tile coordinate, zoom delta 0..31, pixel 0..size-1.
    inline unsigned parentPixel(unsigned tileCoordinate, unsigned zoomDelta,
        unsigned pixel, unsigned size)
    {
        const auto children = std::uint64_t(1) << zoomDelta;
        const auto child = std::uint64_t(tileCoordinate) % children;
        return static_cast<unsigned>((child * size * 2 + pixel * 2 + 1) / (children * 2));
    }

    template <typename ReadPixel, typename WritePixel, typename IsAborted>
    bool magnifyParent(unsigned tileX, unsigned tileY, unsigned zoomDelta,
        unsigned size, ReadPixel readPixel, WritePixel writePixel, IsAborted isAborted)
    {
        for (unsigned y = 0; y < size; ++y)
        {
            if (isAborted())
                return false;
            const auto sourceY = parentPixel(tileY, zoomDelta, y, size);
            for (unsigned x = 0; x < size; ++x)
                writePixel(x, y, readPixel(parentPixel(tileX, zoomDelta, x, size), sourceY));
        }
        return true;
    }
}
