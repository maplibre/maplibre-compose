package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val window = LocalXdgPortalWindow.current
  val provider = remember(window) { createDefaultLocationProvider(window) }
  DisposableEffect(provider) { onDispose { provider.close() } }
  return provider
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider = NullOrientationProvider

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher = remember {
  DesktopSystemSettingsLauncher()
}
