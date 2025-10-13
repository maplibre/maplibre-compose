package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

public class UserLocationState internal constructor(locationState: State<Location?>) {
  public val location: Location? by locationState
}

@Composable
public fun rememberUserLocationState(geoLocator: GeoLocator): UserLocationState {
  val locationState = geoLocator.location.collectAsStateWithLifecycle()
  return remember(locationState) { UserLocationState(locationState) }
}
