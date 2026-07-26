package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.nativeffi.camera.CameraOptions
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.geo.LatLng
import org.maplibre.nativeffi.geo.LatLngBounds
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

// MapLibre Native works in logical pixels with a top-left origin, and Compose's dp are the same
// logical units, so these conversions are unit-preserving rather than density-scaled. Multiplying
// by density here would double-apply the scale factor on any HiDPI display.

internal fun LatLng.toPosition(): Position = Position(longitude = longitude, latitude = latitude)

internal fun Position.toLatLng(): LatLng = LatLng(latitude = latitude, longitude = longitude)

internal fun ScreenPoint.toDpOffset(): DpOffset = DpOffset(x.dp, y.dp)

internal fun DpOffset.toScreenPoint(): ScreenPoint =
  ScreenPoint(x = x.value.toDouble(), y = y.value.toDouble())

internal fun LatLngBounds.toBoundingBox(): BoundingBox =
  BoundingBox(southwest = southwest.toPosition(), northeast = northeast.toPosition())

internal fun BoundingBox.toLatLngBounds(): LatLngBounds =
  LatLngBounds(southwest = southwest.toLatLng(), northeast = northeast.toLatLng())

internal fun PaddingValues.toEdgeInsets(layoutDirection: LayoutDirection): EdgeInsets =
  EdgeInsets(
    top = calculateTopPadding().value.toDouble(),
    left = calculateLeftPadding(layoutDirection).value.toDouble(),
    bottom = calculateBottomPadding().value.toDouble(),
    right = calculateRightPadding(layoutDirection).value.toDouble(),
  )

internal fun EdgeInsets.toPaddingValues(): PaddingValues =
  PaddingValues(start = left.dp, top = top.dp, end = right.dp, bottom = bottom.dp)

/**
 * Snapshots a camera into an immutable value.
 *
 * [CameraOptions] is a mutable builder for native calls, so handing one to Compose state would leak
 * a native-facing object that a later call could mutate underneath it. The conversion is needed
 * regardless, since the public API speaks [CameraPosition]; it is not standing in for anything
 * missing upstream. (It once also existed because the options types had no `equals`, which defeated
 * state diffing — fixed by https://github.com/maplibre/maplibre-native-ffi/pull/342.)
 */
internal fun CameraOptions.toCameraPosition(): CameraPosition =
  CameraPosition(
    target = center?.toPosition() ?: Position(0.0, 0.0),
    zoom = zoom ?: 0.0,
    bearing = bearing ?: 0.0,
    tilt = pitch ?: 0.0,
    padding = padding?.toPaddingValues() ?: PaddingValues(0.dp),
  )

internal fun CameraPosition.toCameraOptions(layoutDirection: LayoutDirection): CameraOptions =
  CameraOptions().also {
    it.center = target.toLatLng()
    it.zoom = zoom
    it.bearing = bearing
    it.pitch = tilt
    it.padding = padding.toEdgeInsets(layoutDirection)
  }

/** Converts physical Compose pixels to the logical pixels MapLibre projects in. */
internal fun Density.physicalPixelsToScreenPoint(x: Float, y: Float): ScreenPoint =
  ScreenPoint(x = (x / density).toDouble(), y = (y / density).toDouble())
