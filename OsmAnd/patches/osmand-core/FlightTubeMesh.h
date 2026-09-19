#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <vector>

// Independent of Qt/JNI so the geometry can be tested without an Android build.
// Horizontal coordinates use the existing vector line's local 31-bit map frame;
// height is absolute metres, exactly as in the native elevated-line shader.
namespace FlightTubeMesh
{
    constexpr unsigned Sides = 12;
    constexpr double Pi = 3.14159265358979323846;

    // Absolute GPS heights do not follow the DEM. Cutting every tube face against every
    // terrain heixel can exceed the native 4M-vertex limit and hide the entire route.
    // Keep tile boundaries (and the existing coarse globe subdivision), not the DEM grid.
    inline int gridCellsPerTile(bool flatEarth, int mapZoom)
    {
        return flatEarth ? 1 : (mapZoom < 3 ? 4 : (mapZoom < 6 ? 2 : 1));
    }

    struct Vec3
    {
        double x, y, z;
        Vec3 operator+(const Vec3& b) const { return {x + b.x, y + b.y, z + b.z}; }
        Vec3 operator-(const Vec3& b) const { return {x - b.x, y - b.y, z - b.z}; }
        Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
    };

    inline double dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
    inline Vec3 cross(const Vec3& a, const Vec3& b)
    {
        return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
    }
    inline Vec3 unit(const Vec3& v, const Vec3& fallback)
    {
        const double length = std::sqrt(dot(v, v));
        return length > 1e-12 ? v * (1.0 / length) : fallback;
    }

    struct Sample
    {
        double x, z, height, metersPerUnit, radius;
        float distance;
    };

    struct Vertex
    {
        double x, z, height;
        float distance, shade;
    };

    inline Vec3 direction(const Sample& a, const Sample& b)
    {
        const double metres = (a.metersPerUnit + b.metersPerUnit) * 0.5;
        return {(b.x - a.x) * metres, b.height - a.height, (b.z - a.z) * metres};
    }

    inline Vertex vertex(const Sample& p, const Vec3& normal)
    {
        // Convert only the radial vertical offset to metres, never the centreline height.
        const double radiusMeters = p.radius * p.metersPerUnit;
        const Vec3 light = unit({-0.3, 0.85, -0.4}, {0, 1, 0});
        const float shade = static_cast<float>(0.70 + 0.30 * std::max(0.0, dot(normal, light)));
        return {p.x + normal.x * p.radius, p.z + normal.z * p.radius,
            p.height + normal.y * radiusMeters, p.distance, shade};
    }

    // Emit triangles directly into the renderer's vertex buffer. Shared cross-sections join
    // consecutive cylinders without cracks. Duplicate samples are skipped in linear time.
    // Do not name this callback `emit`: Qt defines that keyword as an empty macro in
    // VectorLine_P.cpp. It silently turns emit(vertex) into (vertex), emitting no mesh.
    template<class EmitVertex>
    void append(const std::vector<Sample>& input, EmitVertex emitVertex)
    {
        std::vector<Sample> points;
        points.reserve(input.size());
        for (const auto& p : input)
        {
            if (!std::isfinite(p.x) || !std::isfinite(p.z) || !std::isfinite(p.height)
                || !std::isfinite(p.metersPerUnit) || p.metersPerUnit <= 0
                || !std::isfinite(p.radius) || p.radius <= 0)
                return;
            if (points.empty() || dot(direction(points.back(), p), direction(points.back(), p)) > 1e-12)
                points.push_back(p);
        }
        if (points.size() < 2)
            return;

        std::array<Vertex, Sides> previousRing;
        Vec3 previousSide = {0, 0, 0};
        for (std::size_t i = 0; i < points.size(); ++i)
        {
            const Vec3 incoming = i > 0 ? unit(direction(points[i - 1], points[i]), {1, 0, 0})
                : unit(direction(points[0], points[1]), {1, 0, 0});
            const Vec3 outgoing = i + 1 < points.size()
                ? unit(direction(points[i], points[i + 1]), incoming) : incoming;
            const Vec3 tangent = unit(incoming + outgoing, outgoing);
            const Vec3 reference = std::abs(tangent.y) < 0.9 ? Vec3{0, 1, 0} : Vec3{1, 0, 0};
            const Vec3 side = unit(previousSide - tangent * dot(previousSide, tangent),
                unit(cross(tangent, reference), {0, 0, 1}));
            const Vec3 other = unit(cross(tangent, side), {0, 1, 0});
            previousSide = side;

            std::array<Vertex, Sides> ring;
            for (unsigned j = 0; j < Sides; ++j)
            {
                const double angle = 2.0 * Pi * j / Sides;
                ring[j] = vertex(points[i], side * std::cos(angle) + other * std::sin(angle));
            }
            if (i > 0)
            {
                for (unsigned j = 0; j < Sides; ++j)
                {
                    const unsigned next = (j + 1) % Sides;
                    emitVertex(previousRing[j]); emitVertex(previousRing[next]); emitVertex(ring[j]);
                    emitVertex(previousRing[next]); emitVertex(ring[next]); emitVertex(ring[j]);
                }
            }
            if (i == 0 || i + 1 == points.size())
            {
                const auto& p = points[i];
                const Vertex centre = {p.x, p.z, p.height, p.distance, 0.8f};
                for (unsigned j = 0; j < Sides; ++j)
                {
                    const unsigned next = (j + 1) % Sides;
                    emitVertex(centre);
                    emitVertex(ring[i == 0 ? next : j]);
                    emitVertex(ring[i == 0 ? j : next]);
                }
            }
            previousRing = ring;
        }
    }
}
