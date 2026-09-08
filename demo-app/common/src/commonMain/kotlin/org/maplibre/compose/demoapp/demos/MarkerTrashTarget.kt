package org.maplibre.compose.demoapp.demos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal class MarkerTrashTarget(private val start: Offset) {
  private var initialized = false
  var needsExit = false
    private set

  fun update(pointer: Offset, bounds: Rect?): Boolean {
    if (bounds == null) return false
    // Layout can arrive after the drag starts. Check the original press, not the first move.
    if (!initialized) {
      needsExit = bounds.contains(start)
      initialized = true
    }

    val inside = bounds.contains(pointer)
    if (!inside) needsExit = false
    return inside && !needsExit
  }
}
