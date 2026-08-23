package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

internal suspend fun playCamera(
  camera: CameraState,
  from: CameraPosition,
  to: CameraPosition,
  duration: Duration,
) {
  val start = TimeSource.Monotonic.markNow()
  val durationNs = duration.inWholeNanoseconds.toDouble().coerceAtLeast(1.0)
  camera.position = from
  while (true) {
    val elapsedNs = start.elapsedNow().inWholeNanoseconds.toDouble()
    val t = (elapsedNs / durationNs).coerceIn(0.0, 1.0)
    camera.position = lerp(from, to, t)
    if (t >= 1.0) break
    withFrameNanos {}
  }
}

internal fun lerp(from: CameraPosition, to: CameraPosition, t: Double): CameraPosition {
  val u = t.coerceIn(0.0, 1.0)
  return CameraPosition(
    bearing = lerpDegrees(from.bearing, to.bearing, u),
    target =
      Position(
        longitude = lerp(from.target.longitude, to.target.longitude, u),
        latitude = lerp(from.target.latitude, to.target.latitude, u),
      ),
    tilt = lerp(from.tilt, to.tilt, u),
    zoom = lerp(from.zoom, to.zoom, u),
  )
}

internal fun lerp(from: Double, to: Double, t: Double): Double = from + (to - from) * t

internal fun lerpDegrees(from: Double, to: Double, t: Double): Double {
  var delta = (to - from) % 360.0
  if (delta > 180.0) delta -= 360.0
  if (delta < -180.0) delta += 360.0
  return (from + delta * t + 360.0) % 360.0
}

internal fun orbitPosition(
  center: Position,
  radiusLon: Double,
  radiusLat: Double,
  t: Double,
): Position {
  val angle = t * 2.0 * PI
  return Position(
    longitude = center.longitude + cos(angle) * radiusLon,
    latitude = center.latitude + sin(angle) * radiusLat,
  )
}
