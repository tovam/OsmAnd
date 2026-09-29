// Platform adapters only; production methods and class layout are included below.
#include "../../patches/osmand-core/BasemapOverzoom.h"
#include <cassert>
#include <cstdint>
#include <functional>
#include <iostream>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

#define Q_DISABLE_COPY_AND_MOVE(name)
using QMutex = std::mutex;
struct QMutexLocker { std::lock_guard<QMutex> lock; explicit QMutexLocker(QMutex* m) : lock(*m) {} };
template<class T> using sk_sp = std::shared_ptr<T>;
constexpr int kN32_SkColorType = 1;
struct SkImageInfo { unsigned size; static SkImageInfo MakeN32Premul(unsigned w, unsigned h) { assert(w == h); return {w}; } };
struct SkPixmap {
    unsigned size = 0;
    const std::vector<std::uint32_t>* pixels = nullptr;
    int colorType() const { return kN32_SkColorType; }
    int width() const { return static_cast<int>(size); }
    int height() const { return static_cast<int>(size); }
    const std::uint32_t* addr32(unsigned x, unsigned y) const { return &pixels->at(y * size + x); }
};
struct SkImage {
    unsigned size;
    std::vector<std::uint32_t> pixels;
    bool peekPixels(SkPixmap* out) const { out->size = size; out->pixels = &pixels; return true; }
};
struct SkBitmap {
    std::shared_ptr<SkImage> image;
    bool tryAllocPixels(SkImageInfo info) { image = std::make_shared<SkImage>(); image->size = info.size; image->pixels.resize(info.size * info.size); return true; }
    std::uint32_t* getAddr32(unsigned x, unsigned y) { return &image->pixels.at(y * image->size + x); }
    void setImmutable() {}
    sk_sp<SkImage> asImage() { return image; }
};
namespace OsmAnd {
using ZoomLevel = int;
enum { MinZoomLevel = 0 };
struct TileId {
    int x = 0, y = 0;
    static TileId fromXY(int x, int y) { TileId id; id.x = x; id.y = y; return id; }
};
struct AreaI {
    std::int64_t left, top, right, bottom;
    bool intersects(const AreaI& other) const {
        return left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top;
    }
    bool operator==(const AreaI& other) const {
        return std::tie(left, top, right, bottom) == std::tie(other.left, other.top, other.right, other.bottom);
    }
};
struct Utilities {
    static AreaI tileBoundingBox31(TileId id, ZoomLevel z) {
        const auto size = std::int64_t(1) << (31 - z);
        return {id.x * size, id.y * size, (std::int64_t(id.x) + 1) * size - 1, (std::int64_t(id.y) + 1) * size - 1};
    }
};
struct QueryController { bool aborted = false; bool isAborted() const { return aborted; } };
struct IMapDataProvider {
    struct RetainableCacheMetadata { virtual ~RetainableCacheMetadata() = default; };
    struct Request {
        TileId tileId;
        ZoomLevel zoom = 0, detailedZoom = 0;
        AreaI visibleArea31;
        std::int64_t areaTime = 0;
        std::shared_ptr<QueryController> queryController = std::make_shared<QueryController>();
        static void copy(Request& dst, const Request& src) { dst = src; }
    };
    struct Data { virtual ~Data() = default; };
};
struct Metric {
    float elapsedTime = 0;
    template<class T> std::shared_ptr<T> findOrAddSubmetricOfType() { return std::make_shared<T>(); }
};
struct MapRasterLayerProvider_Metrics { struct Metric_obtainData : Metric {}; };
struct MapPrimitivesProvider_Metrics { struct Metric_obtainData : Metric {}; };
struct Stopwatch { explicit Stopwatch(bool) {} float elapsed() const { return 0; } };
struct MapObject {
    bool isCoastline = false;
    AreaI bbox31{0, 0, 2147483647, 2147483647};
    bool intersectedOrContainedBy(const AreaI& area, const AreaI&, std::int64_t) const { return bbox31.intersects(area); }
    virtual ~MapObject() = default;
};
struct Section { bool isBasemap = false, isContourLines = false; };
struct BinaryMapObject : MapObject { std::shared_ptr<Section> section = std::make_shared<Section>(); };
struct RawObjects { std::vector<std::shared_ptr<MapObject>> mapObjects; };
struct Primitive { std::shared_ptr<MapObject> sourceObject; };
struct Primitives {
    bool empty = false;
    std::vector<std::shared_ptr<Primitive>> polygons, polylines, points;
    bool isEmpty() const { return empty; }
};
struct MapPrimitiviser {
    enum { LastZoomToUseBasemap = 11 };
    struct SurfaceMapObject : MapObject {};
};
struct MapPrimitivesProvider {
    struct Data {
        std::shared_ptr<RawObjects> mapObjectsData = std::make_shared<RawObjects>();
        std::shared_ptr<Primitives> primitivisedObjects = std::make_shared<Primitives>();
        std::shared_ptr<IMapDataProvider::RetainableCacheMetadata> retainableCacheMetadata;
    };
    std::shared_ptr<Data> fine = std::make_shared<Data>();
    std::map<std::tuple<int, int, int>, std::shared_ptr<Data>> parents;
    std::set<int> missingZooms, surfaceOnlyZooms, symbolsOnlyZooms;
    int failedZoom = -1, cancelledZoom = -1;
    std::vector<int> requestedParents;
    int fineZoom = 14;
    bool parentAvailable = true;
    int parentRequests = 0;
    bool obtainTiledPrimitives(const IMapDataProvider::Request& req, std::shared_ptr<Data>& data, MapPrimitivesProvider_Metrics::Metric_obtainData*) {
        if (req.zoom < fineZoom) {
            ++parentRequests;
            requestedParents.push_back(req.zoom);
            if (req.zoom == failedZoom) return false;
            if (req.zoom == cancelledZoom) req.queryController->aborted = true;
            assert(req.detailedZoom == req.zoom);
            assert(req.visibleArea31 == Utilities::tileBoundingBox31(req.tileId, req.zoom));
            auto& parent = parents[{req.zoom, req.tileId.x, req.tileId.y}];
            if (!parent) {
                parent = std::make_shared<Data>();
                if (!symbolsOnlyZooms.count(req.zoom)) {
                    std::shared_ptr<MapObject> object = surfaceOnlyZooms.count(req.zoom)
                        ? std::static_pointer_cast<MapObject>(std::make_shared<MapPrimitiviser::SurfaceMapObject>())
                        : std::make_shared<MapObject>();
                    object->bbox31 = req.visibleArea31;
                    parent->primitivisedObjects->polygons.push_back(std::make_shared<Primitive>(Primitive{object}));
                }
            }
            data = parentAvailable && !missingZooms.count(req.zoom) ? parent : nullptr;
        } else data = fine;
        return true;
    }
};
enum class AlphaChannelPresence { NotPresent };
class MapRasterLayerProvider {
public:
    using Request = IMapDataProvider::Request;
    struct Data {
        TileId tileId;
        ZoomLevel zoom;
        sk_sp<SkImage> image;
        std::unique_ptr<const IMapDataProvider::RetainableCacheMetadata> metadata;
        Data(TileId id, ZoomLevel z, AlphaChannelPresence, float, sk_sp<SkImage> im,
            std::shared_ptr<MapPrimitivesProvider::Data>, const IMapDataProvider::RetainableCacheMetadata* md)
            : tileId(id), zoom(z), image(im), metadata(md) {}
    };
    std::shared_ptr<MapPrimitivesProvider> primitivesProvider = std::make_shared<MapPrimitivesProvider>();
    bool fillBackground = true;
    unsigned getTileSize() const { return 64; }
    float getTileDensityFactor() const { return 1; }
};
template<class T> struct ImplementationInterface { T* ptr; ImplementationInterface(T* p) : ptr(p) {} T* operator->() { return ptr; } };
class MapRasterizer {};
bool isPerformanceMetricsEnabled() { return false; }
struct Performance { void rasterStart(TileId) {} void rasterFinish(TileId, ZoomLevel) {} };
Performance& getPerformanceMetrics() { static Performance perf; return perf; }
}
#include "BasemapRasterHeader.h"
#include "BasemapRasterMethods.h"
namespace OsmAnd {
MapRasterLayerProvider_P::MapRasterLayerProvider_P(MapRasterLayerProvider* p) : owner(p) {}
MapRasterLayerProvider_P::~MapRasterLayerProvider_P() = default;
void MapRasterLayerProvider_P::initialize() {}
MapRasterLayerProvider_P::RetainableCacheMetadata::RetainableCacheMetadata(
    const std::shared_ptr<const IMapDataProvider::RetainableCacheMetadata>& p) : binaryMapPrimitivesRetainableCacheMetadata(p) {}
MapRasterLayerProvider_P::RetainableCacheMetadata::~RetainableCacheMetadata() = default;
}
struct Renderer : OsmAnd::MapRasterLayerProvider_P {
    int coarseRenders = 0, fineRenders = 0;
    std::vector<int> renderedZooms;
    explicit Renderer(OsmAnd::MapRasterLayerProvider* p) : MapRasterLayerProvider_P(p) {}
    sk_sp<SkImage> rasterize(const OsmAnd::MapRasterLayerProvider::Request& req,
        const std::shared_ptr<const OsmAnd::MapPrimitivesProvider::Data>& data,
        OsmAnd::MapRasterLayerProvider_Metrics::Metric_obtainData*) override {
        SkBitmap bitmap;
        bitmap.tryAllocPixels(SkImageInfo::MakeN32Premul(64, 64));
        const bool coarse = req.zoom < owner->primitivesProvider->fineZoom;
        if (coarse) ++coarseRenders; else ++fineRenders;
        renderedZooms.push_back(req.zoom);
        bool content = false;
        for (const auto& primitive : data->primitivisedObjects->polygons)
            content |= !std::dynamic_pointer_cast<OsmAnd::MapPrimitiviser::SurfaceMapObject>(primitive->sourceObject);
        for (unsigned y = 0; y < 64; ++y)
            for (unsigned x = 0; x < 64; ++x)
                *bitmap.getAddr32(x, y) = coarse && content ? 0xff000000u | (y << 8) | x : 0xffffffff;
        return bitmap.asImage();
    }
};

