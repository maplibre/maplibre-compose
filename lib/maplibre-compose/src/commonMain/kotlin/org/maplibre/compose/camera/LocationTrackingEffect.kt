package org.maplibre.compose.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.UserLocationState

@Composable
public fun LocationTrackingEffect(
  locationState: UserLocationState,
  enabled: Boolean = true,
  trackBearing: Boolean = true,
  precision: Double = 0.00001, // approx 1 m
  onLocationChange: suspend LocationChangeScope.() -> Unit,
) {
  LaunchedEffect(enabled, trackBearing) {
    if (!enabled) return@LaunchedEffect

    snapshotFlow { locationState.location }
      .filterNotNull()
      .distinctUntilChanged equal@{ old, new ->
        if (trackBearing && (old.bearing != null || new.bearing != null)) {
          if (old.bearing == null) return@equal true
          if (new.bearing == null) return@equal true
          if (abs(old.bearing - new.bearing) > precision) return@equal true
        }

        if (abs(old.position.latitude - new.position.latitude) > precision) return@equal true
        if (abs(old.position.longitude - new.position.longitude) > precision) return@equal true

        false
      }
      .collect { LocationChangeScopeImpl(currentLocation = it).onLocationChange() }
  }
}

public interface LocationChangeScope {
  public val currentLocation: Location

  public suspend fun CameraState.updateFromLocation(
    animationDuration: Duration? = 300.milliseconds,
    updateBearing: BearingUpdate = BearingUpdate.TRACK_LOCATION,
  )
}

public enum class BearingUpdate {
  IGNORE,
  ALWAYS_NORTH,
  TRACK_LOCATION,
}

internal class LocationChangeScopeImpl(override val currentLocation: Location) :
  LocationChangeScope {
  override suspend fun CameraState.updateFromLocation(
    animationDuration: Duration?,
    updateBearing: BearingUpdate,
  ) {
    val cameraState = this

    val newPosition =
      when (updateBearing) {
        BearingUpdate.IGNORE -> {
          cameraState.position.copy(target = currentLocation.position)
        }

        BearingUpdate.ALWAYS_NORTH -> {
          cameraState.position.copy(target = currentLocation.position, bearing = 0.0)
        }

        BearingUpdate.TRACK_LOCATION -> {
          cameraState.position.copy(
            target = currentLocation.position,
            bearing = currentLocation.bearing ?: cameraState.position.bearing,
          )
        }
      }

    if (animationDuration != null) {
      cameraState.animateTo(newPosition, animationDuration)
    } else {
      cameraState.position = newPosition
    }
  }
}
