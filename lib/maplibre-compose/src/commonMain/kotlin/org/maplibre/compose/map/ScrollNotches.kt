package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

/**
 * Converts the size of one scroll event to wheel notches. A value of 1.0 is one wheel detent. A
 * trackpad can produce a fractional value.
 *
 * Compose does not use the same `scrollDelta` unit on every host. Each host converts its value to
 * wheel notches. A host can use [density] to reverse a dp conversion.
 */
internal expect fun scrollNotches(scrollDelta: Float, density: Density): Double
