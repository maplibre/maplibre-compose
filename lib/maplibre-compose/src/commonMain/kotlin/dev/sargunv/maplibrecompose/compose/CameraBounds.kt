package dev.sargunv.maplibrecompose.compose

import io.github.dellisd.spatialk.geojson.BoundingBox

/**
 * @param zoomRange The allowable bounds for the camera zoom level.
 * @param pitchRange The allowable bounds for the camera pitch.
 * @param boundingBox The allowable bounds for the camera position. Behaves differently on
 *   android and iOS:
 *   android: prevents the camera center target from going outside of bounds
 *   iOS: prevents the camera edges from going outside of bounds.
 */
public data class CameraBounds(
  val zoomRange: ClosedRange<Float> = 0f..20f,
  val pitchRange: ClosedRange<Float> = 0f..60f,
  val boundingBox: BoundingBox? = null
)
