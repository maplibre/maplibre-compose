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

// MapLibre Native works in logical pixels, the same units as Compose's dp, so these conversions are
// unit-preserving; scaling by density here would double-apply it on a HiDPI display.

// TODO: once maplibre-native-ffi covers every target this library does, the common public geometry
// types should be typealiases for its own, and this file should go away rather than grow an
// equivalent on each platform.

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

internal fun PaddingValues.toEdgeInsets(layoutDirection: LayoutDirection): EdgeInsets =
  EdgeInsets(
    top = calculateTopPadding().value.toDouble(),
    left = calculateLeftPadding(layoutDirection).value.toDouble(),
    bottom = calculateBottomPadding().value.toDouble(),
    right = calculateRightPadding(layoutDirection).value.toDouble(),
  )

/**
 * Snapshots a camera into an immutable value. [CameraOptions] is a mutable builder for native
 * calls, so a later call could mutate one held in Compose state underneath it.
 */
internal fun CameraOptions.toCameraPosition(): CameraPosition =
  CameraPosition(
    target = center?.toPosition() ?: Position(0.0, 0.0),
    zoom = zoom ?: 0.0,
    bearing = bearing ?: 0.0,
    tilt = pitch ?: 0.0,
  )

internal fun CameraPosition.toCameraOptions(padding: EdgeInsets): CameraOptions =
  CameraOptions().also {
    it.center = target.toLatLng()
    it.zoom = zoom
    it.bearing = bearing
    it.pitch = tilt
    it.padding = padding
  }

/** Converts physical Compose pixels to the logical pixels MapLibre projects in. */
internal fun Density.physicalPixelsToScreenPoint(x: Float, y: Float): ScreenPoint =
  ScreenPoint(x = (x / density).toDouble(), y = (y / density).toDouble())
