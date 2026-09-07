package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Limits the camera position that the map can display.
 *
 * @param boundingBox The region the camera target must stay within, or null for no geographic
 *   limit. A region that crosses the antimeridian is supported in both encodings: the GeoJSON
 *   convention with an east longitude less than the west longitude, and continuous longitudes with
 *   an east longitude past ±180°. How the camera settles against an edge is engine-defined.
 */
@Immutable
public data class CameraConstraints(
  public val minZoom: Double = 0.0,
  public val maxZoom: Double = 20.0,
  public val minPitch: Double = 0.0,
  public val maxPitch: Double = 60.0,
  public val boundingBox: BoundingBox? = null,
)
