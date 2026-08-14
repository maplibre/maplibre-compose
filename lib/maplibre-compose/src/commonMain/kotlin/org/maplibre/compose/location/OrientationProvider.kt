package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Supplies device-heading measurements. */
public interface OrientationProvider {
  /** The latest heading, or `null` before one is available. */
  public val orientation: StateFlow<Orientation?>
}

/** An orientation provider that never supplies a heading. */
public object NullOrientationProvider : OrientationProvider {
  public override val orientation: StateFlow<Orientation?> = MutableStateFlow(null)
}

/**
 * Creates and remembers the default orientation provider for the current platform.
 *
 * Web and desktop return [NullOrientationProvider]. See
 * [rememberAndroidOrientationProvider][org.maplibre.compose.location.rememberAndroidOrientationProvider]
 * and
 * [rememberIosOrientationProvider][org.maplibre.compose.location.rememberIosOrientationProvider].
 * Google Play Services applications can instead use
 * [rememberFusedOrientationProvider][org.maplibre.compose.gms.rememberFusedOrientationProvider].
 */
@Composable
public expect fun rememberDefaultOrientationProvider(
  updateInterval: Duration = 1.seconds
): OrientationProvider

/** Creates and remembers an orientation provider that never supplies a heading. */
@Composable
public fun rememberNullOrientationProvider(): OrientationProvider {
  return NullOrientationProvider
}
