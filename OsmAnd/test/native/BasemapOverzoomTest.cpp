#include "../../patches/osmand-core/BasemapOverzoom.h"

#include <cassert>
#include <iostream>

int main()
{
    const int lastBasemapZoom = 11;
    for (int zoom = 0; zoom <= 31; ++zoom)
    {
        // Routing-only, contour-only and empty regions have no regional map.
        // Their world-map fallback is enabled above its normal display range.
        assert(BasemapOverzoom::needsFallback(zoom, lastBasemapZoom, false) == (zoom > 11));
        assert(!BasemapOverzoom::needsFallback(zoom, lastBasemapZoom, true));
        assert(BasemapOverzoom::sourceZoom(true, zoom, lastBasemapZoom) == (zoom > 11 ? 11 : zoom));
        assert(BasemapOverzoom::sourceZoom(false, zoom, lastBasemapZoom) == zoom);
    }

    // Read/style limits need not match. A world file may end earlier than the
    // style's last zoom; never ask the file for a level it cannot supply.
    for (int lastDataZoom = 0; lastDataZoom <= 11; ++lastDataZoom)
        assert(BasemapOverzoom::sourceZoom(true, 18, lastDataZoom) == lastDataZoom);

    // Alternate coarse and detailed objects in one tile: the world-map label
    // uses its coarse rule, but routing/detail overlays keep their own zoom.
    const bool basemapObjects[] = {true, false, true, false, false, true};
    const int expectedStyleZooms[] = {11, 18, 11, 18, 18, 11};
    for (unsigned i = 0; i < sizeof(basemapObjects) / sizeof(basemapObjects[0]); ++i)
        assert(BasemapOverzoom::sourceZoom(basemapObjects[i], 18, 11) == expectedStyleZooms[i]);

    std::cout << "PASS: world fallback at high zoom, detailed map precedence, "
                 "independent read/style limits and mixed coarse/detailed label zooms\n";
}
