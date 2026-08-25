package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider

/** A location engine that [LocationDemo] offers on its engine selector. */
internal interface DemoLocationEngine {
  /** The short label that names this engine on the selector. */
  val label: String

  @Composable fun rememberLocationProvider(): LocationProvider

  @Composable fun rememberOrientationProvider(): OrientationProvider
}

/** The providers that the platform selects by default. */
internal object DefaultLocationEngine : DemoLocationEngine {
  override val label = "Auto"

  @Composable
  override fun rememberLocationProvider(): LocationProvider = rememberDefaultLocationProvider()

  @Composable
  override fun rememberOrientationProvider(): OrientationProvider =
    rememberDefaultOrientationProvider()
}

/**
 * The engines this platform offers, starting with [DefaultLocationEngine]. A platform with one
 * engine shows no selector.
 */
internal expect val demoLocationEngines: List<DemoLocationEngine>
