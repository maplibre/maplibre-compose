package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider

/**
 * Platform location and orientation used by [LocationDemo]. Android can switch to Play Services.
 */
@Composable
internal expect fun rememberDemoLocationProvider(usePlayServices: Boolean): LocationProvider

@Composable
internal expect fun rememberDemoOrientationProvider(usePlayServices: Boolean): OrientationProvider

/** Android-only switch between Play Services and the platform location engine. */
@Composable
internal expect fun LocationEngineRow(usePlayServices: Boolean, onChange: (Boolean) -> Unit)
