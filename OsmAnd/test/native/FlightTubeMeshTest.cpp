// Match qobjectdefs.h in the production VectorLine_P.cpp translation unit. Without
// this keyword, the standalone test used to pass while the APK emitted zero vertices.
#define emit
#include "../../patches/osmand-core/FlightTubeMesh.h"
#undef emit
#include <cassert>
#include <iostream>
#include <limits>
#include <map>
#include <set>
#include <tuple>

using namespace FlightTubeMesh;
using Position = std::tuple<double, double, double>;

static bool near(double a, double b) { return std::abs(a - b) < 1e-7; }
static Position position(const Vertex& v) { return {v.x, v.height, v.z}; }

static std::vector<Vertex> mesh(const std::vector<Sample>& points)
{
    std::vector<Vertex> result;
    append(points, [&](const Vertex& v) { result.push_back(v); });
    for (const auto& v : result)
    {
        assert(std::isfinite(v.x) && std::isfinite(v.z) && std::isfinite(v.height));
        assert(v.shade >= 0.7f && v.shade <= 1.0f);
    }
    return result;
}

static void closed(const std::vector<Vertex>& vertices)
{
    // Every edge of the capped volume must belong to exactly two triangles.
    std::map<std::pair<Position, Position>, int> edges;
    assert(vertices.size() % 3 == 0);
    for (std::size_t i = 0; i < vertices.size(); i += 3)
    {
        for (unsigned j = 0; j < 3; ++j)
        {
            auto a = position(vertices[i + j]);
            auto b = position(vertices[i + (j + 1) % 3]);
            assert(a != b);
            if (b < a) std::swap(a, b);
            ++edges[{a, b}];
        }
    }
    for (const auto& edge : edges) assert(edge.second == 2);
}

static void preservedCentres(const std::vector<Sample>& points, const std::vector<Vertex>& vertices)
{
    for (const auto& p : points)
    {
        std::set<Position> ring;
        for (const auto& v : vertices)
        {
            if (v.distance == p.distance && position(v) != Position(p.x, p.height, p.z))
                ring.insert(position(v));
        }
        assert(ring.size() == Sides);
        Vec3 centre = {0, 0, 0};
        for (const auto& v : ring)
        {
            const Vec3 radial = {(std::get<0>(v) - p.x) * p.metersPerUnit,
                std::get<1>(v) - p.height, (std::get<2>(v) - p.z) * p.metersPerUnit};
            assert(near(std::sqrt(dot(radial, radial)), p.radius * p.metersPerUnit));
            centre = centre + Vec3{std::get<0>(v), std::get<1>(v), std::get<2>(v)};
        }
        centre = centre * (1.0 / Sides);
        assert(near(centre.x, p.x) && near(centre.y, p.height) && near(centre.z, p.z));
    }
}

static void grazingEndCapKeepsRoundPhysicalDiameter(
    const Sample& sample, const std::vector<Vertex>& vertices)
{
    // Looking along a level flight path projects the end cap into its vertical/horizontal plane.
    // Its equal axes distinguish a real cylinder from the old flat elevated ribbon.
    double minZ = 1e9, maxZ = -1e9, minH = 1e9, maxH = -1e9;
    for (const auto& v : vertices)
    {
        if (!near(v.x, sample.x))
            continue;
        minZ = std::min(minZ, v.z); maxZ = std::max(maxZ, v.z);
        minH = std::min(minH, v.height); maxH = std::max(maxH, v.height);
    }
    assert(minZ < maxZ && minH < maxH);
    assert(near((maxZ - minZ) * sample.metersPerUnit, maxH - minH));
    assert(near(maxH - minH, 2.0 * sample.radius * sample.metersPerUnit));
}

int main()
{
    assert(gridCellsPerTile(true, 2) == 1);
    assert(gridCellsPerTile(false, 2) == 4);
    assert(gridCellsPerTile(false, 5) == 2);
    assert(gridCellsPerTile(false, 14) == 1);
    // Identical physical diameter from above, from the side and at a grazing angle, at 12 km altitude.
    const std::vector<Sample> level = {{0, 0, 12000, 0.02, 100, 0}, {100000, 0, 12000, 0.02, 100, 1}};
    const auto levelMesh = mesh(level);
    // 12 sides, two triangles per side, and two 12-triangle caps: 48 triangles.
    assert(levelMesh.size() == 144);
    double minZ = 1e9, maxZ = -1e9, minH = 1e9, maxH = -1e9;
    for (const auto& v : levelMesh)
    {
        minZ = std::min(minZ, v.z); maxZ = std::max(maxZ, v.z);
        minH = std::min(minH, v.height); maxH = std::max(maxH, v.height);
    }
    assert(near((maxZ - minZ) * 0.02, maxH - minH));
    assert(near(maxH - minH, 4));
    grazingEndCapKeepsRoundPhysicalDiameter(level.front(), levelMesh);
    preservedCentres(level, levelMesh);
    closed(levelMesh);

    // Climb, descent and bend, with latitude-dependent map-unit scale.
    const std::vector<Sample> bent = {{-50000, 10000, 500, 0.02, 80, 0},
        {0, 0, 12000, 0.018, 95, 1}, {60000, 90000, 5000, 0.01, 160, 2}};
    const auto bentMesh = mesh(bent);
    preservedCentres(bent, bentMesh);
    closed(bentMesh);

    // Vertical sections, stationary points, reversals and empty/missing data must stay finite.
    const std::vector<Sample> vertical = {{0, 0, 100, 0.02, 100, 0}, {0, 0, 200, 0.02, 100, 1}};
    const auto verticalMesh = mesh(vertical);
    preservedCentres(vertical, verticalMesh);
    closed(verticalMesh);
    auto duplicate = level;
    duplicate.insert(duplicate.begin(), level.front());
    assert(mesh(duplicate).size() == levelMesh.size());
    assert(mesh({}).empty());
    assert(mesh({level.front()}).empty());
    assert(mesh({level.front(), level.front()}).empty());
    auto invalid = level;
    invalid[1].height = std::numeric_limits<double>::quiet_NaN();
    assert(mesh(invalid).empty());
    auto reverse = level;
    reverse.push_back(level.front());
    assert(!mesh(reverse).empty());

    // The geometry cost is linear and bounded for a full 4,000-point rendered leg.
    std::vector<Sample> longLeg;
    for (int i = 0; i < 4000; ++i)
        longLeg.push_back({i * 1000.0, std::sin(i * 0.01) * 1000, 12000, 0.02, 100, float(i)});
    const auto longMesh = mesh(longLeg);
    assert(longMesh.size() == 6 * Sides * longLeg.size());
    std::cout << "PASS: Qt keyword compatibility, closed volume, equal top/side diameter, unchanged centres and heights, "
        "joined bends, vertical segments, duplicates, reversals, invalid inputs, bounded 4000-point mesh\n";
}
