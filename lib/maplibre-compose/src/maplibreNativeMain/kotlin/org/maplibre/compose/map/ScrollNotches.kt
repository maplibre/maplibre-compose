package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

// Compose's Android, AWT, and GLFW scroll deltas already count one per detent, so they must not
// scale with density.
internal actual fun scrollNotches(scrollDelta: Float, density: Density): Double =
  scrollDelta.toDouble()
