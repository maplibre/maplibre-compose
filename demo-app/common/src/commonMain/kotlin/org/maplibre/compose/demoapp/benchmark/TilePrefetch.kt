package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.BoundingBox

interface TilePrefetcher {
  val mode: String

  suspend fun ensurePacked(
    scenarioId: String,
    styleUrl: String,
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    camera: MapState,
    onStatus: (String) -> Unit,
  )
}

@Composable expect fun rememberTilePrefetcher(): TilePrefetcher

expect val benchmarkPlatformLabel: String
