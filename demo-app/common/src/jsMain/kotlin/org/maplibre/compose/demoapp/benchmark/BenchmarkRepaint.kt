package org.maplibre.compose.demoapp.benchmark

import org.maplibre.compose.map.DelicateMapApi
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.withPlatformMap

@OptIn(DelicateMapApi::class)
internal actual suspend fun benchmarkRequestRepaint(state: MapState) {
  state.withPlatformMap { map.triggerRepaint() }
}