int main() {
    using namespace OsmAnd;
    MapRasterLayerProvider provider;
    Renderer renderer(&provider);
    MapRasterLayerProvider::Request request;
    request.zoom = request.detailedZoom = 14;
    request.tileId = TileId::fromXY(123 * 8 + 3, 456 * 8 + 6);
    std::shared_ptr<MapRasterLayerProvider::Data> output;
    provider.primitivesProvider->fine->primitivisedObjects->empty = true;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(output && output->zoom == 14 && output->tileId.x == request.tileId.x);
    assert(output->image->pixels[0] == (0xff000000u | (48 << 8) | 24));
    assert(output->image->pixels.back() == (0xff000000u | (55 << 8) | 31));
    assert(renderer.coarseRenders == 1 && renderer.fineRenders == 0);
    ++request.tileId.x;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(renderer.coarseRenders == 1); // Siblings reuse the same parent image.

    auto regional = std::make_shared<BinaryMapObject>();
    provider.primitivesProvider->fine->mapObjectsData->mapObjects.push_back(regional);
    provider.primitivesProvider->fine->primitivisedObjects->polygons.push_back(std::make_shared<Primitive>(Primitive{regional}));
    provider.primitivesProvider->fine->primitivisedObjects->empty = false;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(output->image->pixels[0] == 0xffffffff && renderer.fineRenders == 1);
    regional->section->isContourLines = true;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(output->image->pixels[0] != 0xffffffff); // Contours cannot hide the basemap.
    regional->section->isContourLines = false;
    regional->isCoastline = true;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(output->image->pixels[0] != 0xffffffff);
    provider.fillBackground = false;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(output->image->pixels[0] == 0xffffffff); // Never fill transparent overlays.
    provider.fillBackground = true;
    provider.primitivesProvider->fine.reset();
    assert(renderer.obtainRasterizedTile(request, output, nullptr) && output);
    MapRasterLayerProvider missingProvider;
    missingProvider.primitivesProvider->fine.reset();
    missingProvider.primitivesProvider->parentAvailable = false;
    Renderer missingRenderer(&missingProvider);
    assert(missingRenderer.obtainRasterizedTile(request, output, nullptr) && !output);
    request.queryController->aborted = true;
    assert(!renderer.obtainRasterizedTile(request, output, nullptr));
    request.queryController->aborted = false;
    const auto originalX = request.tileId.x;
    for (int i = 1; i <= 5; ++i) {
        request.tileId.x = originalX + i * 8;
        assert(renderer.obtainRasterizedTile(request, output, nullptr));
    }
    const auto before = renderer.coarseRenders;
    request.tileId.x = originalX;
    assert(renderer.obtainRasterizedTile(request, output, nullptr));
    assert(renderer.coarseRenders == before + 1); // Old parent was evicted, cache is bounded.
    std::cout << "PASS: production raster path: empty/missing fine tile, correct parent crop, "
                 "regional precedence, contours/coastlines, transparent layers, cancellation, bounded LRU\n";

    int failures = 0;
    const auto check = [](bool ok, const char* message) {
        if (!ok) throw std::runtime_error(message);
    };
    const auto regression = [&](const char* name, const std::function<void()>& run) {
        try { run(); std::cout << "PASS: " << name << '\n'; }
        catch (const std::exception& e) { ++failures; std::cerr << "FAIL: " << name << ": " << e.what() << '\n'; }
    };
    regression("neighboring regional data cannot block the coarse map", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        auto neighbor = std::make_shared<BinaryMapObject>();
        neighbor->bbox31 = Utilities::tileBoundingBox31(TileId::fromXY(0, 0), 14);
        p.primitivesProvider->fine->mapObjectsData->mapObjects.push_back(neighbor);
        p.primitivesProvider->fine->primitivisedObjects->polygons.push_back(std::make_shared<Primitive>(Primitive{neighbor}));
        check(r.obtainRasterizedTile(request, output, nullptr) && output && output->image->pixels[0] != 0xffffffff,
            "raw data read outside the requested tile incorrectly disabled fallback");
    });
    regression("filtered-out regional objects cannot block fallback", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        p.primitivesProvider->fine->mapObjectsData->mapObjects.push_back(std::make_shared<BinaryMapObject>());
        p.primitivesProvider->fine->primitivisedObjects->empty = true;
        check(r.obtainRasterizedTile(request, output, nullptr) && output,
            "an object without any rendered primitive incorrectly disabled fallback");
    });
    for (const std::string kind : {"missing", "surface only", "symbols only"}) {
        regression((kind + " z11 must fall back to the actual z7 map").c_str(), [&] {
            MapRasterLayerProvider p;
            Renderer r(&p);
            for (int z = 8; z <= 11; ++z) {
                if (kind == "missing") p.primitivesProvider->missingZooms.insert(z);
                else if (kind == "surface only") p.primitivesProvider->surfaceOnlyZooms.insert(z);
                else p.primitivesProvider->symbolsOnlyZooms.insert(z);
            }
            p.primitivesProvider->fine->primitivisedObjects->empty = true;
            check(r.obtainRasterizedTile(request, output, nullptr) && output,
                "stopped at empty z11 despite a map at z7");
            check(output->image->pixels[0] == (0xff000000u
                    | (BasemapOverzoom::parentPixel(request.tileId.y, 7, 0, 64) << 8)
                    | BasemapOverzoom::parentPixel(request.tileId.x, 7, 0, 64)),
                "returned white background instead of the correct ancestor pixels");
            check(r.renderedZooms == std::vector<int>{7}, "must render only the useful ancestor");
            const auto reads = p.primitivesProvider->parentRequests;
            check(r.obtainRasterizedTile(request, output, nullptr) && output, "cached fallback unavailable");
            check(p.primitivesProvider->parentRequests == reads, "re-read the same missing ancestors for a sibling");
        });
    }
    regression("coarse maps ending below z11 remain visible even at z9", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        auto coarseRequest = request;
        coarseRequest.zoom = coarseRequest.detailedZoom = 9;
        coarseRequest.tileId = TileId::fromXY(126, 148);
        p.primitivesProvider->fineZoom = 9;
        p.primitivesProvider->fine->primitivisedObjects->empty = true;
        p.primitivesProvider->missingZooms.insert(8);
        check(r.obtainRasterizedTile(coarseRequest, output, nullptr) && output, "fallback only worked above z11");
        check(r.renderedZooms == std::vector<int>{7}, "did not choose z7");
    });
    regression("pixel crop remains correct at every deep zoom including z31", [&] {
        for (int z = 12; z <= 31; ++z) {
            MapRasterLayerProvider p;
            Renderer r(&p);
            p.primitivesProvider->fineZoom = z;
            p.primitivesProvider->fine->primitivisedObjects->empty = true;
            for (int coarse = 8; coarse <= 11; ++coarse) p.primitivesProvider->surfaceOnlyZooms.insert(coarse);
            const auto side = std::int64_t(1) << z;
            for (const auto x : {std::int64_t(0), side / 2, side - 1}) {
                auto deep = request;
                deep.zoom = deep.detailedZoom = z;
                deep.tileId = TileId::fromXY(static_cast<int>(x), static_cast<int>(side - 1 - x));
                check(r.obtainRasterizedTile(deep, output, nullptr) && output, "no parent for deep zoom");
                // Independent reference arithmetic, not the production sampler.
                const auto children = std::int64_t(1) << (z - 7);
                for (unsigned py : {0u, 63u}) for (unsigned px : {0u, 63u}) {
                    const auto sx = static_cast<unsigned>(((x % children) + (px + 0.5L) / 64) * 64 / children);
                    const auto sy = static_cast<unsigned>(((deep.tileId.y % children) + (py + 0.5L) / 64) * 64 / children);
                    check(output->image->pixels[py * 64 + px] == (0xff000000u | (sy << 8) | sx), "wrong deep-zoom crop");
                }
            }
        }
    });
    regression("valid coarse tiles are not downgraded", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        auto coarse = request;
        coarse.zoom = coarse.detailedZoom = 9;
        coarse.tileId = TileId::fromXY(126, 148);
        p.primitivesProvider->fineZoom = 9;
        auto world = std::make_shared<BinaryMapObject>();
        world->section->isBasemap = true;
        p.primitivesProvider->fine->primitivisedObjects->polygons.push_back(std::make_shared<Primitive>(Primitive{world}));
        check(r.obtainRasterizedTile(coarse, output, nullptr) && output, "valid map missing");
        check(p.primitivesProvider->parentRequests == 0 && r.fineRenders == 1, "valid coarse tile was downgraded");
    });
    regression("world-level z0 is usable but never requests a negative zoom", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        p.primitivesProvider->fine->primitivisedObjects->empty = true;
        for (int z = 1; z <= 11; ++z) p.primitivesProvider->missingZooms.insert(z);
        check(r.obtainRasterizedTile(request, output, nullptr) && output, "did not reach z0");
        check(r.renderedZooms == std::vector<int>{0}, "unexpected world source zoom");
        MapRasterLayerProvider worldProvider;
        Renderer worldRenderer(&worldProvider);
        auto world = request;
        world.zoom = world.detailedZoom = 0;
        world.tileId = TileId::fromXY(0, 0);
        worldProvider.primitivesProvider->fineZoom = 0;
        worldProvider.primitivesProvider->fine->primitivisedObjects->empty = true;
        check(worldRenderer.obtainRasterizedTile(world, output, nullptr) && !output, "invalid z0 result");
        check(worldProvider.primitivesProvider->parentRequests == 0, "requested negative ancestor");
    });
    regression("entirely missing map is cached without retaining a blank image", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        p.primitivesProvider->fine->primitivisedObjects->empty = true;
        p.primitivesProvider->parentAvailable = false;
        check(r.obtainRasterizedTile(request, output, nullptr) && !output, "invented a missing map");
        const auto reads = p.primitivesProvider->parentRequests;
        check(reads == 12, "search must be bounded to z11..z0");
        check(r.obtainRasterizedTile(request, output, nullptr) && !output, "incorrect empty cache entry");
        check(p.primitivesProvider->parentRequests == reads && r.coarseRenders == 0, "repeated empty scan");
    });
    regression("failed or cancelled ancestor lookups remain retryable", [&] {
        for (const bool cancel : {false, true}) {
            MapRasterLayerProvider p;
            Renderer r(&p);
            auto attempt = request;
            attempt.queryController = std::make_shared<QueryController>();
            p.primitivesProvider->fine->primitivisedObjects->empty = true;
            if (cancel) p.primitivesProvider->cancelledZoom = 11;
            else p.primitivesProvider->failedZoom = 11;
            check(!r.obtainRasterizedTile(attempt, output, nullptr), "published a blank success after parent failure");
            p.primitivesProvider->cancelledZoom = p.primitivesProvider->failedZoom = -1;
            attempt.queryController->aborted = false;
            check(r.obtainRasterizedTile(attempt, output, nullptr) && output, "failure permanently cached as missing map");
            check(p.primitivesProvider->parentRequests == 2, "did not retry failed parent");
        }
    });
    regression("a newly downloaded regional map takes precedence over a cached world tile", [&] {
        MapRasterLayerProvider p;
        Renderer r(&p);
        check(r.obtainRasterizedTile(request, output, nullptr) && output, "no coarse fallback");
        auto country = std::make_shared<BinaryMapObject>();
        p.primitivesProvider->fine->primitivisedObjects->polygons.push_back(std::make_shared<Primitive>(Primitive{country}));
        check(r.obtainRasterizedTile(request, output, nullptr) && output, "country missing");
        check(r.fineRenders == 1, "cached world tile masked the country");
    });
    return failures ? 1 : 0;
}
