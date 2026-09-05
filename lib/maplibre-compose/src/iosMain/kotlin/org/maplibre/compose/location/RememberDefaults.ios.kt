package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val provider = remember { IosLocationProvider() }
  DisposableEffect(provider) { onDispose { provider.close() } }
  return provider
}

@Composable
public actual fun rememberDefaultHeadingProvider(): HeadingProvider = remember {
  IosHeadingProvider()
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher = remember {
  IosSystemSettingsLauncher()
}
