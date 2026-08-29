package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider = remember {
  IosLocationProvider()
}

@Composable
public actual fun rememberDefaultHeadingProvider(): HeadingProvider = remember {
  IosHeadingProvider()
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher = remember {
  IosSystemSettingsLauncher()
}
