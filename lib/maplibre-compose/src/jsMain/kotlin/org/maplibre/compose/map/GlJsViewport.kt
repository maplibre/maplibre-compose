package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toPosition
import org.maplibre.spatialk.geojson.Position

/**
 * Reads the viewport of the transform this map currently holds, for a map [width] and [height] in
 * logical pixels.
 */
internal fun MaplibreMap.readViewport(width: Double, height: Double): Viewport =
  Viewport(
    size = DpSize(width.dp, height.dp),
    visibleBoundingBox = getBounds().toBoundingBox(),
    visibleRegion = readVisibleRegion(width, height),
    metersPerDpAtTarget = metersPerDpAtLatitude(getZoom(), getCenter().toPosition().latitude),
  )

/** Unprojects the four corners of a [width] by [height] map into a visible region. */
internal fun MaplibreMap.readVisibleRegion(width: Double, height: Double): VisibleRegion =
  VisibleRegion(
    farLeft = unprojectAt(0.0, 0.0),
    farRight = unprojectAt(width, 0.0),
    nearLeft = unprojectAt(0.0, height),
    nearRight = unprojectAt(width, height),
  )

/** Unprojects a point in logical pixels from the top left of the map. */
internal fun MaplibreMap.unprojectAt(x: Double, y: Double): Position =
  unproject(
      unsafeJso<Point> {
        this.x = x
        this.y = y
      }
    )
    .toPosition()
