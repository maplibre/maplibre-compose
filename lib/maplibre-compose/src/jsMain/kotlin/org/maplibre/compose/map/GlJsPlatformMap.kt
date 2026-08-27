package org.maplibre.compose.map

import org.maplibre.compose.gljs.MaplibreMap

public actual typealias PlatformMap = MaplibreMap

@DelicateMapApi
public actual suspend fun <T> MapState.withPlatformMap(block: (PlatformMap) -> T): T {
  check(!isClosed) { "MapState is closed; the platform map is destroyed" }
  val map =
    engine.session?.liveMap
      ?: throw IllegalStateException(
        "MapState has no live map while detached; on Web the map exists only while a MaplibreMap " +
          "is composed"
      )
  return block(map)
}
