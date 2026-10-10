package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.nativeffi.generated.CameraOptions
import org.maplibre.nativeffi.generated.EdgeInsets
import org.maplibre.nativeffi.generated.LatLng
import org.maplibre.nativeffi.generated.LatLngBounds
import org.maplibre.nativeffi.generated.ScreenPoint
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

internal fun PaddingValues.toEdgeInsets(layoutDirection: LayoutDirection): EdgeInsets =
  EdgeInsets(
    top = calculateTopPadding().value.toDouble(),
    left = calculateLeftPadding(layoutDirection).value.toDouble(),
    bottom = calculateBottomPadding().value.toDouble(),
    right = calculateRightPadding(layoutDirection).value.toDouble(),
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
  EdgeInsets(
    viewportInsets.top + top.value,
    viewportInsets.left + left.value,
    viewportInsets.bottom + bottom.value,
    viewportInsets.right + right.value,
  )

/**
 * The camera padding inside [insets]. Fractional insets round-trip through the engine with
 * floating-point residue, and the engine rejects any negative padding, so each side clamps at zero.
 */
internal fun EdgeInsets.relativeTo(insets: EdgeInsets): DpPadding =
  DpPadding(
    left = (left - insets.left).coerceAtLeast(0.0).dp,
    top = (top - insets.top).coerceAtLeast(0.0).dp,
    right = (right - insets.right).coerceAtLeast(0.0).dp,
    bottom = (bottom - insets.bottom).coerceAtLeast(0.0).dp,
  )

internal fun DpPadding.toEdgeInsets(): EdgeInsets =
  EdgeInsets(
    top = top.value.toDouble(),
    left = left.value.toDouble(),
    bottom = bottom.value.toDouble(),
    right = right.value.toDouble(),
  )
