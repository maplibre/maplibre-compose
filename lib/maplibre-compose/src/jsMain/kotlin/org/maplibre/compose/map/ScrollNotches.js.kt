package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

/**
 * How far a browser scrolls a page for one detent of a mouse wheel, in CSS pixels.
 *
 * TODO: Firefox may report a wheel in lines instead, which arrives as a number near three and so
 *   reads as a thirtieth of a notch. Compose hands on `deltaY` without the `deltaMode` that would
 *   tell them apart, so that case zooms too slowly until it does.
 */
private const val CSS_PIXELS_PER_NOTCH = 100.0

/**
 * A browser reports how far the page would have scrolled in CSS pixels, and Compose divides that by
 * the display density on the way here. A CSS pixel is already a physical unit, so the division has
 * to be undone before the notch conversion.
 */
internal actual fun scrollNotches(scrollDelta: Float, density: Density): Double =
  scrollDelta.toDouble() * density.density / CSS_PIXELS_PER_NOTCH
