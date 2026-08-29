package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import kotlin.time.TimeMark
import org.maplibre.compose.location.LocationFix
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation

/**
 * Whether this platform draws the location indicator with MapLibre Native's built-in layer. The
 * browser runs MapLibre GL JS, which has no such layer.
 */
expect val isNativeLocationIndicatorAvailable: Boolean

/**
 * Draws the user's location with MapLibre Native's location indicator layer, the map-rendered
 * alternative to the [LocationPuck][org.maplibre.compose.location.LocationPuck] composable. Draws
 * nothing on platforms where [isNativeLocationIndicatorAvailable] is false.
 */
@Composable
@MaplibreComposable
expect fun NativeLocationIndicator(
  location: LocationFix?,
  measurementMark: TimeMark?,
  bearing: Bearing?,
  bearingAccuracy: Rotation?,
)
