package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

internal actual fun scrollNotches(scrollDelta: Float, density: Density): Double =
  scrollDelta.toDouble()
