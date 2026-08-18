package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider

@Composable
internal actual fun rememberDemoLocationProvider(usePlayServices: Boolean): LocationProvider =
  rememberDefaultLocationProvider()

@Composable
internal actual fun rememberDemoOrientationProvider(usePlayServices: Boolean): OrientationProvider =
  rememberDefaultOrientationProvider()

@Composable
internal actual fun LocationEngineRow(usePlayServices: Boolean, onChange: (Boolean) -> Unit) {}
