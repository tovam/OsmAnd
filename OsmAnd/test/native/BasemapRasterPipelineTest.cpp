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
struct TileId {
    int x = 0, y = 0;
    static TileId fromXY(int x, int y) { TileId id; id.x = x; id.y = y; return id; }
};
struct AreaI { TileId id; ZoomLevel zoom; };
struct Utilities { static AreaI tileBoundingBox31(TileId id, ZoomLevel z) { return {id, z}; } };
struct QueryController { bool aborted = false; bool isAborted() const { return aborted; } };
struct IMapDataProvider {
    struct RetainableCacheMetadata { virtual ~RetainableCacheMetadata() = default; };
    struct Request {
        TileId tileId;
        ZoomLevel zoom = 0, detailedZoom = 0;
        AreaI visibleArea31;
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
struct MapObject { bool isCoastline = false; virtual ~MapObject() = default; };
struct Section { bool isBasemap = false, isContourLines = false; };
struct BinaryMapObject : MapObject { std::shared_ptr<Section> section = std::make_shared<Section>(); };
struct RawObjects { std::vector<std::shared_ptr<MapObject>> mapObjects; };
struct Primitives { bool empty = false; bool isEmpty() const { return empty; } };
struct MapPrimitiviser { enum { LastZoomToUseBasemap = 11 }; };
struct MapPrimitivesProvider {
    struct Data {
        std::shared_ptr<RawObjects> mapObjectsData = std::make_shared<RawObjects>();
        std::shared_ptr<Primitives> primitivisedObjects = std::make_shared<Primitives>();
        std::shared_ptr<IMapDataProvider::RetainableCacheMetadata> retainableCacheMetadata;
    };
    std::shared_ptr<Data> fine = std::make_shared<Data>();
    std::map<std::pair<int, int>, std::shared_ptr<Data>> parents;
    bool parentAvailable = true;
    int parentRequests = 0;
    bool obtainTiledPrimitives(const IMapDataProvider::Request& req, std::shared_ptr<Data>& data, MapPrimitivesProvider_Metrics::Metric_obtainData*) {
        if (req.zoom == 11) {
            ++parentRequests;
            assert(req.detailedZoom == 11 && req.visibleArea31.zoom == 11);
            assert(req.visibleArea31.id.x == req.tileId.x && req.visibleArea31.id.y == req.tileId.y);
            auto& parent = parents[{req.tileId.x, req.tileId.y}];
            if (!parent) parent = std::make_shared<Data>();
            data = parentAvailable ? parent : nullptr;
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
    explicit Renderer(OsmAnd::MapRasterLayerProvider* p) : MapRasterLayerProvider_P(p) {}
    sk_sp<SkImage> rasterize(const OsmAnd::MapRasterLayerProvider::Request& req,
        const std::shared_ptr<const OsmAnd::MapPrimitivesProvider::Data>&,
        OsmAnd::MapRasterLayerProvider_Metrics::Metric_obtainData*) override {
        SkBitmap bitmap;
        bitmap.tryAllocPixels(SkImageInfo::MakeN32Premul(64, 64));
        if (req.zoom == 11) ++coarseRenders; else ++fineRenders;
        for (unsigned y = 0; y < 64; ++y)
            for (unsigned x = 0; x < 64; ++x)
                *bitmap.getAddr32(x, y) = req.zoom == 11 ? 0xff000000u | (y << 8) | x : 0xffffffff;
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
    provider.primitivesProvider->parentAvailable = false;
    assert(renderer.obtainRasterizedTile(request, output, nullptr) && !output);
    provider.primitivesProvider->parentAvailable = true;
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
}
