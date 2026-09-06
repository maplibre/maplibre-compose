package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Only the host adapter interprets native metadata. Axis values always come from Compose. */
internal expect fun scrollUnits(event: PointerEvent): ScrollUnits

internal enum class ScrollUnits {
  BrowserPixel,
  BrowserLine,
  BrowserPage,
  MacRotation,
  Rotation,
  IosIndirect,
}

internal data class NormalizedScroll(
  val panDelta: DpOffset,
  val zoomNotches: DpOffset,
) {
  /** Horizontal-only wheels work; opposite-sign diagonal axes never cancel one another. */
  val zoomComponent: Double
    get() =
      if (abs(zoomNotches.x.value) > abs(zoomNotches.y.value)) zoomNotches.x.value.toDouble()
      else zoomNotches.y.value.toDouble()
}

/** Converts host scroll units before any binding claims the sample. */
internal fun normalizeScroll(
  raw: Offset,
  units: ScrollUnits,
  density: Density,
  viewportSize: IntSize,
): NormalizedScroll? {
  if (!raw.x.isFinite() || !raw.y.isFinite() || (raw.x == 0f && raw.y == 0f)) return null
  val pixelsPerDp = density.density.toDouble()
  if (!pixelsPerDp.isFinite() || pixelsPerDp <= 0.0) return null
  fun pan(component: Float, size: Int): Float {
    val multiplier =
      when (units) {
        ScrollUnits.BrowserPixel -> 1.0
        ScrollUnits.BrowserLine -> 100.0 / 3.0
        ScrollUnits.BrowserPage -> size / pixelsPerDp
        ScrollUnits.MacRotation -> 10.0
        ScrollUnits.Rotation -> 40.0
        ScrollUnits.IosIndirect -> 100.0 / pixelsPerDp
      }
    return (-component * multiplier).toFloat()
  }
  fun notches(component: Float): Float =
    (component /
        when (units) {
          ScrollUnits.BrowserPixel -> 100.0
          ScrollUnits.BrowserLine -> 3.0
          ScrollUnits.IosIndirect -> pixelsPerDp
          else -> 1.0
        })
      .toFloat()
  val x = pan(raw.x, viewportSize.width)
  val y = pan(raw.y, viewportSize.height)
  val notchX = notches(raw.x)
  val notchY = notches(raw.y)
  // JS numbers retain double precision until DpOffset packs them into two Float values.
  if (listOf(x, y, notchX, notchY).any { !it.isFinite() || abs(it) > Float.MAX_VALUE }) return null
  return NormalizedScroll(
    DpOffset(x.dp, y.dp),
    DpOffset(notchX.dp, notchY.dp),
  )
}
