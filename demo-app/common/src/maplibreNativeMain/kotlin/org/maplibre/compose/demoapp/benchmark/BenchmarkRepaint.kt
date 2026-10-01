package org.maplibre.compose.demoapp.benchmark

import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.withPlatformMap
import org.maplibre.compose.util.DelicateMaplibreComposeApi

@OptIn(DelicateMaplibreComposeApi::class)
internal actual suspend fun benchmarkRequestRepaint(state: MapState) {
  state.withPlatformMap { map.requestRepaint() }
}
