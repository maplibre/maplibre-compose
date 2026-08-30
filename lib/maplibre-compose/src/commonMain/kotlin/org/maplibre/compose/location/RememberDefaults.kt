package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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
 * Creates and remembers the default heading provider for the current platform.
 *
 * Android and iOS supply sensor-based headings. Web and desktop return a provider that completes
 * without measurements.
 */
@Composable public expect fun rememberDefaultHeadingProvider(): HeadingProvider

internal object NoHeadingProvider : HeadingProvider {
  override fun updates(request: HeadingRequest): Flow<HeadingMeasurement> = emptyFlow()
}

/** Creates and remembers the platform [SystemSettingsLauncher]. */
@Composable public expect fun rememberSystemSettingsLauncher(): SystemSettingsLauncher
