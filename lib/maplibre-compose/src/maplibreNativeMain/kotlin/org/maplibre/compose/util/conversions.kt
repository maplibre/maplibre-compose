package org.maplibre.compose.util

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.CameraPadding
import org.maplibre.nativeffi.Camera
import org.maplibre.nativeffi.CameraOptions
import org.maplibre.nativeffi.EdgeInsets
import org.maplibre.nativeffi.LatLng
import org.maplibre.nativeffi.LatLngBounds
import org.maplibre.nativeffi.ScreenPoint
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

// MapLibre Native works in logical pixels, the same units as Compose's dp, so these conversions are
// unit-preserving; scaling by density here would double-apply it on a HiDPI display.

internal fun LatLng.toPosition(): Position = Position(longitude = longitude, latitude = latitude)

internal fun Position.toLatLng(): LatLng = LatLng(latitude = latitude, longitude = longitude)

internal fun ScreenPoint.toDpOffset(): DpOffset = DpOffset(x.dp, y.dp)

internal fun DpOffset.toScreenPoint(): ScreenPoint =
  ScreenPoint(x = x.value.toDouble(), y = y.value.toDouble())

internal fun LatLngBounds.toBoundingBox(): BoundingBox =
  BoundingBox(southwest = southwest.toPosition(), northeast = northeast.toPosition())

internal fun BoundingBox.toLatLngBounds(): LatLngBounds =
  LatLngBounds(
    southwest = southwest.toLatLng(),
    northeast =
      LatLng(
        latitude = north,
        longitude = if (east < west) east + 360.0 else east,
      ),
  )

/** The camera position of these options, with padding inside [viewportInsets]. */
internal fun CameraOptions.toCameraPosition(viewportInsets: EdgeInsets): CameraPosition =
  CameraPosition(
    center = center?.toPosition() ?: Position(0.0, 0.0),
    zoom = zoom ?: 0.0,
    bearing = bearing ?: 0.0,
    pitch = pitch ?: 0.0,
    padding = (padding ?: EdgeInsets()).relativeTo(viewportInsets),
  )

/** A published camera has every field; no defaults need to be supplied by Compose. */
internal fun Camera.toCameraPosition(viewportInsets: EdgeInsets): CameraPosition =
  CameraPosition(
    center = center.toPosition(),
    zoom = zoom,
    bearing = bearing,
    pitch = pitch,
    padding = padding.relativeTo(viewportInsets),
  )

internal fun CameraPosition.toCameraOptions(viewportInsets: EdgeInsets): CameraOptions =
  CameraOptions(
    center = center.toLatLng(),
    zoom = zoom,
    bearing = bearing,
    pitch = pitch,
    padding = padding.inside(viewportInsets),
  )

internal fun CameraUpdate.toCameraOptions(viewportInsets: EdgeInsets): CameraOptions =
  CameraOptions(
    center = center?.toLatLng(),
    zoom = zoom,
    bearing = bearing,
    pitch = pitch,
    padding = padding?.inside(viewportInsets),
  )

private fun DpPadding.inside(viewportInsets: EdgeInsets): EdgeInsets =
  (viewportInsets.toCameraPadding() + this).toEdgeInsets()

internal fun EdgeInsets.relativeTo(insets: EdgeInsets): DpPadding =
  toCameraPadding().relativeTo(insets.toCameraPadding())

internal fun EdgeInsets.toCameraPadding(): CameraPadding = CameraPadding(top, left, bottom, right)

internal fun CameraPadding.toEdgeInsets(): EdgeInsets = EdgeInsets(top, left, bottom, right)

internal fun DpPadding.toEdgeInsets(): EdgeInsets = (CameraPadding() + this).toEdgeInsets()
