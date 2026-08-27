package org.maplibre.compose.map

import org.maplibre.spatialk.geojson.BoundingBox

internal data class CameraConstraints(
  val minZoom: Double,
  val maxZoom: Double,
  val minPitch: Double,
  val maxPitch: Double,
  val boundingBox: BoundingBox?,
)
