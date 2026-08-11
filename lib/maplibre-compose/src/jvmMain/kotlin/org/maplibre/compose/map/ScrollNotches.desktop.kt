package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

// AWT's MouseWheelEvent.getWheelRotation and GLFW's scroll offset both count one per detent, so the
// delta is already in notches and must not scale with density.
internal actual fun scrollNotches(scrollDelta: Float, density: Density): Double =
  scrollDelta.toDouble()
