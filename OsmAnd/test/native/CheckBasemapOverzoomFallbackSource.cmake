cmake_minimum_required(VERSION 3.16)

# This checks the patched core call paths, not device or renderer output. The smart build supplies
# the same checked-out core sources to which the patch was just applied.
if(NOT DEFINED PRIMITIVISER_FILE OR NOT EXISTS "${PRIMITIVISER_FILE}")
    message(FATAL_ERROR "Pass -DPRIMITIVISER_FILE=<patched src/Map/MapPrimitiviser_P.cpp>")
endif()
if(NOT DEFINED PRIMITIVISER_PRIVATE_HEADER OR NOT EXISTS "${PRIMITIVISER_PRIVATE_HEADER}")
    message(FATAL_ERROR "Pass -DPRIMITIVISER_PRIVATE_HEADER=<patched src/Map/MapPrimitiviser_P.h>")
endif()
if(NOT DEFINED DATA_INTERFACE_FILE OR NOT EXISTS "${DATA_INTERFACE_FILE}")
    message(FATAL_ERROR "Pass -DDATA_INTERFACE_FILE=<patched src/ObfDataInterface.cpp>")
endif()

file(READ "${PRIMITIVISER_FILE}" source)
file(READ "${PRIMITIVISER_PRIVATE_HEADER}" private_header)
file(READ "${DATA_INTERFACE_FILE}" data_interface)

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
require_source("const auto styleZoom = static_cast<ZoomLevel>[(]BasemapOverzoom::sourceZoom[(]" "style zoom selected from tested policy")
require_source("useCoarseBasemapStyle, zoom, MapPrimitiviser::LastZoomToUseBasemap" "coarse rules apply only to fallback primitives")
require_source("polygonizedCoastlineObjects" "generated coastline/surface fallback is still processed")
require_source("useCoarseBasemapStyle= [*]/ detailedDataMissing" "fallback policy is based on missing detail")
require_source("useCoarseBasemapStyle [?] styleZoom : detailedZoom" "polyline evaluator preserves detailed style outside fallback")
require_source("detailedBinaryMapObjectsPresent [|]= !isContourLinesObject" "contours are not regional cartography")
require_source("detailedLandDataPresent [|][|] !detailedmapCoastlineObjects.isEmpty[(][)]" "routing-only objects cannot hide the world map")
require_source("!isBasemapObject && !possiblyBasemapObject->section->isContourLines" "non-surface fallback also excludes contours")
require_source("binaryObject && binaryObject->section->isBasemap, zoom, MapPrimitiviser::LastZoomToUseBasemap" "label zoom chosen per source object")
require_source("textOrderCache.clear[(][)]" "text style cache is reset between coarse and detailed objects")
require_source("id_INPUT_MINZOOM, textZoom" "coarse label minimum zoom")
require_source("id_INPUT_MAXZOOM, textZoom" "coarse label maximum zoom")

string(REGEX MATCHALL "BasemapOverzoom::needsFallback[(]" fallback_decisions "${source}")
list(LENGTH fallback_decisions fallback_decision_count)
if(NOT fallback_decision_count EQUAL 2)
    message(FATAL_ERROR "Both surface and non-surface rendering must use the tested fallback policy")
endif()

foreach(pattern IN ITEMS
        "mapSection->isBasemap, zoom, ObfMapSectionLevel::MaxBasemapZoomLevel"
        "Utilities::roundBoundingBox31[(]tileBBox31, sectionZoom[)]"
        "sectionZoom,[ \r\n]+&sectionBBox31")
    string(REGEX MATCHALL "${pattern}" reads "${data_interface}")
    list(LENGTH reads read_count)
    if(NOT read_count EQUAL 2)
        message(FATAL_ERROR "Both map readers must keep the supplementary basemap's parent level: ${pattern}")
    endif()
endforeach()

string(REGEX MATCHALL "useCoarseBasemapStyle= [*]/ detailedDataMissing" fallback_calls "${source}")
list(LENGTH fallback_calls fallback_call_count)
if(NOT fallback_call_count EQUAL 3)
    message(FATAL_ERROR "Expected three coarse-fallback call paths (surface basemap, surface coastline, non-surface basemap), got ${fallback_call_count}")
endif()

if(source MATCHES "std::all_of\\(source")
    message(FATAL_ERROR "Fallback style policy must not infer provenance from a mixed generated surface source")
endif()

message(STATUS "Basemap overzoom fallback source paths: OK")
