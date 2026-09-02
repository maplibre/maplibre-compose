package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.withFrameNanos
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapState

internal object FlyAroundScenario : BenchmarkScenario {
  override val id = "fly-around"
  override val title = "Fly-around"
  override val description = "Orbits a pitched camera around the packed region."
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera = CameraPosition(target = BenchmarkCenter, zoom = OrbitZoom, tilt = OrbitTilt)

  override suspend fun run(mapState: MapState, session: BenchmarkSession) {
    val start = TimeSource.Monotonic.markNow()
    val durationNs = Duration.inWholeNanoseconds.toDouble()
    while (true) {
      val t = (start.elapsedNow().inWholeNanoseconds.toDouble() / durationNs).coerceIn(0.0, 1.0)
      mapState.setCameraPosition(
        CameraPosition(
          target = orbitPosition(BenchmarkCenter, RadiusLon, RadiusLat, t),
          zoom = OrbitZoom,
          tilt = OrbitTilt,
          bearing = t * 360.0,
        )
      )
      if (t >= 1.0) break
      withFrameNanos {}
    }
  }

  private const val OrbitZoom = 14.5
  private const val OrbitTilt = 50.0
  private const val RadiusLon = 0.004
  private const val RadiusLat = 0.003
  private val Duration = 6.seconds
}
