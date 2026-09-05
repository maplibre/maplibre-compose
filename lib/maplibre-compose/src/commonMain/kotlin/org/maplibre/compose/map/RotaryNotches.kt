package org.maplibre.compose.map

import androidx.compose.runtime.Composable

/**
 * The scroll distance, in pixels, that one detent of a rotary input such as a watch crown produces
 * in a rotary scroll event. Zero on hosts without rotary input, which disables rotary zoom.
 */
@Composable internal expect fun rotaryNotchPixels(): Float
