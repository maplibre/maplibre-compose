package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking

/** The tests' blocking live-layer read, through the public escape hatch. */
@OptIn(DelicateMapApi::class)
internal fun MapState.liveStyleLayerIds(): List<String> = runBlocking {
  withPlatformMap { it.styleLayerIds() }
}
