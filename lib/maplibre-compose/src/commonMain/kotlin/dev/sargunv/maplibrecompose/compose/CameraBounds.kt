package dev.sargunv.maplibrecompose.compose

import io.github.dellisd.spatialk.geojson.BoundingBox

/**
 * @param zoomRange The allowable bounds for the camera zoom level.
 * @param pitchRange The allowable bounds for the camera pitch.
 * @param boundingBox The allowable bounds for the camera position. On Android, it prevents the
 *   camera center from going out of bounds. On iOS, it prevents the camera edges from going out of
 *   bounds.
 */
public data class CameraBounds(
  val zoomRange: ClosedRange<Float> = 0f..20f,
  val pitchRange: ClosedRange<Float> = 0f..60f,
  val boundingBox: BoundingBox? = null,
)
