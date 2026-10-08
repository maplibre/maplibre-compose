package org.maplibre.compose.map

import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** Provides the borrowed MapLibre GL JS map for one [MapState.withPlatformMap] callback. */
@DelicateMaplibreComposeApi
// Exposes MapLibre GL JS bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public actual class PlatformMapScope internal constructor(private val engineMap: MaplibreMap) {
  /** The raw MapLibre GL JS `Map` object. */
  public val map: dynamic
    get() = engineMap
}

@DelicateMaplibreComposeApi
// Exposes MapLibre GL JS bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public actual suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T {
  val session = lifecycle.presentationAdapterForPlatformAccess() as GlJsMapSession
  return session.withPlatformMap(block)
}
