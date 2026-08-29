package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.util.MaplibreComposable

/**
 * Whether this platform draws the location indicator with MapLibre Native's built-in layer. The
 * browser runs MapLibre GL JS, which has no such layer.
 */
expect val isDemoNativeLocationPuckAvailable: Boolean

/**
 * Draws the user's location with MapLibre Native's location indicator layer, the map-rendered
 * alternative to the [LocationPuck][org.maplibre.compose.location.LocationPuck] composable. Draws
 * nothing on platforms where [isDemoNativeLocationPuckAvailable] is false.
 */
@Composable @MaplibreComposable expect fun DemoNativeLocationPuck(locationState: LocationState)
