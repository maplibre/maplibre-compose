package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.gms.rememberFusedLocationProvider
import org.maplibre.compose.gms.rememberFusedOrientationProvider
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider

@Composable
internal actual fun rememberDemoLocationProvider(usePlayServices: Boolean): LocationProvider =
  if (usePlayServices) rememberFusedLocationProvider() else rememberDefaultLocationProvider()

@Composable
internal actual fun rememberDemoOrientationProvider(usePlayServices: Boolean): OrientationProvider =
  if (usePlayServices) rememberFusedOrientationProvider() else rememberDefaultOrientationProvider()

@Composable
internal actual fun LocationEngineRow(usePlayServices: Boolean, onChange: (Boolean) -> Unit) {
  SwitchRow("Google Play Services", usePlayServices, onChange)
}
