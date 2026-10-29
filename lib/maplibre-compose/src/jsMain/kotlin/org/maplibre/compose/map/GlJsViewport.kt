package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.ViewportProjection
import org.maplibre.compose.gljs.GlJsTerrain
import org.maplibre.compose.gljs.GlJsTransform
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.util.AngleMath
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toCameraPadding
import org.maplibre.compose.util.toDpOffset
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
      metersPerDpAtCenter = metersPerDpAtLatitude(getZoom(), getCenter().toPosition().latitude),
    )
    // GL JS updates one transform in place, so the viewport keeps its own copy.
    .withProjection(TransformProjection(_camera.transform.clone(), terrain))

/** Projects like `Map.project` and `Map.unproject`, but for one frozen [transform]. */
private class TransformProjection(
  private val transform: GlJsTransform,
  private val terrain: GlJsTerrain?,
) : ViewportProjection {
  override fun screenLocationFromPosition(position: Position): DpOffset {
    // GL JS projects a longitude as given, while MapLibre Native wraps it onto the world copy
    // nearest the camera. Wrap here so both engines share one contract.
    val center = transform.center
    val nearestCopy = with(AngleMath) { center.lng + position.longitude.diff(center.lng) }
    return transform
      .locationToScreenPoint(LngLat(lng = nearestCopy, lat = position.latitude), terrain)
      .toDpOffset()
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position =
    transform
      .screenPointToLocation(
        unsafeJso<Point> {
          x = offset.x.value.toDouble()
          y = offset.y.value.toDouble()
        },
        terrain,
      )
      .toPosition()

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(transform.zoom, latitude)
}

/** Reads the camera this map currently holds, with [viewportInsets] excluded from its padding. */
internal fun MaplibreMap.readCameraPosition(viewportInsets: PaddingOptions): CameraPosition =
  CameraPosition(
    bearing = getBearing(),
    center = getCenter().toPosition(),
    pitch = getPitch(),
    padding = getPadding().toCameraPadding().relativeTo(viewportInsets.toCameraPadding()),
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
      },
    )
    .toPosition()
