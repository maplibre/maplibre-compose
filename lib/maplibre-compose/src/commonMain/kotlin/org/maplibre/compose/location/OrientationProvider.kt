package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public interface OrientationProvider {
  public val orientation: StateFlow<Orientation?>
}

public class NullOrientationProvider : OrientationProvider {
  public override val orientation: StateFlow<Orientation?> = MutableStateFlow(null)
}

@Composable
public expect fun rememberDefaultOrientationProvider(
  updateInterval: Duration = 1.seconds
): OrientationProvider

@Composable
public fun rememberNullOrientationProvider(): OrientationProvider {
  return remember { NullOrientationProvider() }
}
