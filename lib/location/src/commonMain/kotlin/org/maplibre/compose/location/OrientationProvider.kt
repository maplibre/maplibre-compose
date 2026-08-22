package org.maplibre.compose.location

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
