package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Creates and remembers the default location provider for the current platform.
 *
 * Platform provider constructors remain available when an application needs configuration beyond
 * [LocationRequest]. An unsupported target or host returns a provider whose
 * [LocationProvider.backendAvailability] is [LocationBackendAvailability.Unsupported] instead of
 * throwing during composition.
 */
@Composable public expect fun rememberDefaultLocationProvider(): LocationProvider

/**
 * Creates and remembers the default orientation provider for the current platform.
 *
 * Android and iOS supply sensor-based headings. Web and desktop return [NullOrientationProvider].
 */
@Composable
public expect fun rememberDefaultOrientationProvider(
  updateInterval: Duration = 1.seconds
): OrientationProvider

/** Creates and remembers the platform [SystemSettingsLauncher]. */
@Composable public expect fun rememberSystemSettingsLauncher(): SystemSettingsLauncher
