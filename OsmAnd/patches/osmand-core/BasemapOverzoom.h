#pragma once

// A regional map and a routing/contour overlay are not interchangeable. Keep the
// world map underneath overlays when the regional cartography is unavailable.
// Only source/style zoom is clamped: tile coordinates and projection keep the
// zoom requested by the user, so the coarse geography is enlarged in place.
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
}
