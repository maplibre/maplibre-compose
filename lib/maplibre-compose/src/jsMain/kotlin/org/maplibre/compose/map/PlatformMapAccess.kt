package org.maplibre.compose.map

import org.maplibre.compose.gljs.MaplibreMap

/** Provides the borrowed MapLibre GL JS map for one [MapState.withPlatformMap] callback. */
public actual class PlatformMapScope internal constructor(private val engineMap: MaplibreMap) {
  /** The raw MapLibre GL JS `Map` object. */
  public val map: dynamic
    get() = engineMap
}

@DelicateMapApi
public actual suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T {
  val session = lifecycle.presentationAdapterForPlatformAccess() as GlJsMapSession
  return session.withPlatformMap(block)
}
