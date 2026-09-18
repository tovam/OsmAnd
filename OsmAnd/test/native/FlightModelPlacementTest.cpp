#include "../../patches/osmand-core/FlightModelPlacement.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <sstream>
#include <string>

static void checkBounds(float minY, float maxY)
{
    const float offset = FlightModelPlacement::verticalOffset(true, minY, maxY);
    assert(FlightModelPlacement::verticalOffset(false, minY, maxY) == 0.0f);
    for (double scale : {0.001, 0.1, 1.0, 10.0, 1000.0})
    {
        const double altitude = 12000.0;
        const double bottom = altitude + (minY + offset) * scale;
        const double top = altitude + (maxY + offset) * scale;
        const double tolerance = 0.0001 * std::max(1.0, scale);
        assert(std::abs((top + bottom) * 0.5 - altitude) < tolerance);
        assert(std::abs((top - bottom) - (maxY - minY) * scale) < tolerance);
        assert(bottom < altitude && top > altitude);
    }
}

int main(int argc, char** argv)
{
    assert(argc == 3); // Patched native renderer and bundled OBJ, never app data.
    std::ifstream renderer(argv[1]);
    assert(renderer.good());
    const std::string source((std::istreambuf_iterator<char>(renderer)), {});
    assert(source.find("FlightModelPlacement::verticalOffset(") != std::string::npos);
    assert(source.find("!qIsNaN(model3DMapSymbol->elevation), originalBBox.minY, originalBBox.maxY") != std::string::npos);
    // Recentring happens in model space, before scale/heading and the unchanged
    // absolute elevation. Both frustum checks and GPU rendering use this matrix.
    assert(source.find("mDirectionInWorld * mRotateOnRelief * mScale * mVerticalAnchor") != std::string::npos);
    assert(source.find("glm::vec3(0.0f, verticalOffset, 0.0f)") != std::string::npos);
    assert(source.find("renderable->mModel = mLocalModel;") != std::string::npos);

    std::ifstream model(argv[2]);
    assert(model.good());
    float minY = std::numeric_limits<float>::max();
    float maxY = std::numeric_limits<float>::lowest();
    std::string line;
    while (std::getline(model, line))
    {
        std::istringstream fields(line);
        std::string kind;
        float x, y, z;
        if (fields >> kind && kind == "v")
        {
            assert(fields >> x >> y >> z);
            minY = std::min(minY, y);
            maxY = std::max(maxY, y);
        }
    }
    assert(maxY > minY);
    checkBounds(0.0f, maxY - minY); // Actual ObjParser ground-normalized aircraft.
    checkBounds(minY, maxY); // Also safe for a model loaded without normalization.
    checkBounds(-4.0f, -1.0f);
    checkBounds(-1.0f, 3.0f);
    std::cout << "PASS: aircraft centred vertically at every scale; model height, "
                 "GPS altitude, horizontal origin and grounded model policy preserved\n";
}
