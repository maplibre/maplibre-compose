@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.StyleComposition

@Composable
@OptIn(ExperimentalResourceApi::class)
// The snippet below shows the calls alone. Requesting the permission belongs to the surrounding
// app, which the documentation covers in prose.
fun Location() {
  // #region puck
  val mapState = rememberMapState()

  val locationProvider = rememberDefaultLocationProvider()
  val headingProvider = rememberDefaultHeadingProvider() // optional: get heading from sensors

  val locationState =
    rememberLocationState(
      provider = locationProvider,
      headingProvider = headingProvider,
    )

  val composition =
    remember(locationState) {
      StyleComposition {
        LocationPuck(
          idPrefix = "user",
          locationState = locationState,
        )

        LocationTrackingEffect(locationState = locationState) {
          mapState.presentation?.animateCameraPosition(
            CameraPosition(target = currentLocation.position, zoom = 15.0)
          )
        }
      }
    }
  MaplibreMap(state = mapState, styleComposition = composition)
  // #endregion puck
}
