package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.LocationFix
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

actual val isNativeLocationIndicatorAvailable: Boolean = true

@Composable
@MaplibreComposable
actual fun NativeLocationIndicator(
  location: LocationFix?,
  measurementMark: TimeMark?,
  bearing: Bearing?,
  bearingAccuracy: Rotation?,
) {
  if (location == null) return

  val isOld = rememberIsLocationOld(location, measurementMark)
  val colors = LocationPuckDefaults.colors()
  val dotFill = if (isOld) colors.dotFillColorOldLocation else colors.dotFillColorCurrentLocation
  val dot = remember(dotFill, colors) { DotPainter(dotFill, colors.dotStrokeColor) }
  val arrow = remember(colors) { ArrowPainter(colors.bearingColor) }

  LocationIndicatorLayer(
    id = "native-location-indicator",
    location = location.position,
    bearing = const(bearing?.let { (it - Bearing.North).inDegrees.toFloat() } ?: 0f),
    accuracyRadius =
      const(if (isOld) 0f else location.horizontalAccuracy?.inMeters?.toFloat() ?: 0f),
    accuracyRadiusColor = const(colors.accuracyFillColor),
    accuracyRadiusBorderColor = const(colors.accuracyStrokeColor),
    topImage = image(dot, size = DpSize(24.dp, 24.dp)),
    bearingImage = if (bearing != null) image(arrow, size = DpSize(56.dp, 56.dp)) else nil(),
  )
}

/**
 * Whether [location] is older than the same 30-second threshold [LocationPuck]
 * [org.maplibre.compose.location.LocationPuck] uses to mark a retained fix as stale.
 */
@Composable
private fun rememberIsLocationOld(location: LocationFix, measurementMark: TimeMark?): Boolean {
  val threshold = 30.seconds
  val effectiveMeasurementMark =
    remember(location, measurementMark) {
      measurementMark
        ?: TimeSource.Monotonic.markNow() -
          (Clock.System.now() - location.measuredAt).coerceAtLeast(Duration.ZERO)
    }
  var isOld by
    remember(effectiveMeasurementMark) {
      mutableStateOf(effectiveMeasurementMark.elapsedNow() > threshold)
    }
  LaunchedEffect(effectiveMeasurementMark) {
    if (isOld) return@LaunchedEffect
    val remaining = threshold - effectiveMeasurementMark.elapsedNow()
    if (remaining > Duration.ZERO) delay(remaining)
    isOld = true
  }
  return isOld
}

/** The location dot: a filled circle with a stroke, sized by the canvas it is drawn into. */
private class DotPainter(private val fill: Color, private val stroke: Color) : Painter() {
  override val intrinsicSize: Size = Size.Unspecified

  override fun DrawScope.onDraw() {
    val strokeWidth = size.minDimension / 8f
    val radius = (size.minDimension - strokeWidth) / 2f
    drawCircle(fill, radius = radius)
    drawCircle(stroke, radius = radius, style = Stroke(strokeWidth))
  }
}

/**
 * The bearing arrow: a triangle at the top of the canvas. The layer rotates the whole image around
 * its center, so the empty middle leaves room for the dot.
 */
private class ArrowPainter(private val color: Color) : Painter() {
  override val intrinsicSize: Size = Size.Unspecified

  override fun DrawScope.onDraw() {
    val path =
      Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width * 0.65f, size.height * 0.25f)
        lineTo(size.width * 0.35f, size.height * 0.25f)
        close()
      }
    drawPath(path, color)
  }
}
