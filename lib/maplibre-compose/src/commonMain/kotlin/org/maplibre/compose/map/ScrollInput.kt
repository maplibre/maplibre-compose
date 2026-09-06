package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Converts host scroll units to content displacement in pixels, following platform scrolling. */
internal typealias ScrollConverter = (PointerEvent, Density, IntSize) -> Offset

@Composable internal expect fun rememberScrollConverter(): ScrollConverter

/** Rejects unusable displacement before a binding claims input. */
internal fun normalizeScroll(delta: Offset, density: Density): DpOffset? {
  if (!density.density.isFinite() || density.density <= 0f) return null
  val logical = delta / density.density
  if (!logical.x.isFinite() || !logical.y.isFinite() || logical == Offset.Zero) return null
  return DpOffset(logical.x.dp, logical.y.dp)
}

internal val PointerEvent.totalScrollDelta: Offset
  get() = changes.fold(Offset.Zero) { delta, change -> delta + change.scrollDelta }
