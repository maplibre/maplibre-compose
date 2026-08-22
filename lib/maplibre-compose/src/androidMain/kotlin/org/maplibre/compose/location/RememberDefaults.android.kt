package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val context = LocalContext.current
  return remember(context) { createDefaultLocationProvider(context) }
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  return remember(context, updateInterval, coroutineScope) {
    createDefaultOrientationProvider(context, updateInterval, coroutineScope)
  }
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher {
  val context = LocalContext.current
  return remember(context) { AndroidSystemSettingsLauncher(context) }
}
