package net.osmand.core.android

import net.osmand.core.jni.PolygonsCollection

class MapRendererView {
    val providers = mutableSetOf<PolygonsCollection>()
    var additions = 0
    var removals = 0
    fun hasSymbolsProvider(provider: PolygonsCollection) = provider in providers
    fun addSymbolsProvider(provider: PolygonsCollection) { providers += provider; additions++ }
    fun removeSymbolsProvider(provider: PolygonsCollection) { providers -= provider; removals++ }
}
