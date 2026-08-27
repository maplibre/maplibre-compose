package org.maplibre.compose.map

import org.maplibre.nativeffi.map.MapHandle

public actual typealias PlatformMap = MapHandle

@DelicateMapApi
public actual suspend fun <T> MapState.withPlatformMap(block: (PlatformMap) -> T): T {
  check(!isClosed) { "MapState is closed; the platform map is destroyed" }
  val core =
    engine.core
      ?: throw IllegalStateException(
        "MapState has no platform map; the map is created at the first MaplibreMap attach or " +
          "snapshot"
      )
  return core.withMapHandle(block)
}
