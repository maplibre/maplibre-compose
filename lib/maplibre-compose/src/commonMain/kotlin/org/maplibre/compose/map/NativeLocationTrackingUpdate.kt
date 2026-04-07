package org.maplibre.compose.map

import org.maplibre.compose.location.Location
import org.maplibre.compose.location.NativeLocationPuck
import org.maplibre.compose.location.UserTrackingMode

internal data class NativeLocationTrackingUpdate(
  val location: Location?,
  val trackingMode: UserTrackingMode,
  val puck: NativeLocationPuck,
) {
  val isEnabled: Boolean
    get() = trackingMode != UserTrackingMode.None || puck != NativeLocationPuck.None
}
