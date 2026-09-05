package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val context = LocalContext.current
  val provider = remember(context) { createDefaultLocationProvider(context) }
  DisposableEffect(provider) { onDispose { provider.close() } }
  return provider
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
