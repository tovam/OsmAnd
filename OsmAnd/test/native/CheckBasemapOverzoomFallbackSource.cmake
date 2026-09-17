cmake_minimum_required(VERSION 3.16)

# This checks the patched core call paths, not device or renderer output. The smart build supplies
# the same checked-out core sources to which the patch was just applied.
if(NOT DEFINED PRIMITIVISER_FILE OR NOT EXISTS "${PRIMITIVISER_FILE}")
    message(FATAL_ERROR "Pass -DPRIMITIVISER_FILE=<patched src/Map/MapPrimitiviser_P.cpp>")
endif()
if(NOT DEFINED PRIMITIVISER_PRIVATE_HEADER OR NOT EXISTS "${PRIMITIVISER_PRIVATE_HEADER}")
    message(FATAL_ERROR "Pass -DPRIMITIVISER_PRIVATE_HEADER=<patched src/Map/MapPrimitiviser_P.h>")
endif()

file(READ "${PRIMITIVISER_FILE}" source)
file(READ "${PRIMITIVISER_PRIVATE_HEADER}" private_header)

function(require_source pattern description)
    string(REGEX MATCH "${pattern}" match "${source}")
    if(NOT match)
        message(FATAL_ERROR "Missing basemap-overzoom source path: ${description}")
    endif()
endfunction()

string(REGEX MATCH "const bool useCoarseBasemapStyle" header_flag "${private_header}")
if(NOT header_flag)
    message(FATAL_ERROR "MapPrimitiviser private declaration does not receive explicit fallback style policy")
endif()
require_source("const bool useCoarseBasemapStyle" "explicit obtainPrimitives policy parameter")
require_source("const auto styleZoom = useCoarseBasemapStyle" "style zoom selected from policy")
require_source("polygonizedCoastlineObjects" "generated coastline/surface fallback is still processed")
require_source("useCoarseBasemapStyle= [*]/ detailedDataMissing" "fallback policy is based on missing detail")
require_source("useCoarseBasemapStyle [?] styleZoom : detailedZoom" "polyline evaluator preserves detailed style outside fallback")

string(REGEX MATCHALL "useCoarseBasemapStyle= [*]/ detailedDataMissing" fallback_calls "${source}")
list(LENGTH fallback_calls fallback_call_count)
if(NOT fallback_call_count EQUAL 3)
    message(FATAL_ERROR "Expected three coarse-fallback call paths (surface basemap, surface coastline, non-surface basemap), got ${fallback_call_count}")
endif()

if(source MATCHES "std::all_of\\(source")
    message(FATAL_ERROR "Fallback style policy must not infer provenance from a mixed generated surface source")
endif()

message(STATUS "Basemap overzoom fallback source paths: OK")
