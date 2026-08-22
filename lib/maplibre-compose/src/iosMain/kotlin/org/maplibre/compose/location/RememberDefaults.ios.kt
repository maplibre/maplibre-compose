package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider = remember {
  IosLocationProvider()
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  val coroutineScope = rememberCoroutineScope()
  return remember(updateInterval, coroutineScope) {
    IosOrientationProvider(updateInterval, coroutineScope)
  }
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher = remember {
  IosSystemSettingsLauncher()
}
