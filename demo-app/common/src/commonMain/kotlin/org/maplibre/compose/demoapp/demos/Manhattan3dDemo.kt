package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.center
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object Manhattan3dDemo : Demo {
  override val name = "3D Manhattan"
  override val description = "View 3D buildings in Manhattan's financial district."
  override val preferredLightStyle = OpenFreeMap.Liberty

  private val offlineRegion =
    BoundingBox(west = -74.020, south = 40.700, east = -73.993, north = 40.722)

  override val destination =
    DemoDestination.ExactCamera(
      CameraPosition(
        target = Position(longitude = -74.0109, latitude = 40.7085),
        zoom = 14.2,
        bearing = 2.0,
        tilt = 60.0,
      )
    )
  override val pointerPin = DemoPointerPin(offlineRegion.center, destination)

  @Composable
  override fun Panel(state: DemoAppState) {
    OfflineRegionSection(
      region = offlineRegion,
      styleUrl = preferredLightStyle.base.uri,
      packName = name,
    )
  }
}

/**
 * A panel section that downloads [region] in the style at [styleUrl] for offline use, with progress
 * and a delete control. The section is empty on the web target, which has no offline API.
 */
@Composable expect fun OfflineRegionSection(region: BoundingBox, styleUrl: String, packName: String)
