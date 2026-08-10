package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.LngLatBounds
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

// MapLibre GL JS works in CSS pixels, the same units as Compose's dp, so these conversions are
// unit-preserving; scaling by density here would double-apply it on a HiDPI display.

internal fun LngLat.toPosition(): Position = Position(longitude = lng, latitude = lat)

internal fun Position.toLngLat(): LngLat = LngLat(lng = longitude, lat = latitude)

internal fun DpOffset.toPoint(): Point = unsafeJso {
  x = this@toPoint.x.value.toDouble()
  y = this@toPoint.y.value.toDouble()
}

internal fun Point.toDpOffset(): DpOffset = DpOffset(x.dp, y.dp)

internal fun LngLatBounds.toBoundingBox(): BoundingBox =
  BoundingBox(southwest = getSouthWest().toPosition(), northeast = getNorthEast().toPosition())

internal fun BoundingBox.toLngLatBounds(): LngLatBounds =
  LngLatBounds(sw = southwest.toLngLat(), ne = northeast.toLngLat())

internal fun PaddingValues.toPaddingOptions(layoutDirection: LayoutDirection): PaddingOptions =
  unsafeJso {
    top = calculateTopPadding().value.toDouble()
    left = calculateLeftPadding(layoutDirection).value.toDouble()
    bottom = calculateBottomPadding().value.toDouble()
    right = calculateRightPadding(layoutDirection).value.toDouble()
  }

internal fun PaddingOptions.toPaddingValues(): PaddingValues =
  PaddingValues.Absolute(left = left.dp, top = top.dp, right = right.dp, bottom = bottom.dp)
