package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val context = LocalContext.current
  return remember(context) { createDefaultLocationProvider(context) }
}

@Composable
public actual fun rememberDefaultHeadingProvider(): HeadingProvider {
  val context = LocalContext.current
  return remember(context) { createDefaultHeadingProvider(context) }
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher {
  val context = LocalContext.current
  return remember(context) { AndroidSystemSettingsLauncher(context) }
}
