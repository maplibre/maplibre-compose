package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object Manhattan3dDemo : Demo {
  override val name = "3D Manhattan"
  override val description = "Liberty's building extrusions pitched over the financial district."
  override val region = BoundingBox(west = -74.020, south = 40.700, east = -73.993, north = 40.722)
  override val preferredStyle = OpenFreeMap.Liberty

  override val camera =
    CameraPosition(
      target = Position(longitude = -74.0109, latitude = 40.7085),
      zoom = 14.2,
      bearing = 2.0,
      tilt = 60.0,
    )

  @Composable
  override fun Panel() {
    OfflineRegionSection(region = region, styleUrl = preferredStyle.base.uri, packName = name)
  }
}

/**
 * A panel section that downloads [region] in the style at [styleUrl] for offline use, with progress
 * and a delete control. The section is empty on the web target, which has no offline API.
 */
@Composable expect fun OfflineRegionSection(region: BoundingBox, styleUrl: String, packName: String)
