package org.maplibre.compose.map

import androidx.compose.ui.unit.Density

/**
 * One scroll event's size, in wheel notches: 1.0 is one detent, and a trackpad reports fractions.
 *
 * Compose's `scrollDelta` carries no unit that holds across hosts, so each host converts its own.
 * [density] is passed because a host may have converted its raw delta to dp on the way here, which
 * an implementation may have to undo.
 */
internal expect fun scrollNotches(scrollDelta: Float, density: Density): Double
