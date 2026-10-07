package org.maplibre.compose.demoapp.benchmark

import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.withPlatformMap
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

@OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)
internal actual suspend fun benchmarkRequestRepaint(state: MapState) {
  state.withPlatformMap { map.requestRepaint() }
}
