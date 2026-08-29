package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.maplibre.compose.gms.GmsLocationBackend
import org.maplibre.compose.hms.HmsLocationBackend
import org.maplibre.compose.location.AndroidHeadingProvider
import org.maplibre.compose.location.AndroidLocationProvider
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.LocationProvider

/** The Google Play Services fused providers, regardless of backend discovery. */
private object GmsLocationEngine : DemoLocationEngine {
  override val label = "GMS"

  @Composable
  override fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember(context) { GmsLocationBackend().createLocationProvider(context) }
  }

  @Composable
  override fun rememberHeadingProvider(): HeadingProvider {
    val context = LocalContext.current
    return remember(context) { GmsLocationBackend().createHeadingProvider(context) }
  }
}

/** Huawei Mobile Services fused location with framework heading. */
private object HmsLocationEngine : DemoLocationEngine {
  override val label = "HMS"

  @Composable
  override fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember(context) { HmsLocationBackend().createLocationProvider(context) }
  }

  @Composable
  override fun rememberHeadingProvider(): HeadingProvider {
    val context = LocalContext.current
    return remember(context) { AndroidHeadingProvider(context) }
  }
}

/** The Android framework providers, regardless of backend discovery. */
private object FrameworkLocationEngine : DemoLocationEngine {
  override val label = "Framework"

  @Composable
  override fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember(context) { AndroidLocationProvider(context) }
  }

  @Composable
  override fun rememberHeadingProvider(): HeadingProvider {
    val context = LocalContext.current
    return remember(context) { AndroidHeadingProvider(context) }
  }
}

internal actual val demoLocationEngines: List<DemoLocationEngine> =
  listOf(
    DefaultLocationEngine,
    GmsLocationEngine,
    HmsLocationEngine,
    FrameworkLocationEngine,
  )
