package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val coroutineScope = rememberCoroutineScope()
  return remember(coroutineScope) { BrowserLocationProvider(coroutineScope) }
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider = NullOrientationProvider

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher =
  BrowserSystemSettingsLauncher
