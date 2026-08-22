package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.gms.rememberFusedLocationProvider
import org.maplibre.compose.gms.rememberFusedOrientationProvider
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.rememberAndroidLocationProvider
import org.maplibre.compose.location.rememberAndroidOrientationProvider

/** The Google Play Services fused providers, regardless of backend discovery. */
private object FusedLocationEngine : DemoLocationEngine {
  override val label = "Fused"

  @Composable
  override fun rememberLocationProvider(): LocationProvider = rememberFusedLocationProvider()

  @Composable
  override fun rememberOrientationProvider(): OrientationProvider =
    rememberFusedOrientationProvider()
}

/** The Android framework providers, regardless of backend discovery. */
private object FrameworkLocationEngine : DemoLocationEngine {
  override val label = "Framework"

  @Composable
  override fun rememberLocationProvider(): LocationProvider = rememberAndroidLocationProvider()

  @Composable
  override fun rememberOrientationProvider(): OrientationProvider =
    rememberAndroidOrientationProvider(updateInterval = 1.seconds)
}

internal actual val demoLocationEngines: List<DemoLocationEngine> =
  listOf(DefaultLocationEngine, FusedLocationEngine, FrameworkLocationEngine)
