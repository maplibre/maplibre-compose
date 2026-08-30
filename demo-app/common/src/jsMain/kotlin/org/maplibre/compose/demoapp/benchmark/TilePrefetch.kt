package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@Composable
actual fun rememberTilePrefetcher(): TilePrefetcher = remember { HttpWarmupPrefetcher() }

actual val benchmarkPlatformLabel: String = "Web"

private class HttpWarmupPrefetcher : TilePrefetcher {
  override val mode = "http-warmup"

  override suspend fun ensurePacked(
    scenarioId: String,
    styleUrl: String,
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    camera: MapState,
    onStatus: (String) -> Unit,
  ) {
    val origin = camera.cameraPosition
    val center =
      Position(
        longitude = (bounds.west + bounds.east) / 2,
        latitude = (bounds.south + bounds.north) / 2,
      )
    for (zoom in minZoom..maxZoom) {
      onStatus("Warming zoom $zoom")
      camera.presentation?.setCameraPosition(
        CameraPosition(target = center, zoom = zoom.toDouble())
      )
      delay(350.milliseconds)
    }
    camera.presentation?.setCameraPosition(origin)
  }
}
