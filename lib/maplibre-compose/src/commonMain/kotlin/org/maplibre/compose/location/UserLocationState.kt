package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration

public class UserLocationState internal constructor(locationState: State<Location?>) {
  public val location: Location? by locationState
}

public data class Location(
  val position: Position,
  val accuracy: Double,
  val bearing: Double?,
  val bearingAccuracy: Double?,
  val age: Duration,
)

@Composable
public fun rememberUserLocationState(geoLocator: GeoLocator): UserLocationState {
  val locationState = geoLocator.location.collectAsStateWithLifecycle()
  return remember(locationState) { UserLocationState(locationState) }
}
