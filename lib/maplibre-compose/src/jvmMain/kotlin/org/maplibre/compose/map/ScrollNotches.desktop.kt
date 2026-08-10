package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

/**
 * Desktop hosts report wheel rotation, which is already the unit this wants: AWT's
 * `MouseWheelEvent.getWheelRotation` and GLFW's scroll offset both count one per detent, and
 * neither scales with the display.
 *
 * A host that reports scrolled distance instead would zoom about a hundred times too fast, in which
 * case this is the one place that has to learn about it.
 */
internal actual fun scrollNotches(scrollDelta: Float, density: Density): Double =
  scrollDelta.toDouble()
