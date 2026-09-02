package org.maplibre.compose.demoapp.benchmark

import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapState

internal object ZoomPumpScenario : BenchmarkScenario {
  override val id = "zoom-pump"
  override val title = "Zoom pump"
  override val description = "Zooms in and out over cached tiles to stress rasterization."
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera = CameraPosition(target = BenchmarkCenter, zoom = WideZoom)

  override suspend fun run(mapState: MapState, session: BenchmarkSession) {
    val wide = CameraPosition(target = BenchmarkCenter, zoom = WideZoom)
    val tight = CameraPosition(target = BenchmarkCenter, zoom = TightZoom)
    mapState.setCameraPosition(wide)
    repeat(Cycles) {
      playCamera(mapState, wide, tight, HalfCycle)
      playCamera(mapState, tight, wide, HalfCycle)
    }
  }

  private const val WideZoom = 12.5
  private const val TightZoom = 16.0
  private const val Cycles = 3
  private val HalfCycle = 1200.milliseconds
}
