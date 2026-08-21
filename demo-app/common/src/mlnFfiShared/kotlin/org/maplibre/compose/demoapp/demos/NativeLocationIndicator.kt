package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.BearingWithAccuracy
import org.maplibre.compose.location.Location
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import org.maplibre.spatialk.units.extensions.inMeters

actual val isNativeLocationIndicatorAvailable: Boolean = true

@Composable
@MaplibreComposable
actual fun NativeLocationIndicator(location: Location?, bearing: BearingWithAccuracy?) {
  if (location == null) return

  val colors = LocationPuckDefaults.colors()
  val dot =
    remember(colors) { DotPainter(colors.dotFillColorCurrentLocation, colors.dotStrokeColor) }
  val arrow = remember(colors) { ArrowPainter(colors.bearingColor) }

  LocationIndicatorLayer(
    id = "native-location-indicator",
    location = location.position.value,
    bearing = const(bearing?.let { (it.value - Bearing.North).inDegrees.toFloat() } ?: 0f),
    accuracyRadius = const(location.position.accuracy?.inMeters?.toFloat() ?: 0f),
    accuracyRadiusColor = const(colors.accuracyFillColor),
    accuracyRadiusBorderColor = const(colors.accuracyStrokeColor),
    topImage = image(dot, size = DpSize(24.dp, 24.dp)),
    bearingImage = if (bearing != null) image(arrow, size = DpSize(56.dp, 56.dp)) else nil(),
  )
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
