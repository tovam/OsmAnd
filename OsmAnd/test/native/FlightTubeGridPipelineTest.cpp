// Compile the actual upstream grid cutter against its POD inputs, without Qt/Android.
// run_flight_tube_pipeline_test.py supplies the mechanically extracted implementation.
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <deque>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <unordered_map>
#include <vector>

#define OSMAND_CORE_API
#define Q_DECL_FINAL
namespace OsmAnd {
using ZoomLevel = int;
constexpr int MaxZoomLevel = 31;
struct FColorARGB { float a, r, g, b; };
struct PointD {
    double x, y;
    PointD(double x_, double y_) : x(x_), y(y_) {}
};
struct TileId {
    int32_t x, y;
    static TileId fromXY(int32_t x, int32_t y) { return {x, y}; }
    bool operator<(const TileId& b) const { return x < b.x || (x == b.x && y < b.y); }
};
struct Utilities {
    static double getPowZoom(int zoom) { return std::pow(2.0, zoom); }
};
struct VectorMapSymbol {
    struct Vertex { float positionXYZD[4]; FColorARGB color; };
    using Index = uint16_t;
    enum class PrimitiveType { TriangleFan, TriangleStrip, Triangles, LineLoop };
};
}
#include "FlightCoreGridExtract.h"
#include "FlightCoreGridExtract.cpp"
#include "../../patches/osmand-core/FlightTubeMesh.h"

static void checkGrid(int cells, double radiusInTiles, bool expectOverflow, int pointCount = 4000)
{
    // A dense leg at cruise altitude spanning four visible tiles. A wide map-unit radius
    // is possible when an elevated line uses surfaceZoom, below the map's tile zoom.
    constexpr int zoom = 14;
    const double tileSize = std::pow(2.0, 31 - zoom);
    constexpr double metresPerUnit = 0.014;
    constexpr double altitude = 12000;
    const double radius = tileSize * radiusInTiles;
    std::vector<FlightTubeMesh::Sample> points;
    for (int i = 0; i < pointCount; ++i) {
        double t = double(i) / (pointCount - 1);
        points.push_back({tileSize * 4 * t, tileSize * std::sin(t), altitude,
            metresPerUnit, radius, float(i)});
    }
    std::vector<OsmAnd::VectorMapSymbol::Vertex> input, output;
    FlightTubeMesh::append(points, [&](const FlightTubeMesh::Vertex& p) {
        input.push_back({{float(p.x), float(p.height), float(p.z), p.distance}, {1, 0, 0, 0}});
    });
    auto parts = std::make_shared<std::vector<std::pair<OsmAnd::TileId, int32_t>>>();
    bool overflow = false;
    bool generated = OsmAnd::GeometryModifiers::cutMeshWithGrid(input, nullptr,
        OsmAnd::VectorMapSymbol::PrimitiveType::Triangles, parts, zoom, {123.4, 123.4},
        cells, 0.5f, 0.01f, false, false, output, overflow);
    if (expectOverflow) {
        // VectorLine_P::generatePrimitive returns false here: the ENTIRE line is hidden.
        assert(!generated && overflow && output.empty());
        std::cout << "REPRODUCED: DEM grid rejects the whole flight tube\n";
        return;
    }
    assert(generated && !overflow && !parts->empty() && !output.empty());
    // These counts/parts go straight to GPUAPI::uploadSymbolAsMeshToGPU/glDrawArrays.
    std::size_t drawCount = 0;
    for (const auto& part : *parts) {
        assert(part.second > 0 && part.second % 3 == 0);
        drawCount += part.second;
    }
    assert(drawCount == output.size());
    assert(output.size() < 4 * 1024 * 1024);
    float minHeight = std::numeric_limits<float>::max(), maxHeight = -minHeight;
    float minDistance = minHeight, maxDistance = -minHeight;
    for (const auto& p : output) {
        for (float value : p.positionXYZD) assert(std::isfinite(value));
        assert(p.color.a == 1 && p.color.r == 0 && p.color.g == 0 && p.color.b == 0);
        minHeight = std::min(minHeight, p.positionXYZD[1]);
        maxHeight = std::max(maxHeight, p.positionXYZD[1]);
        minDistance = std::min(minDistance, p.positionXYZD[3]);
        maxDistance = std::max(maxDistance, p.positionXYZD[3]);
    }
    assert(std::abs((minHeight + maxHeight) / 2 - altitude) < 0.1);
    assert(maxHeight - minHeight > 1.9 * radius * metresPerUnit);
    assert(minDistance == 0 && maxDistance == pointCount - 1);
    std::cout << "PASS: cells=" << cells << " radius/tile=" << radiusInTiles
        << " points=" << pointCount << " drawVertices=" << drawCount << '\n';
}

int main()
{
    checkGrid(64, 0.5, true);
    for (bool flatEarth : {false, true}) {
        for (double radius : {0.01, 0.5, 2.0})
            checkGrid(FlightTubeMesh::gridCellsPerTile(flatEarth, 13), radius, false);
    }
    // Keep the low-zoom globe subdivision and support both sparse and dense tracks.
    checkGrid(FlightTubeMesh::gridCellsPerTile(false, 2), 0.01, false);
    checkGrid(FlightTubeMesh::gridCellsPerTile(false, 5), 0.01, false);
    checkGrid(FlightTubeMesh::gridCellsPerTile(true, 13), 0.01, false, 2);
    std::cout << "PASS: complete opaque, elevated tube survives native tile cutting\n";
}
