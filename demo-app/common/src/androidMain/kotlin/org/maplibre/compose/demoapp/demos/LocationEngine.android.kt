package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.gms.GmsLocationBackend
import org.maplibre.compose.hms.HmsLocationBackend
import org.maplibre.compose.location.AndroidLocationProvider
import org.maplibre.compose.location.AndroidOrientationProvider
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider

/** The Google Play Services fused providers, regardless of backend discovery. */
private object GmsLocationEngine : DemoLocationEngine {
  override val label = "GMS"

  @Composable
  override fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember(context) { GmsLocationBackend().createLocationProvider(context) }
  }

  @Composable
  override fun rememberOrientationProvider(): OrientationProvider {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    return remember(context, coroutineScope) {
      GmsLocationBackend().createOrientationProvider(context, 1.seconds, coroutineScope)
    }
  }
}

/** Huawei Mobile Services fused location with framework orientation. */
private object HmsLocationEngine : DemoLocationEngine {
  override val label = "HMS"

  @Composable
  override fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember(context) { HmsLocationBackend().createLocationProvider(context) }
  }

  @Composable
  override fun rememberOrientationProvider(): OrientationProvider {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    return remember(context, coroutineScope) {
      AndroidOrientationProvider(context, 1.seconds, coroutineScope)
    }
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
  override fun rememberOrientationProvider(): OrientationProvider {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    return remember(context, coroutineScope) {
      AndroidOrientationProvider(context, 1.seconds, coroutineScope)
    }
  }
}

internal actual val demoLocationEngines: List<DemoLocationEngine> =
  listOf(
    DefaultLocationEngine,
    GmsLocationEngine,
    HmsLocationEngine,
    FrameworkLocationEngine,
  )
