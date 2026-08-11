package org.maplibre.compose.location

import androidx.compose.runtime.Composable

@Composable
public actual fun rememberDefaultLocationProvider(): LocationProvider = UnsupportedLocationProvider

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: kotlin.time.Duration
): OrientationProvider = NullOrientationProvider
