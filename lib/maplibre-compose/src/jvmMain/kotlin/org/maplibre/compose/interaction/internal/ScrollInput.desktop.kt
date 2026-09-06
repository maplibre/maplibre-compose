package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.awt.event.MouseWheelEvent
import kotlin.math.sqrt

@Composable internal actual fun rememberScrollConverter(): ScrollConverter = DesktopScrollConverter

private val desktopOs = System.getProperty("os.name").lowercase()
private val DesktopScrollConverter: ScrollConverter = { event, density, bounds ->
  desktopScrollDelta(
    event.totalScrollDelta,
    event.awtEventOrNull as? MouseWheelEvent,
    density,
    bounds,
    desktopOs,
  )
}

/** Matches Compose's macOS, Windows, and Linux scroll distances and AWT line/page settings. */
internal fun desktopScrollDelta(
  raw: Offset,
  nativeEvent: MouseWheelEvent?,
  density: Density,
  bounds: IntSize,
  os: String,
): Offset {
  val amount = nativeEvent?.scrollAmount?.toFloat() ?: 1f
  val distance =
    when {
      nativeEvent?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL ->
        Offset(bounds.width.toFloat(), bounds.height.toFloat())
      os.startsWith("mac") -> Offset(10f, 10f) * density.density
      os.startsWith("linux") -> Offset(sqrt(bounds.width.toFloat()), sqrt(bounds.height.toFloat()))
      else -> Offset(bounds.width / 20f, bounds.height / 20f)
    }
  return Offset(-raw.x * distance.x * amount, -raw.y * distance.y * amount)
}
