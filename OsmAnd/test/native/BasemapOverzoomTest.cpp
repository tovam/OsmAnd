#include "../../patches/osmand-core/BasemapOverzoom.h"

#include <cassert>
#include <iostream>
#include <vector>

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

    // A synthetic world tile with a unique colour per pixel. Check actual output
    // pixels, not just the decision to use a fallback. This is the same sampler
    // used by MapRasterLayerProvider_P with a SkPixmap source and SkBitmap target.
    const unsigned size = 64;
    std::vector<std::uint32_t> pixels(size * size, 0);
    for (unsigned delta = 0; delta <= 31; ++delta)
    {
        const std::uint64_t children = std::uint64_t(1) << delta;
        for (auto child : {std::uint64_t(0), children / 2, children - 1})
        {
            const unsigned tx = static_cast<unsigned>(child);
            const unsigned ty = static_cast<unsigned>(children - 1 - child);
            assert(BasemapOverzoom::magnifyParent(tx, ty, delta, size,
                [](unsigned x, unsigned y) { return 0xff000000u | (y << 8) | x; },
                [&](unsigned x, unsigned y, std::uint32_t color) { pixels[y * size + x] = color; },
                [] { return false; }));
            for (unsigned y = 0; y < size; ++y)
            {
                for (unsigned x = 0; x < size; ++x)
                {
                    // Independently compute the expected source coordinate in long double.
                    const auto sx = static_cast<unsigned>((tx + (x + 0.5L) / size) * size / children);
                    const auto sy = static_cast<unsigned>((ty + (y + 0.5L) / size) * size / children);
                    assert(sx < size && sy < size);
                    assert(pixels[y * size + x] == (0xff000000u | (sy << 8) | sx));
                }
            }
        }
    }
    unsigned writes = 0;
    assert(!BasemapOverzoom::magnifyParent(0, 0, 12, size,
        [](unsigned, unsigned) { return 0u; },
        [&](unsigned, unsigned, std::uint32_t) { ++writes; },
        [&] { return writes >= size; }));
    assert(writes == size); // Cancellation is checked at every row.
    // Global tile coordinates, rather than just child offsets, select the same crop.
    assert(BasemapOverzoom::parentPixel(123 * 8 + 3, 3, 0, 256) == 96);
    assert(BasemapOverzoom::parentPixel(123 * 8 + 3, 3, 255, 256) == 127);

    std::cout << "PASS: fallback policy, detailed precedence, mixed style zooms, "
                 "synthetic raster magnification at zoom deltas 0..31, cancellation\n";
}
