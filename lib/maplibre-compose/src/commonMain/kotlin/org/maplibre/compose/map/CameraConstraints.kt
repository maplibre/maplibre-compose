package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

/** Limits the camera position that the map can display. */
@Immutable
public data class CameraConstraints(
  public val minZoom: Double = 0.0,
  public val maxZoom: Double = 20.0,
  public val minPitch: Double = 0.0,
  public val maxPitch: Double = 60.0,
  public val boundingBox: BoundingBox? = null,
)
