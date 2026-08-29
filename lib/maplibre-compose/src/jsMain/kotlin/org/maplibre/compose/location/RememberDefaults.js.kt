package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider {
  val coroutineScope = rememberCoroutineScope()
  return remember(coroutineScope) { BrowserLocationProvider(coroutineScope) }
}

@Composable public actual fun rememberDefaultHeadingProvider(): HeadingProvider = NoHeadingProvider

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher =
  BrowserSystemSettingsLauncher
