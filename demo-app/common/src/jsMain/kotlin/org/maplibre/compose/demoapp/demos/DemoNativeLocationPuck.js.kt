package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.util.MaplibreComposable

actual val isDemoNativeLocationPuckAvailable: Boolean = false

@Composable @MaplibreComposable actual fun DemoNativeLocationPuck(locationState: LocationState) {}
