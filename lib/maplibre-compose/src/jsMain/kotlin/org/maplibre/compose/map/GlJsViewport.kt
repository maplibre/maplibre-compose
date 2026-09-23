package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toVisibleBounds
import org.maplibre.spatialk.geojson.Position

/**
 * Reads the viewport of the transform this map currently holds, for a map [width] and [height] in
 * logical pixels. [viewportInsets] are excluded from the camera's padding.
 */
internal fun MaplibreMap.readViewport(
  width: Double,
  height: Double,
  viewportInsets: PaddingOptions,
): Viewport =
  Viewport(
    cameraPosition = readCameraPosition(viewportInsets),
    size = DpSize(width.dp, height.dp),
    visibleBounds = getBounds().toVisibleBounds(),
    visibleRegion = readVisibleRegion(width, height),
  )

/** Reads the camera this map currently holds, with [viewportInsets] excluded from its padding. */
internal fun MaplibreMap.readCameraPosition(viewportInsets: PaddingOptions): CameraPosition =
  CameraPosition(
    bearing = getBearing(),
    target = getCenter().toPosition(),
    tilt = getPitch(),
    padding =
      getPadding().let {
        DpPadding(
          // Fractional insets leave floating-point residue, and GL JS rejects negative padding.
          left = (it.left - viewportInsets.left).coerceAtLeast(0.0).dp,
          top = (it.top - viewportInsets.top).coerceAtLeast(0.0).dp,
          right = (it.right - viewportInsets.right).coerceAtLeast(0.0).dp,
          bottom = (it.bottom - viewportInsets.bottom).coerceAtLeast(0.0).dp,
        )
      },
    zoom = getZoom(),
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
